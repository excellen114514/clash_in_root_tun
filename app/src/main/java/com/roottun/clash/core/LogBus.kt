package com.roottun.clash.core

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 日志级别，顺序即过滤优先级。 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** 一条日志记录，包含时间戳、级别、来源标签与正文。 */
data class LogLine(
    val id: Long,
    val timeMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    val timeText: String
        get() = TIME_FORMAT.get()!!.format(Date(timeMillis))

    companion object {
        private val TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }
    }
}

/**
 * 应用内日志总线。
 *
 * 所有 Root 命令、内核启动/停止、订阅拉取与异常堆栈都必须经过这里，禁止静默失败
 * （任务硬性约束 C5）。
 */
object LogBus {
    private const val MAX_LINES = 3000
    private const val LOGCAT_TAG = "RootTunClash"
    private const val FILE_LIMIT_BYTES = 1_500_000L

    private val lock = Any()
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    private var nextId = 1L

    @Volatile
    private var fileSink: BufferedWriter? = null

    @Volatile
    private var fileSinkPath: File? = null

    @Volatile
    var mirrorToLogcat: Boolean = true

    fun attachFile(file: File) {
        synchronized(lock) {
            closeSinkLocked()
            runCatching {
                file.parentFile?.mkdirs()
                if (file.length() > FILE_LIMIT_BYTES) {
                    val backup = File(file.parentFile, file.name + ".1")
                    if (backup.exists()) backup.delete()
                    file.renameTo(backup)
                }
                fileSink = BufferedWriter(FileWriter(file, true))
                fileSinkPath = file
            }.onFailure {
                fileSink = null
                fileSinkPath = null
                Log.w(LOGCAT_TAG, "无法打开日志文件: ${it.message}")
            }
            logLocked(LogLevel.INFO, "APP", "日志文件已就绪: ${file.absolutePath}")
        }
    }

    fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    fun log(level: LogLevel, tag: String, message: String) {
        val safeTag = tag.ifBlank { "APP" }
        synchronized(lock) {
            message.split('\n').forEach { line ->
                if (line.isNotEmpty() || message.isNotEmpty()) {
                    logLocked(level, safeTag, line)
                }
            }
        }
    }

    fun exception(tag: String, message: String, throwable: Throwable) {
        error(tag, message)
        val stack = throwable.stackTraceToString().split('\n').take(20)
        stack.forEach { error(tag, "  $it") }
    }

    fun clear() {
        synchronized(lock) {
            _lines.value = emptyList()
            logLocked(LogLevel.INFO, "APP", "日志已清空")
        }
    }

    fun snapshotText(maxLines: Int = 500): String = synchronized(lock) {
        _lines.value.takeLast(maxLines).joinToString("\n") { line ->
            "${FILE_TIME.get()!!.format(Date(line.timeMillis))} ${line.level.name}/" +
                "${line.tag}: ${line.message}"
        }
    }

    private val credentialUrl = Regex("""(?i)\b(?:https?|ssr?|vmess|vless|trojan|tuic|hysteria2|hy2)://[^\s「」（），<>]+""")

    private fun logLocked(level: LogLevel, tag: String, raw: String) {
        // Redact before every sink, including exceptions, exported logs and redirected URLs.
        val message = credentialUrl.replace(raw) { "[地址已省略]" }
        val now = System.currentTimeMillis()
        val entry = LogLine(nextId++, now, level, tag, message)
        val current = _lines.value
        _lines.value = if (current.size >= MAX_LINES) {
            current.subList(current.size - MAX_LINES + 1, current.size).toMutableList().apply { add(entry) }
        } else {
            current + entry
        }

        if (mirrorToLogcat) {
            runCatching {
                when (level) {
                    LogLevel.DEBUG -> Log.d(LOGCAT_TAG, "[$tag] $message")
                    LogLevel.INFO -> Log.i(LOGCAT_TAG, "[$tag] $message")
                    LogLevel.WARN -> Log.w(LOGCAT_TAG, "[$tag] $message")
                    LogLevel.ERROR -> Log.e(LOGCAT_TAG, "[$tag] $message")
                }
            }
        }

        val sink = fileSink
        if (sink != null) {
            runCatching {
                sink.write("${FILE_TIME.get()!!.format(Date(now))} ${level.name}/$tag: $message")
                sink.newLine()
                sink.flush()
            }.onFailure {
                runCatching { closeSinkLocked() }
            }
        }
    }

    private fun closeSinkLocked() {
        runCatching { fileSink?.flush() }
        runCatching { fileSink?.close() }
        fileSink = null
        fileSinkPath = null
    }

    private val FILE_TIME = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
