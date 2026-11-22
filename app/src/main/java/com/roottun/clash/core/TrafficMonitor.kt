package com.roottun.clash.core

import android.os.SystemClock
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 本次内核运行的累计流量与瞬时速率（字节 / 字节每秒）。 */
data class TrafficSnapshot(
    val running: Boolean = false,
    val uploadTotal: Long = 0L,
    val downloadTotal: Long = 0L,
    val uploadRate: Long = 0L,
    val downloadRate: Long = 0L,
    val connections: Int = 0,
    val updatedAt: Long = 0L,
    val error: String? = null,
)

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[index])
}

fun formatRate(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

/**
 * 通过 mihomo 的 unix socket 控制器轮询 `/connections`，读取累计上下行字节数并计算速率。
 * 不新增任何本地 TCP 监听，也不依赖外部网络。
 */
class TrafficMonitor(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "TRAFFIC"
        private const val POLL_INTERVAL_MS = 2_000L
    }

    private val _state = MutableStateFlow(TrafficSnapshot())
    val state: StateFlow<TrafficSnapshot> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var socketPath: String = ""

    fun start(path: String) {
        if (path.isBlank()) return
        socketPath = path
        if (job?.isActive == true) return
        _state.value = TrafficSnapshot(running = true)
        job = scope.launch(Dispatchers.IO) {
            var lastUpload = 0L
            var lastDownload = 0L
            var lastAt = 0L
            var failures = 0
            while (isActive) {
                val current = socketPath
                if (current.isBlank()) break
                MihomoApi(current).fetchTraffic()
                    .onSuccess { totals ->
                        val now = SystemClock.elapsedRealtime()
                        val seconds = if (lastAt > 0L) (now - lastAt).coerceAtLeast(1L) / 1000.0 else 0.0
                        val uploadRate = if (seconds > 0.0 && totals.upload >= lastUpload) {
                            ((totals.upload - lastUpload) / seconds).toLong()
                        } else 0L
                        val downloadRate = if (seconds > 0.0 && totals.download >= lastDownload) {
                            ((totals.download - lastDownload) / seconds).toLong()
                        } else 0L
                        _state.value = TrafficSnapshot(
                            running = true,
                            uploadTotal = totals.upload,
                            downloadTotal = totals.download,
                            uploadRate = uploadRate,
                            downloadRate = downloadRate,
                            connections = totals.connections,
                            updatedAt = System.currentTimeMillis(),
                        )
                        lastUpload = totals.upload
                        lastDownload = totals.download
                        lastAt = now
                        failures = 0
                    }
                    .onFailure { throwable ->
                        failures++
                        if (failures == 1 || failures % 15 == 0) {
                            LogBus.warn(TAG, "读取流量统计失败：${throwable.message ?: throwable.javaClass.simpleName}")
                        }
                        _state.update {
                            it.copy(
                                running = true,
                                updatedAt = System.currentTimeMillis(),
                                error = throwable.message ?: throwable.javaClass.simpleName,
                            )
                        }
                    }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        socketPath = ""
        job?.cancel()
        job = null
        _state.value = TrafficSnapshot()
    }
}
