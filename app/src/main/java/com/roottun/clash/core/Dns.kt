package com.roottun.clash.core

/**
 * 自定义 DNS 的解析、校验与生效顺序。
 *
 * 参考 tunclash 的做法：
 * - 预设（国内 / Google / Cloudflare）给出一组 DoH + 传统 DNS；
 * - 自定义允许逐条填写 IP、DoH（https://…）或 DoT（tls://…）；
 * - 无论哪种预设，都额外追加国内兜底，避免所选 DNS 不可达时直接断网。
 */
object Dns {

    val FALLBACK: List<String> = listOf("https://dns.alidns.com/dns-query", "223.5.5.5")

    val PRESETS: Map<AppSettings.DnsPreset, List<String>> = mapOf(
        AppSettings.DnsPreset.CN to listOf("https://dns.alidns.com/dns-query", "223.5.5.5", "119.29.29.29"),
        AppSettings.DnsPreset.GOOGLE to listOf("https://dns.google/dns-query", "8.8.8.8"),
        AppSettings.DnsPreset.CLOUDFLARE to listOf("https://cloudflare-dns.com/dns-query", "1.1.1.1"),
    )

    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(:\d{1,5})?$""")
    private val IPV6 = Regex("""^[0-9a-fA-F:]+$""")
    private val URL = Regex("""^(https|tls|quic)://[^\s/]+(/\S*)?$""", RegexOption.IGNORE_CASE)
    private val PLAIN_HOST = Regex("""^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$""")

    /** 单条 DNS 是否看起来可用。 */
    fun isValidEntry(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty() || v.length > 512 || v.any { it.isWhitespace() }) return false
        if (URL.matches(v)) return true
        IPV4.matchEntire(v)?.let { m ->
            return (1..4).all { m.groupValues[it].toIntOrNull()?.let { n -> n in 0..255 } == true }
        }
        if (':' in v && IPV6.matches(v.substringBefore(':'))) return true
        return PLAIN_HOST.matches(v)
    }

    /**
     * 生成写入 mihomo `dns.nameserver` 的列表。
     * 自定义为空或全部非法时退回国内预设，避免内核起不来。
     */
    fun nameservers(state: AppSettings.State): List<String> {
        val primary = when (state.dnsPreset) {
            AppSettings.DnsPreset.CUSTOM -> state.customDnsEntries.filter(::isValidEntry)
            else -> PRESETS[state.dnsPreset].orEmpty()
        }.ifEmpty { PRESETS[AppSettings.DnsPreset.CN].orEmpty() }
        return (primary + FALLBACK).distinct()
    }

    /** 设置页展示用的简短描述。 */
    fun summary(state: AppSettings.State): String = when (state.dnsPreset) {
        AppSettings.DnsPreset.CN -> "国内优化（阿里 DoH）"
        AppSettings.DnsPreset.GOOGLE -> "Google DoH"
        AppSettings.DnsPreset.CLOUDFLARE -> "Cloudflare DoH"
        AppSettings.DnsPreset.CUSTOM -> if (state.customDnsEntries.isEmpty()) {
            "自定义（未填写，回退国内优化）"
        } else {
            "自定义 · ${state.customDnsEntries.size} 条"
        }
    }
}
