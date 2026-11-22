package com.roottun.clash.core

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * mihomo 内核生命周期管理。
 *
 * 与参考项目「exec 后即认为成功」不同，这里会等待
 * ① 进程存活 ② 外部控制端口可连接 ③ TUN 设备出现在 `ip link` 中，
 * 超时后把捕获到的内核 stderr 一起写进日志。
 */
class KernelManager(private val root: RootShell) {

    companion object {
        private const val TAG = "MIHOMO"
        private const val HEALTH_TIMEOUT_MS = 15_000L
        private const val RETRY_TIMEOUT_MS = 12_000L
        private const val POLL_INTERVAL_MS = 300L
        private const val RING_SIZE = 240
    }

    data class Handle(
        val pid: Int,
        val process: Process,
        val startedAt: Long,
        val tunDevice: String,
        val controllerPort: Int,
        val stack: String,
    )

    class KernelStartException(
        message: String,
        val diagnostics: List<String> = emptyList(),
    ) : Exception(message)

    private val ring = ArrayDeque<String>()
    private val ringLock = Any()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var handle: Handle? = null

    val isRunning: Boolean get() = process?.isAlive == true

    fun recentOutput(limit: Int = 40): List<String> = synchronized(ringLock) {
        ring.toList().takeLast(limit)
    }

    fun start(
        binary: File,
        homeDir: File,
        config: File,
        opts: RuntimeOptions,
        timeoutMillis: Long = HEALTH_TIMEOUT_MS,
        onExit: (Int) -> Unit = {},
    ): Handle {
        if (isRunning) {
            throw KernelStartException("mihomo 已在运行（pid=${handle?.pid ?: -1}）")
        }
        val pidFile = Workspace.pidFile
        runCatching { pidFile.delete() }

        val command = buildString {
            // 记录内核真实 pid：$$ 是当前 sh 的 pid，exec 之后同一进程变成 mihomo。
            append("echo \$\$ > ").append(Workspace.shellQuote(pidFile.absolutePath)).append("; ")
            append("cd ").append(Workspace.shellQuote(homeDir.absolutePath)).append("; ")
            append("exec ").append(Workspace.shellQuote(binary.absolutePath))
            append(" -d ").append(Workspace.shellQuote(homeDir.absolutePath))
            append(" -f ").append(Workspace.shellQuote(config.absolutePath))
        }

        LogBus.info(TAG, "启动命令：$command")
        val spawned = try {
            root.spawnStreaming(command, TAG, "$TAG/ERR") { isError, line ->
                appendRing(if (isError) "[stderr] $line" else line)
            }
        } catch (t: Throwable) {
            throw KernelStartException("无法启动 mihomo 进程：${t.message ?: t.javaClass.simpleName}")
        }
        process = spawned

        val watcher = Thread({
            val exitCode = runCatching { spawned.waitFor() }.getOrDefault(-1)
            LogBus.warn(TAG, "mihomo 进程已退出（exit=$exitCode）")
            // 启动失败/兼容栈重试/主动停止不属于运行期崩溃，否则旧 watcher 会拆掉新内核。
            if (handle?.process === spawned && process === spawned) {
                handle = null
                runCatching { onExit(exitCode) }
            }
        }, "mihomo-watcher")
        watcher.isDaemon = true
        watcher.start()

        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastReason = "等待内核就绪"
        while (System.currentTimeMillis() < deadline) {
            if (!spawned.isAlive) {
                val exit = runCatching { spawned.exitValue() }.getOrNull() ?: -1
                val diagnostics = recentOutput(40)
                process = null
                throw KernelStartException(
                    "mihomo 启动后立即退出（exit=$exit）。$lastReason",
                    diagnostics,
                )
            }
            val controllerReady = controllerReady(opts)
            val tunReady = if (opts.enableTun) tunVisible(opts.tunDevice) else true
            if (controllerReady && tunReady) {
                val pid = readPid(pidFile)
                val result = Handle(
                    pid = pid,
                    process = spawned,
                    startedAt = System.currentTimeMillis(),
                    tunDevice = opts.tunDevice,
                    controllerPort = opts.controllerPort,
                    stack = opts.tunStack,
                )
                handle = result
                LogBus.info(
                    TAG,
                    "内核就绪：pid=$pid, stack=${opts.tunStack}, controller=${controllerLabel(opts)}, " +
                        "tun=${if (opts.enableTun) opts.tunDevice else "未启用"}",
                )
                return result
            }
            lastReason = buildString {
                append("控制器 ${controllerLabel(opts)} ${if (controllerReady) "就绪" else "未就绪"}")
                if (opts.enableTun) {
                    append("，TUN 设备 ${opts.tunDevice} ${if (tunReady) "已出现" else "未出现"}")
                }
            }
            runCatching { Thread.sleep(POLL_INTERVAL_MS) }
        }

        val diagnostics = recentOutput(60)
        LogBus.error(TAG, "内核在 ${timeoutMillis}ms 内未就绪：$lastReason")
        diagnostics.takeLast(20).forEach { LogBus.error(TAG, "  内核输出: $it") }
        stopProcessQuietly(pidFile)
        throw KernelStartException("mihomo 在 ${timeoutMillis}ms 内未就绪（$lastReason）", diagnostics)
    }

    fun stop() {
        val pid = handle?.pid ?: readPid(Workspace.pidFile)
        val current = process
        if (pid > 0) {
            root.terminatePid(pid, TAG)
        }
        current?.let { proc ->
            runCatching { if (proc.isAlive) proc.destroy() }
            runCatching { if (proc.isAlive) proc.destroyForcibly() }
        }
        handle = null
        process = null
        runCatching { Workspace.pidFile.delete() }
        LogBus.info(TAG, "mihomo 内核已停止")
    }

    private fun stopProcessQuietly(pidFile: File) {
        val pid = readPid(pidFile)
        if (pid > 0) runCatching { root.terminatePid(pid, TAG, waitMillis = 2_000) }
        process?.let { proc -> runCatching { if (proc.isAlive) proc.destroy() } }
        process = null
        handle = null
    }

    private fun readPid(pidFile: File): Int {
        val raw = root.readFile(pidFile.absolutePath, TAG) ?: return -1
        return raw.lineSequence().firstOrNull()?.trim()?.toIntOrNull() ?: -1
    }

    /** 控制器就绪判定：优先 unix socket（无 TCP 监听），否则回退 TCP 端口探测。 */
    private fun controllerReady(opts: RuntimeOptions): Boolean {
        val socketPath = opts.controllerSocketPath
        if (socketPath.isNotBlank()) {
            return File(socketPath).exists()
        }
        val port = opts.controllerPort
        if (port <= 0) return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 400)
                true
            }
        }.getOrDefault(false)
    }

    private fun controllerLabel(opts: RuntimeOptions): String =
        if (opts.controllerSocketPath.isNotBlank()) "unix:${opts.controllerSocketPath}" else "127.0.0.1:${opts.controllerPort}"

    private fun tunVisible(device: String): Boolean {
        val result = root.exec(
            "ip link show ${Workspace.shellQuote(device)} 2>/dev/null | head -n 2",
            timeoutMillis = 6_000,
            tag = TAG,
            echo = false,
            logStdout = false,
        )
        return result.ok && result.stdoutText().contains(device)
    }

    private fun appendRing(line: String) {
        synchronized(ringLock) {
            ring.addLast(line)
            while (ring.size > RING_SIZE) ring.removeFirst()
        }
    }
}
