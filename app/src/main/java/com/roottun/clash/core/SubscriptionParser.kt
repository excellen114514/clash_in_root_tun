package com.roottun.clash.core

sealed class ParsedSubscription {
    data class ClashYaml(val yaml: String, val nodeCount: Int) : ParsedSubscription()
    data class Links(val report: ShareLinks.ParseReport) : ParsedSubscription()
}

class SubscriptionParseException(message: String) : Exception(message)

/**
 * 订阅格式自动识别：Clash YAML / base64 分享链接列表 / 明文分享链接列表。
 *
 * 三种格式分别独立解析，失败时抛出带原始响应片段的 [SubscriptionParseException]，
 * 由调用方写入日志控制台（而不是只显示「失败」）。
 */
object SubscriptionParser {

    private val YAML_MARKERS = setOf(
        "proxies", "proxy-groups", "proxy-providers", "rules", "rule-providers",
        "mixed-port", "port", "socks-port", "tun", "dns", "listeners",
    )

    fun parse(body: String): ParsedSubscription {
        val text = body.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) {
            throw SubscriptionParseException("订阅内容为空（0 字节）")
        }

        if (looksLikeClashYaml(text)) {
            return ParsedSubscription.ClashYaml(text, countNodes(text))
        }

        val decoded = ShareLinks.decodeBase64(text)
        if (decoded != null) {
            val decodedText = decoded.trim()
            if (looksLikeClashYaml(decodedText)) {
                LogBus.info("SUBSCRIPTION", "检测到 base64 包裹的 Clash YAML，已解码")
                return ParsedSubscription.ClashYaml(decodedText, countNodes(decodedText))
            }
            if (ShareLinks.containsLinks(decodedText)) {
                LogBus.info("SUBSCRIPTION", "检测到 base64 分享链接列表，开始逐条解析")
                val report = ShareLinks.parse(decodedText)
                requireNodes(report, text)
                return ParsedSubscription.Links(report)
            }
        }

        if (ShareLinks.containsLinks(text)) {
            LogBus.info("SUBSCRIPTION", "检测到明文分享链接列表，开始逐条解析")
            val report = ShareLinks.parse(text)
            requireNodes(report, text)
            return ParsedSubscription.Links(report)
        }

        throw SubscriptionParseException(buildDiagnostics(text))
    }

    fun looksLikeClashYaml(text: String): Boolean {
        if (text.isEmpty()) return false
        val keys = ClashYaml.topLevelKeys(text)
        return keys.any { it in YAML_MARKERS }
    }

    fun countNodes(text: String): Int = ClashYaml.extractProxyNames(text).size

    private fun requireNodes(report: ShareLinks.ParseReport, original: String) {
        if (report.nodes.isEmpty()) {
            val warningText = report.warnings.take(3).joinToString("；")
            throw SubscriptionParseException(
                "base64/明文链接中共识别到分享链接，但没有任何一条解析成功。" +
                    (if (warningText.isNotBlank()) "失败原因：$warningText" else "") +
                    " 原始片段：${snippet(original)}",
            )
        }
    }

    private fun buildDiagnostics(text: String): String {
        val lineCount = text.lineSequence().count()
        val firstLine = text.lineSequence().firstOrNull().orEmpty().take(120)
        val hasColon = text.contains(':')
        val hasProxies = text.contains("proxies:", ignoreCase = false)
        val isMostlyBase64 = text.take(512).count { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' } >
            (text.take(512).length * 0.9)
        return buildString {
            append("无法识别的订阅格式：既不是 Clash YAML，也不是 base64 或明文分享链接列表。")
            append("（长度=${text.length}，行数=$lineCount，包含冒号=$hasColon，出现 proxies=$hasProxies，疑似 base64=$isMostlyBase64）")
            append(" 首行片段：$firstLine")
        }
    }

    private fun snippet(text: String): String = text.take(180).replace("\n", "\\n").replace("\r", "")
}
