package com.roottun.clash.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 轻量设置存储（SharedPreferences + StateFlow）。
 *
 * 设置分为两类：
 * - 风格化：动态取色、深色模式、基准色、对比度、圆角、卡片样式；
 * - 配置功能：TUN、DNS、订阅请求、应用分流等运行期参数。
 *
 * 实验功能（Shizuku）默认隐藏，连续点击「关于」卡片 5 次后写入 [State.shizukuUnlocked]。
 */
object AppSettings {

    enum class DarkMode { SYSTEM, LIGHT, DARK }

    enum class Contrast { STANDARD, HIGH }

    enum class CornerStyle { DEFAULT, ROUND, SHARP }

    enum class CardStyle { FILLED, TONAL, OUTLINED }

    enum class DnsPreset { CN, GOOGLE, CLOUDFLARE, CUSTOM }

    data class State(
        val dynamicColor: Boolean = false,
        val darkMode: DarkMode = DarkMode.SYSTEM,
        val seedArgb: Long = 0xFF006A6A,
        val contrast: Contrast = Contrast.STANDARD,
        val cornerStyle: CornerStyle = CornerStyle.DEFAULT,
        val cardStyle: CardStyle = CardStyle.FILLED,
        val tunStack: String = "gvisor",
        val mixedPort: Int = 7890,
        val controllerPort: Int = 9090,
        val tunDevice: String = "net0",
        val dnsPort: Int = 1053,
        val dnsPreset: DnsPreset = DnsPreset.CN,
        val customDns: String = "",
        val manualPolicyRouting: Boolean = true,
        val bypassOwnTraffic: Boolean = true,
        val blockLocalProxyPorts: Boolean = true,
        val strictRoute: Boolean = true,
        val tunMtu: Int = 1400,
        val subscriptionUserAgent: String = SubscriptionFetcher.DEFAULT_USER_AGENT,
        val showTrafficNotification: Boolean = false,
        val shizukuUnlocked: Boolean = false,
    ) {
        /** 自定义 DNS 解析后的条目（已去空白、去重）。 */
        val customDnsEntries: List<String>
            get() = customDns.split(',', '\n', '，')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

        /** DNS 设置是否可用于生成配置。 */
        val dnsReady: Boolean
            get() = dnsPreset != DnsPreset.CUSTOM || customDnsEntries.all { Dns.isValidEntry(it) } &&
                customDnsEntries.isNotEmpty()
    }

    private const val PREFS = "root_tun_clash_settings"
    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        // 旧版本默认 TUN 名是 mihomo0，会命中 tun/vpn 类特征名检测，这里迁移到中性名称 net0。
        val storedTun = p.getString("tunDevice", "net0") ?: "net0"
        val migratedTun = if (storedTun == "mihomo0") "net0" else storedTun
        if (migratedTun != storedTun) {
            p.edit().putString("tunDevice", migratedTun).apply()
            LogBus.info("SETTINGS", "TUN 设备名已从 $storedTun 迁移为 $migratedTun（降低特征名暴露）")
        }
        // 旧偏好中 1500 也是运行时最大 1400 的上限。新键区分用户显式选择的 1500。
        val legacyMtu = p.getInt("tunMtu", 1400).coerceIn(1280, 1400)
        val migratedMtu = p.getInt("tunMtuCeiling", legacyMtu).coerceIn(1280, 1500)
        _state.value = State(
            dynamicColor = p.getBoolean("dynamicColor", false),
            darkMode = runCatching { DarkMode.valueOf(p.getString("darkMode", "SYSTEM") ?: "SYSTEM") }
                .getOrDefault(DarkMode.SYSTEM),
            seedArgb = p.getLong("seedArgb", 0xFF006A6A),
            contrast = runCatching { Contrast.valueOf(p.getString("contrast", "STANDARD") ?: "STANDARD") }
                .getOrDefault(Contrast.STANDARD),
            cornerStyle = runCatching { CornerStyle.valueOf(p.getString("cornerStyle", "DEFAULT") ?: "DEFAULT") }
                .getOrDefault(CornerStyle.DEFAULT),
            cardStyle = runCatching { CardStyle.valueOf(p.getString("cardStyle", "FILLED") ?: "FILLED") }
                .getOrDefault(CardStyle.FILLED),
            tunStack = p.getString("tunStack", "gvisor") ?: "gvisor",
            mixedPort = p.getInt("mixedPort", 7890),
            controllerPort = p.getInt("controllerPort", 9090),
            tunDevice = migratedTun,
            dnsPort = p.getInt("dnsPort", 1053),
            dnsPreset = runCatching { DnsPreset.valueOf(p.getString("dnsPreset", "CN") ?: "CN") }
                .getOrDefault(DnsPreset.CN),
            customDns = p.getString("customDns", "") ?: "",
            manualPolicyRouting = p.getBoolean("manualPolicyRouting", true),
            bypassOwnTraffic = p.getBoolean("bypassOwnTraffic", true),
            blockLocalProxyPorts = p.getBoolean("blockLocalProxyPorts", true),
            strictRoute = p.getBoolean("strictRoute", true),
            tunMtu = migratedMtu,
            subscriptionUserAgent = p.getString(
                "subscriptionUserAgent",
                SubscriptionFetcher.DEFAULT_USER_AGENT,
            ) ?: SubscriptionFetcher.DEFAULT_USER_AGENT,
            showTrafficNotification = p.getBoolean("showTrafficNotification", false),
            shizukuUnlocked = p.getBoolean("shizukuUnlocked", false),
        )
    }

    fun update(transform: (State) -> State) {
        val next = transform(_state.value)
        _state.value = next
        prefs?.edit()?.apply {
            putBoolean("dynamicColor", next.dynamicColor)
            putString("darkMode", next.darkMode.name)
            putLong("seedArgb", next.seedArgb)
            putString("contrast", next.contrast.name)
            putString("cornerStyle", next.cornerStyle.name)
            putString("cardStyle", next.cardStyle.name)
            putString("tunStack", next.tunStack)
            putInt("mixedPort", next.mixedPort)
            putInt("controllerPort", next.controllerPort)
            putString("tunDevice", next.tunDevice)
            putInt("dnsPort", next.dnsPort)
            putString("dnsPreset", next.dnsPreset.name)
            putString("customDns", next.customDns)
            putBoolean("manualPolicyRouting", next.manualPolicyRouting)
            putBoolean("bypassOwnTraffic", next.bypassOwnTraffic)
            putBoolean("blockLocalProxyPorts", next.blockLocalProxyPorts)
            putBoolean("strictRoute", next.strictRoute)
            putInt("tunMtuCeiling", next.tunMtu)
            putString("subscriptionUserAgent", next.subscriptionUserAgent)
            putBoolean("showTrafficNotification", next.showTrafficNotification)
            putBoolean("shizukuUnlocked", next.shizukuUnlocked)
        }?.apply()
        LogBus.debug(
            "SETTINGS",
            "设置已更新: tunStack=${next.tunStack}, dns=${next.dnsPreset}, " +
                "contrast=${next.contrast}, corner=${next.cornerStyle}, card=${next.cardStyle}",
        )
    }
}
