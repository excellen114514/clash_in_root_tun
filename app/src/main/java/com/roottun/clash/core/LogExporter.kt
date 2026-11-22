package com.roottun.clash.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.roottun.clash.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志导出（下载）。
 *
 * - Android 10+：写入公共「下载/RootTunClash/」，无需任何存储权限；
 * - Android 8/9：写入应用专属外部目录（`Android/data/<pkg>/files/Download/`），无需权限。
 *
 * 导出内容 = 设备信息头 + 日志控制台当前的完整日志（含时间戳、级别、来源标签）。
 */
object LogExporter {

    private const val TAG = "LOG"
    private const val MAX_EXPORT_LINES = 5000

    data class Export(
        val location: String,
        val bytes: Int,
    )

    fun export(context: Context): Result<Export> = runCatching {
        val content = buildContent()
        // MediaStore 会按 MIME 追加扩展名，这里直接使用 .txt，避免出现 “.log.txt”。
        val fileName = "RootTunClash-log-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val bytes = content.toByteArray(Charsets.UTF_8)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + File.separator + "RootTunClash",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法在「下载」目录创建文件")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("无法写入日志文件")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
            val location = "Download/RootTunClash/$fileName"
            LogBus.info(TAG, "日志已导出到 $location（${bytes.size} 字节）")
            Export(location, bytes.size)
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            LogBus.info(TAG, "日志已导出到 ${file.absolutePath}（${bytes.size} 字节）")
            Export(file.absolutePath, bytes.size)
        }
    }.onFailure { throwable ->
        LogBus.error(TAG, "导出日志失败：${throwable.javaClass.simpleName}: ${throwable.message}")
    }

    private fun buildContent(): String = buildString {
        appendLine("========== RootTun Clash 日志导出 ==========")
        appendLine("应用：${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine(
            "设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT}) / ABI=${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}",
        )
        appendLine("导出时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        appendLine("-------------------------------------------")
        append(LogBus.snapshotText(MAX_EXPORT_LINES))
        appendLine()
    }
}
