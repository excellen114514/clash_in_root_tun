package com.roottun.clash.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsTest {

    private val base = AppSettings.State()

    @Test
    fun presetsContainDohAndIpv4() {
        val cn = Dns.nameservers(base.copy(dnsPreset = AppSettings.DnsPreset.CN))
        assertTrue(cn.any { it.startsWith("https://") })
        assertTrue(cn.any { it == "223.5.5.5" })

        val google = Dns.nameservers(base.copy(dnsPreset = AppSettings.DnsPreset.GOOGLE))
        assertTrue(google.contains("https://dns.google/dns-query"))
        assertTrue(google.contains("8.8.8.8"))
    }

    @Test
    fun customEntriesAreParsedAndDeduplicated() {
        val state = base.copy(
            dnsPreset = AppSettings.DnsPreset.CUSTOM,
            customDns = "https://dns.google/dns-query, 8.8.8.8\n1.1.1.1, 8.8.8.8",
        )
        assertEquals(3, state.customDnsEntries.size)
        val servers = Dns.nameservers(state)
        assertTrue(servers.contains("https://dns.google/dns-query"))
        assertTrue(servers.contains("8.8.8.8"))
        assertTrue(servers.contains("1.1.1.1"))
        // 国内兜底必须保留，避免自定义 DoH 不可达时直接断网。
        assertTrue(servers.contains("https://dns.alidns.com/dns-query"))
        assertEquals(servers.size, servers.distinct().size)
    }

    @Test
    fun emptyCustomFallsBackToDomesticPreset() {
        val state = base.copy(dnsPreset = AppSettings.DnsPreset.CUSTOM, customDns = "   ")
        val servers = Dns.nameservers(state)
        assertTrue(servers.contains("223.5.5.5"))
        assertFalse(state.dnsReady)
    }

    @Test
    fun invalidEntriesAreReported() {
        assertTrue(Dns.isValidEntry("8.8.8.8"))
        assertTrue(Dns.isValidEntry("https://dns.google/dns-query"))
        assertTrue(Dns.isValidEntry("tls://1.1.1.1:853"))
        assertTrue(Dns.isValidEntry("1.1.1.1:53"))
        assertFalse(Dns.isValidEntry("999.1.1.1"))
        assertFalse(Dns.isValidEntry("not a dns"))
        assertFalse(Dns.isValidEntry(""))

        val bad = base.copy(dnsPreset = AppSettings.DnsPreset.CUSTOM, customDns = "8.8.8.8, bad host")
        assertFalse(bad.dnsReady)
        // 非法条目不会进入生效列表，但仍保留国内兜底。
        val servers = Dns.nameservers(bad)
        assertEquals(listOf("8.8.8.8", "https://dns.alidns.com/dns-query", "223.5.5.5"), servers)
    }

    @Test
    fun composedConfigUsesSelectedNameservers() {
        val state = base.copy(
            dnsPreset = AppSettings.DnsPreset.CUSTOM,
            customDns = "https://dns.google/dns-query,1.1.1.1",
        )
        val yaml = ConfigComposer.runtimeOverrides(
            RuntimeOptions(dnsNameservers = Dns.nameservers(state)),
        )
        assertTrue(yaml.contains("  nameserver:"))
        assertTrue(yaml.contains(Regex("""(?m)^\s+- "?https://dns\.google/dns-query"?$""")))
        assertTrue(yaml.contains(Regex("""(?m)^\s+- "?1\.1\.1\.1"?$""")))
        assertTrue(yaml.contains("  proxy-server-nameserver:"))
        assertTrue(yaml.contains("  default-nameserver:"))
    }
}
