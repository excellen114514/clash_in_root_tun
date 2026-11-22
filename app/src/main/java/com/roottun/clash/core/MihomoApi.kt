package com.roottun.clash.core

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.ByteArrayOutputStream
import org.json.JSONObject

/**
 * mihomo external-controller（RESTful API）的最小客户端。
 *
 * **不经过任何 TCP 端口**：控制器通过 `external-controller-unix` 暴露 unix domain socket，
 * 应用用 [LocalSocket] 直接以 HTTP/1.1 与之通信。这样目标应用扫描 `127.0.0.1` 时
 * 找不到 mihomo 的任何监听端口。
 */
class MihomoApi(private val socketPath: String) {

    companion object {
        private const val TAG = "CONTROLLER"
        const val DEFAULT_TEST_URL = "http://www.gstatic.com/generate_204"
        private const val READ_TIMEOUT_MS = 10_000
    }

    data class GroupInfo(
        val name: String,
        val type: String,
        val now: String?,
        val members: List<String>,
    )

    data class Snapshot(
        val groups: List<GroupInfo>,
        val types: Map<String, String>,
    )

    data class TrafficTotals(
        val upload: Long,
        val download: Long,
        val connections: Int,
    )

    class ApiException(message: String) : Exception(message)

    fun fetchProxies(): Result<Snapshot> = runCatching {
        val body = request("GET", "/proxies")
        val root = JSONObject(body)
        val proxies = root.optJSONObject("proxies") ?: throw ApiException("响应缺少 proxies 字段")
        val types = mutableMapOf<String, String>()
        val groups = mutableListOf<GroupInfo>()
        proxies.keys().forEach { name ->
            val obj = proxies.optJSONObject(name) ?: return@forEach
            val type = obj.optString("type", "Unknown")
            types[name] = type
            val all = obj.optJSONArray("all")
            if (all != null) {
                val members = (0 until all.length()).mapNotNull { index -> all.optString(index).takeIf { it.isNotBlank() } }
                groups.add(
                    GroupInfo(
                        name = name,
                        type = type,
                        now = obj.optString("now").takeIf { it.isNotBlank() },
                        members = members,
                    ),
                )
            }
        }
        Snapshot(groups, types)
    }.recoverCatching { t ->
        throw if (t is ApiException) t else ApiException("读取 /proxies 失败：${t.javaClass.simpleName}: ${t.message}")
    }

    /** `/connections` 顶层的 uploadTotal / downloadTotal 是内核启动以来的累计值。 */
    fun fetchTraffic(): Result<TrafficTotals> = runCatching {
        val body = request("GET", "/connections")
        val root = JSONObject(body)
        TrafficTotals(
            upload = root.optLong("uploadTotal", 0L).coerceAtLeast(0L),
            download = root.optLong("downloadTotal", 0L).coerceAtLeast(0L),
            connections = root.optJSONArray("connections")?.length() ?: 0,
        )
    }.recoverCatching { t ->
        throw if (t is ApiException) t else ApiException("读取流量统计失败：${t.javaClass.simpleName}: ${t.message}")
    }

    fun select(group: String, node: String): Result<Unit> = runCatching {
        val payload = JSONObject().put("name", node).toString()
        request("PUT", "/proxies/${encodePath(group)}", payload)
        Unit
    }.recoverCatching { t ->
        throw if (t is ApiException) t else ApiException("切换节点失败：${t.javaClass.simpleName}: ${t.message}")
    }

    fun delay(node: String, timeoutMs: Int = 5_000, testUrl: String = DEFAULT_TEST_URL): Result<Int> = runCatching {
        val path = "/proxies/${encodePath(node)}/delay" +
            "?timeout=${timeoutMs.coerceIn(500, 30_000)}&url=${encodePath(testUrl)}"
        val body = request("GET", path)
        val json = JSONObject(body)
        val delay = json.optInt("delay", -1)
        if (delay <= 0) throw ApiException(json.optString("message", "测速失败"))
        delay
    }.recoverCatching { t ->
        throw if (t is ApiException) t else ApiException("测速失败：${t.javaClass.simpleName}: ${t.message}")
    }

    private fun request(method: String, path: String, body: String? = null): String {
        val payload = body?.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Accept: application/json\r\n")
            append("Connection: close\r\n")
            if (payload != null) {
                append("Content-Type: application/json\r\n")
                append("Content-Length: ").append(payload.size).append("\r\n")
            }
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)

        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.soTimeout = READ_TIMEOUT_MS
            socket.outputStream.apply {
                write(header)
                if (payload != null) write(payload)
                flush()
            }
            val response = readResponse(socket)
            if (response.code !in 200..299) {
                val message = runCatching { JSONObject(response.body).optString("message") }.getOrNull()
                throw ApiException(
                    "HTTP ${response.code}" +
                        if (!message.isNullOrBlank()) "：$message"
                        else if (response.body.isNotBlank()) "：${response.body.take(160)}" else "",
                )
            }
            return response.body
        } finally {
            runCatching { socket.close() }
        }
    }

    private data class HttpResponse(val code: Int, val body: String)

    private fun readResponse(socket: LocalSocket): HttpResponse {
        val input = socket.inputStream
        val statusLine = readLine(input) ?: throw ApiException("控制器连接被关闭")
        val parts = statusLine.split(' ')
        val code = parts.getOrNull(1)?.toIntOrNull() ?: throw ApiException("非法响应行：$statusLine")
        var contentLength = -1
        var chunked = false
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: -1
            } else if (line.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                line.substringAfter(':').trim().contains("chunked", ignoreCase = true)
            ) {
                chunked = true
            }
        }
        val bodyBytes = when {
            chunked -> readChunked(input)
            contentLength >= 0 -> {
                val buffer = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = input.read(buffer, read, contentLength - read)
                    if (count < 0) break
                    read += count
                }
                buffer.copyOf(read)
            }
            else -> {
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    buffer.write(chunk, 0, count)
                }
                buffer.toByteArray()
            }
        }
        return HttpResponse(code, String(bodyBytes, Charsets.UTF_8))
    }

    /** 读取 HTTP/1.1 chunked 编码的响应体（mihomo 控制器对部分接口使用 chunked）。 */
    private fun readChunked(input: java.io.InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            if (sizeLine.isBlank()) continue
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                // 吃掉 trailer 直到空行
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            var remaining = size
            val chunk = ByteArray(size)
            var read = 0
            while (remaining > 0) {
                val count = input.read(chunk, read, remaining)
                if (count < 0) break
                read += count
                remaining -= count
            }
            buffer.write(chunk, 0, read)
            readLine(input) // 每个 chunk 末尾的 CRLF
        }
        return buffer.toByteArray()
    }
    private fun readLine(input: java.io.InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
        }
        return buffer.toString("UTF-8")
    }

    private fun encodePath(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
