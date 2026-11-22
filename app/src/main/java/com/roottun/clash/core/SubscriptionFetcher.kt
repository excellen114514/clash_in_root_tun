package com.roottun.clash.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException

/**
 * HTTP(S) 订阅下载器。
 *
 * 重要：不少机场会**根据 User-Agent 返回不同格式的订阅**。本项目默认伪装成
 * ClashMetaForAndroid，避免被识别为旧版 Clash 客户端而拿到 `proxies: []` 的模板配置
 * （真实案例：某机场对含 `Clash/版本号` 的 UA 返回 `/日本/` 正则代理组模板，
 * mihomo 会直接报 `proxy group[0]: jp自动切换: '/日本/' not found`）。
 */
class SubscriptionFetcher {

    companion object {
        private const val TAG = "SUBSCRIPTION"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_BODY_BYTES = 20 * 1024 * 1024

        /** 默认 UA：与服务端兼容性最好的 Clash Meta 客户端。 */
        const val DEFAULT_USER_AGENT = "ClashMetaForAndroid/2.11.7.Meta"

        /** 依次尝试的 UA 列表（可被设置页的自定义 UA 覆盖）。 */
        val CANDIDATE_USER_AGENTS = listOf(
            DEFAULT_USER_AGENT,
            "clash.meta/1.19.34",
            "Clash Verge/2.0.0",
        )
    }

    data class Response(
        val body: String,
        val contentType: String?,
        val finalUrl: String,
        val bytes: Int,
        val userAgent: String,
    )

    class FetchException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun fetch(rawUrl: String, userAgent: String = DEFAULT_USER_AGENT): Response {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) throw FetchException("订阅 URL 为空")
        if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
            throw FetchException("订阅 URL 必须以 http:// 或 https:// 开头：$trimmed")
        }

        val url = try {
            URL(trimmed)
        } catch (t: Throwable) {
            throw FetchException("订阅 URL 格式非法：${t.message}", t)
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "gzip")
            }

            val code = connection.responseCode
            val contentType = connection.contentType
            LogBus.info(TAG, "HTTP $code ← $trimmed（UA=$userAgent，Content-Type=${contentType ?: "未知"}）")

            if (code != HttpURLConnection.HTTP_OK) {
                // 服务端错误页可能回显 token；只把状态码传给日志、界面和持久化错误字段。
                throw FetchException("HTTP 状态码 $code")
            }

            val encoding = connection.contentEncoding?.lowercase()
            val rawStream = connection.inputStream
            val inputStream = if (encoding != null && encoding.contains("gzip")) {
                LogBus.debug(TAG, "响应为 gzip 编码，自动解压")
                GZIPInputStream(rawStream)
            } else {
                rawStream
            }

            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0
            inputStream.use { stream ->
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BODY_BYTES) {
                        throw FetchException("订阅内容超过 ${MAX_BODY_BYTES / 1024 / 1024} MiB 限制，已中止")
                    }
                    buffer.write(chunk, 0, read)
                }
            }

            val bodyBytes = buffer.toByteArray()
            if (bodyBytes.isEmpty()) {
                throw FetchException("HTTP 200 但响应体为空（0 字节）")
            }
            val charset = parseCharset(contentType)
            val body = String(bodyBytes, charset)
            LogBus.info(TAG, "下载完成：${bodyBytes.size} 字节，字符集=$charset，最终 URL=${connection.url}")
            return Response(body, contentType, connection.url.toString(), bodyBytes.size, userAgent)
        } catch (e: FetchException) {
            throw e
        } catch (e: SSLException) {
            LogBus.error(TAG, "TLS/证书错误：${e.message}")
            throw FetchException("TLS/证书错误：${e.message}（订阅服务器证书不被信任或已被劫持）", e)
        } catch (e: UnknownHostException) {
            LogBus.error(TAG, "域名解析失败：${e.message}")
            throw FetchException("域名解析失败：${e.message}（请检查网络或 DNS）", e)
        } catch (e: SocketTimeoutException) {
            LogBus.error(TAG, "网络超时：${e.message}")
            throw FetchException("网络超时：连接 ${CONNECT_TIMEOUT_MS}ms / 读取 ${READ_TIMEOUT_MS}ms 超时", e)
        } catch (e: IOException) {
            LogBus.error(TAG, "网络 I/O 错误：${e.javaClass.simpleName}: ${e.message}")
            throw FetchException("网络错误：${e.javaClass.simpleName}: ${e.message}", e)
        } catch (t: Throwable) {
            LogBus.exception(TAG, "拉取订阅时发生未预期异常", t)
            throw FetchException("未预期异常：${t.javaClass.simpleName}: ${t.message}", t)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun parseCharset(contentType: String?): java.nio.charset.Charset {
        if (contentType == null) return Charsets.UTF_8
        val match = Regex("charset=([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(contentType)
        val name = match?.groupValues?.getOrNull(1) ?: return Charsets.UTF_8
        return runCatching { java.nio.charset.Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }
}
