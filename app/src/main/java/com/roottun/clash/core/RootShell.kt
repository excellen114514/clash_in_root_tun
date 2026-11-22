package com.roottun.clash.core

import android.content.Context
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 一条 Root 命令的执行结果，保留 stdout / stderr / 退出码，便于写入日志控制台。 */
data class RootResult(
    val command: String,
    val code: Int,
    val stdout: List<String>,
    val stderr: List<String>,
    val durationMillis: Long,
    val timedOut: Boolean = false,
    val failure: String? = null,
) {
    val ok: Boolean get() = !timedOut && failure == null && code == 0
    fun stdoutText(): String = stdout.joinToString("\n")
    fun stderrText(): String = stderr.joinToString("\n")
    override fun toString(): String = "RootResult(code=$code, ok=$ok, stdout=${stdout.size} lines)"
}

/**
 * 唯一的特权命令执行通道（约束 C2）。
 *
 * 提权走两条互补路径：
 * 1. libsu `Shell`（任务要求的 libsu core）：当 `Shell.isRoot == true` 时优先使用；
 * 2. 直接 `su -c`（ProcessBuilder）：当 libsu 只拿到非 Root shell 时兜底，并用于
 *    需要实时流式输出的长驻进程（mihomo）。
 *
 * 关键修复（相对参考项目）：
 * - libsu 在 su 被拒绝/超时时会**静默降级成非 Root shell**，这里每次 Root 检查都会
 *   重新建 shell 并校验 `id -u`，绝不把非 Root shell 缓存下来继续执行 TUN 命令；
 * - su 授权弹窗可能耗时十几秒，Root 检测有明确超时与可读日志；
 * - 会自动挑选当前应用 uid 真正可执行的 su 路径（MuMu 的 `/system/xbin/su` 对普通应用
 *   不可执行，必须使用 `/system/bin/su`）。
 */
class RootShell(private val context: Context) {

    companion object {
        private const val TAG = "ROOT"
        private const val LIBSU_TIMEOUT_SECONDS = 30L
        private const val MAX_LOGGED_LINES = 80
        private const val ROOT_PROBE_TIMEOUT_MS = 40_000L

        private val SU_CANDIDATES = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
        )

        enum class Mode { NONE, LIBSU, DIRECT_SU }
    }

    private val execLock = Any()
    private val rootCheckLock = Any()

    @Volatile
    private var libsuShell: Shell? = null

    @Volatile
    private var mode: Mode = Mode.NONE

    @Volatile
    private var suBinary: String? = null

    @Volatile
    private var cachedRoot: Boolean? = null

    @Volatile
    private var cachedRootAt: Long = 0L

    /** 当前提权方式，用于状态展示与日志。 */
    fun modeLabel(): String = when (mode) {
        Mode.LIBSU -> "libsu Shell"
        Mode.DIRECT_SU -> "su -c（${suBinary ?: "su"}）"
        Mode.NONE -> "未建立 Root 通道"
    }

    /** 探测 su 是否存在（不触发提权弹窗）。 */
    fun suBinaryPresent(): Boolean = resolveSuBinary() != null

    fun resolveSuBinary(): String? {
        suBinary?.let { path -> if (File(path).exists()) return path }
        val executable = SU_CANDIDATES.firstOrNull { path ->
            val file = File(path)
            file.exists() && file.canExecute()
        }
        val found = executable ?: SU_CANDIDATES.firstOrNull { File(it).exists() }
        suBinary = found
        return found
    }

    /**
     * Root 可用性检查。
     *
     * 每次都会重新建立提权通道；失败时会把「为什么失败」写进日志（su 缺失 / 非 Root shell /
     * 等待授权超时 / uid 不是 0）。
     */
    fun checkRoot(force: Boolean = false): Boolean {
        synchronized(rootCheckLock) {
            val now = System.currentTimeMillis()
            // 3 秒内已经成功探测过就直接复用，避免启动时 init 与 onResume 并发重复拉起 su。
            cachedRoot?.let { granted ->
                if (granted && now - cachedRootAt < if (force) 3_000L else 10_000L) return true
            }
            val su = resolveSuBinary()
        if (su == null) {
            val message = "系统中没有找到 su 可执行文件（${SU_CANDIDATES.joinToString()}）"
            LogBus.error(TAG, "未获得 Root 权限：$message")
            LogBus.error(TAG, "MuMu 模拟器：请打开 设置 → 其他设置 → Root 权限，并重启模拟器")
            rememberRoot(false)
            return false
        }
        LogBus.info(TAG, "su 路径：$su（exists=${File(su).exists()}, canExecute=${File(su).canExecute()}）")

        // 1) 直接用 su -c 探测：这条路径同时会弹出 MuMu / Magisk 的授权框。
        LogBus.info(TAG, "请求 Root 权限（如出现授权弹窗，请在设备上点击「允许」）…")
        val direct = runDirect(su, "id", ROOT_PROBE_TIMEOUT_MS, echo = false)
        val directUid = direct.stdoutText()
            .lineSequence()
            .firstOrNull { it.trim().startsWith("uid=") }
            ?.trim()
        if (direct.ok && directUid?.contains("uid=0") == true) {
            LogBus.info(TAG, "Root 检查通过：$directUid（su -c 通道）")
            suBinary = su
            mode = Mode.DIRECT_SU
            // 2) 尽量再升级到 libsu Shell（若 libsu 能拿到 Root，则优先使用它）。
            val shell = buildLibsuShell()
            if (shell != null) {
                libsuShell = shell
                mode = Mode.LIBSU
                LogBus.info(TAG, "libsu Shell 已就绪（Shell.isRoot=true），优先使用 libsu 执行命令")
            } else {
                LogBus.warn(TAG, "libsu 未能建立 Root shell，改用直接的 su -c 通道（功能等价）")
            }
            rememberRoot(true)
            return true
        }

        // 直接 su 失败时再给 libsu 一次机会（部分设备只对 libsu 的调用方式放行）。
        val shell = buildLibsuShell()
        if (shell != null && shell.isRoot) {
            libsuShell = shell
            mode = Mode.LIBSU
            LogBus.info(TAG, "Root 检查通过：libsu Shell isRoot=true")
            rememberRoot(true)
            return true
        }

        val reason = when {
            direct.timedOut -> "等待 Root 授权超时（${ROOT_PROBE_TIMEOUT_MS}ms），请确认授权弹窗已点击「允许」"
            direct.failure != null -> "su 执行失败：${direct.failure}"
            directUid != null -> "su 返回 $directUid（不是 uid=0）"
            direct.stderrText().isNotBlank() -> "su 返回错误：${direct.stderrText().take(200)}"
            else -> "su 返回非 0 退出码 exit=${direct.code}"
        }
        LogBus.error(TAG, "未获得 Root 权限：$reason")
        if (!File(su).canExecute()) {
            LogBus.error(
                TAG,
                "当前 su 路径对应用 uid 不可执行。MuMu 请确认已在模拟器设置中开启 Root，并在授权框中选择「永久记住选择」",
            )
        }
        rememberRoot(false)
        return false
        }
        return cachedRoot == true
    }

    private fun rememberRoot(granted: Boolean) {
        cachedRoot = granted
        cachedRootAt = System.currentTimeMillis()
        if (!granted) {
            mode = Mode.NONE
            runCatching { libsuShell?.close() }
            libsuShell = null
        }
    }

    private fun buildLibsuShell(): Shell? {
        return try {
            val builder = Shell.Builder.create()
                .setContext(context)
                // flags = 0：不把 stderr 合并进 stdout，便于分别记录两路输出。
                .setFlags(0)
                .setTimeout(LIBSU_TIMEOUT_SECONDS)
            val shell = builder.build()
            if (shell.isRoot) {
                shell
            } else {
                LogBus.warn(TAG, "libsu 只拿到非 Root shell（Shell.isRoot=false），已丢弃，避免特权命令降级执行")
                runCatching { shell.close() }
                null
            }
        } catch (t: Throwable) {
            LogBus.warn(TAG, "libsu Shell 创建失败：${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * 执行一条特权命令并记录全部输出。
     *
     * @param tag 日志来源标签（如 ROOT / TUN / MIHOMO）。
     */
    fun exec(
        command: String,
        timeoutMillis: Long = 20_000,
        tag: String = TAG,
        echo: Boolean = true,
        logStdout: Boolean = true,
    ): RootResult {
        if (echo) LogBus.info(tag, "$ $command")

        // 还没有 Root 通道时，先尝试建立一次（这样用户点启动时会自动弹授权框）。
        if (mode == Mode.NONE && cachedRoot != true) {
            checkRoot(force = true)
        }

        return when (mode) {
            Mode.LIBSU -> execWithLibsu(command, timeoutMillis, tag, logStdout)
            Mode.DIRECT_SU -> execDirect(command, timeoutMillis, tag, logStdout)
            Mode.NONE -> {
                val message = "未获得 Root 权限，未执行特权操作"
                LogBus.error(tag, message)
                RootResult(command, -1, emptyList(), listOf(message), 0L, failure = message)
            }
        }
    }

    private fun execWithLibsu(
        command: String,
        timeoutMillis: Long,
        tag: String,
        logStdout: Boolean,
    ): RootResult {
        val start = System.currentTimeMillis()
        val shell = libsuShell
        if (shell == null || !shell.isAlive) {
            LogBus.warn(tag, "libsu Shell 不可用，改用 su -c")
            mode = Mode.DIRECT_SU
            return execDirect(command, timeoutMillis, tag, logStdout)
        }
        val result: RootResult = try {
            val out = mutableListOf<String>()
            val err = mutableListOf<String>()
            val future = shell.newJob().add(command).to(out, err).enqueue()
            try {
                val shellResult = future.get(timeoutMillis, TimeUnit.MILLISECONDS)
                RootResult(
                    command = command,
                    code = shellResult.code,
                    stdout = shellResult.out.toList(),
                    stderr = shellResult.err.toList(),
                    durationMillis = System.currentTimeMillis() - start,
                )
            } catch (timeout: TimeoutException) {
                future.cancel(true)
                LogBus.error(tag, "命令超时（${timeoutMillis}ms）：${command.firstLine()}")
                RootResult(
                    command, -1, emptyList(), listOf("TIMEOUT"),
                    System.currentTimeMillis() - start, timedOut = true,
                )
            } catch (t: Throwable) {
                val cause = t.cause?.message ?: t.message ?: t.javaClass.simpleName
                LogBus.error(tag, "命令执行失败：$cause")
                RootResult(
                    command, -1, emptyList(), listOf(cause),
                    System.currentTimeMillis() - start, failure = cause,
                )
            }
        } catch (t: Throwable) {
            LogBus.exception(tag, "libsu 执行异常：${command.firstLine()}", t)
            mode = Mode.DIRECT_SU
            return execDirect(command, timeoutMillis, tag, logStdout)
        }
        return reportResult(result, tag, logStdout)
    }

    private fun execDirect(
        command: String,
        timeoutMillis: Long,
        tag: String,
        logStdout: Boolean,
    ): RootResult {
        val su = resolveSuBinary()
        if (su == null) {
            val message = "未找到 su，无法执行特权命令"
            LogBus.error(tag, message)
            return RootResult(command, -1, emptyList(), listOf(message), 0L, failure = message)
        }
        return reportResult(runDirect(su, command, timeoutMillis, echo = false), tag, logStdout)
    }

    private fun runDirect(
        su: String,
        command: String,
        timeoutMillis: Long,
        echo: Boolean,
    ): RootResult {
        val start = System.currentTimeMillis()
        if (echo) LogBus.info(TAG, "$ $command")
        return try {
            val process = ProcessBuilder(su, "-c", command).start()
            val stdout = mutableListOf<String>()
            val stderr = mutableListOf<String>()
            val outThread = pump(process.inputStream, stdout)
            val errThread = pump(process.errorStream, stderr)
            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                LogBus.error(TAG, "su 命令超时（${timeoutMillis}ms）：${command.firstLine()}")
                return RootResult(
                    command, -1, stdout.toList(), stderr.toList(),
                    System.currentTimeMillis() - start, timedOut = true,
                )
            }
            outThread.join(1_500)
            errThread.join(1_500)
            RootResult(
                command = command,
                code = process.exitValue(),
                stdout = stdout.toList(),
                stderr = stderr.toList(),
                durationMillis = System.currentTimeMillis() - start,
            )
        } catch (t: Throwable) {
            LogBus.exception(TAG, "su 执行异常：${command.firstLine()}", t)
            RootResult(
                command, -1, emptyList(), listOf(t.message ?: t.javaClass.simpleName),
                System.currentTimeMillis() - start, failure = t.message ?: t.javaClass.simpleName,
            )
        }
    }

    private fun pump(stream: InputStream, sink: MutableList<String>): Thread {
        val thread = Thread({
            try {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> synchronized(sink) { sink.add(line) } }
                }
            } catch (_: Throwable) {
            }
        }, "su-pump")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private fun reportResult(result: RootResult, tag: String, logStdout: Boolean): RootResult {
        if (result.failure == null && !result.timedOut) {
            if (logStdout) logLines(tag, LogLevel.INFO, result.stdout.take(MAX_LOGGED_LINES))
            logLines(tag, LogLevel.WARN, result.stderr.take(MAX_LOGGED_LINES))
            if (result.stdout.size > MAX_LOGGED_LINES) {
                LogBus.debug(tag, "… stdout 省略 ${result.stdout.size - MAX_LOGGED_LINES} 行")
            }
            LogBus.debug(tag, "exit=${result.code}（${result.durationMillis}ms）")
        }
        return result
    }

    /**
     * 把一段脚本写入应用私有目录后以 root 执行（适合多行命令，避免引号地狱）。
     */
    fun execScript(name: String, script: String, timeoutMillis: Long = 30_000, tag: String = TAG): RootResult {
        val file = File(Workspace.scriptDir, name)
        return try {
            Workspace.ensureDirs()
            file.writeText(script)
            exec("sh ${Workspace.shellQuote(file.absolutePath)}", timeoutMillis, tag)
        } catch (t: Throwable) {
            LogBus.exception(tag, "写入脚本失败: ${file.absolutePath}", t)
            RootResult(script, -1, emptyList(), listOf(t.toString()), 0L, failure = t.message)
        }
    }

    /** 读取 Root 文件（用于读取内核 pid 文件等）。 */
    fun readFile(path: String, tag: String = TAG): String? {
        val result = exec("cat ${Workspace.shellQuote(path)}", 10_000, tag, echo = false, logStdout = false)
        return if (result.ok) result.stdoutText().trim().ifEmpty { null } else null
    }

    /**
     * 以 root 启动一个长驻进程并实时转发输出（stdout / stderr 分开读取）。
     */
    fun spawnStreaming(
        command: String,
        stdoutTag: String,
        stderrTag: String,
        onLine: (isStderr: Boolean, line: String) -> Unit,
    ): Process {
        val su = resolveSuBinary() ?: throw IllegalStateException("未找到 su，无法启动内核")
        LogBus.info(stdoutTag, "$ su -c ${command.firstLine()}…")
        val process = ProcessBuilder(su, "-c", command)
            .redirectErrorStream(false)
            .start()
        startPump(process.inputStream, isError = false, tag = stdoutTag, onLine = onLine)
        startPump(process.errorStream, isError = true, tag = stderrTag, onLine = onLine)
        return process
    }

    /** 结束指定 pid（先 TERM，再 KILL），返回是否已终止。 */
    fun terminatePid(pid: Int, tag: String = TAG, waitMillis: Long = 6_000): Boolean {
        if (pid <= 0) return true
        LogBus.info(tag, "终止进程 pid=$pid")
        exec("kill $pid 2>/dev/null", 8_000, tag, echo = false, logStdout = false)
        val deadline = System.currentTimeMillis() + waitMillis
        while (System.currentTimeMillis() < deadline) {
            if (!isPidAlive(pid, tag)) {
                LogBus.info(tag, "进程 pid=$pid 已退出")
                return true
            }
            runCatching { Thread.sleep(200) }
        }
        LogBus.warn(tag, "进程 pid=$pid 未在 ${waitMillis}ms 内退出，发送 SIGKILL")
        exec("kill -9 $pid 2>/dev/null", 8_000, tag, echo = false, logStdout = false)
        runCatching { Thread.sleep(400) }
        return !isPidAlive(pid, tag)
    }

    fun isPidAlive(pid: Int, tag: String = TAG): Boolean {
        if (pid <= 0) return false
        val result = exec("kill -0 $pid 2>/dev/null && echo alive", 6_000, tag, echo = false, logStdout = false)
        return result.ok && result.stdoutText().contains("alive")
    }

    private fun startPump(
        stream: InputStream,
        isError: Boolean,
        tag: String,
        onLine: (isStderr: Boolean, line: String) -> Unit,
    ) {
        val thread = Thread({
            try {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            LogBus.log(if (isError) LogLevel.WARN else LogLevel.INFO, tag, line)
                            runCatching { onLine(isError, line) }
                        }
                    }
                }
            } catch (t: Throwable) {
                LogBus.debug(tag, "输出流已关闭：${t.javaClass.simpleName}")
            }
        }, if (isError) "su-stderr" else "su-stdout")
        thread.isDaemon = true
        thread.start()
    }

    private fun logLines(tag: String, level: LogLevel, lines: List<String>) {
        lines.forEach { line -> if (line.isNotBlank()) LogBus.log(level, tag, line) }
    }
}

internal fun String.firstLine(): String = substringBefore('\n').take(160)
