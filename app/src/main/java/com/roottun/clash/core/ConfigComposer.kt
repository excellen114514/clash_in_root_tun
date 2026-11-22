package com.roottun.clash.core

/** 运行期参数，全部来自用户在设置页里的选择。 */
data class RuntimeOptions(
    val mixedPort: Int = 7890,
    val controllerPort: Int = 9090,
    val dnsPort: Int = 1053,
    val controllerSocketPath: String = "",
    val tunDevice: String = "net0",
    val tunStack: String = "gvisor",
    val tunMtu: Int = 1500,
    val strictRoute: Boolean = true,
    val enableTun: Boolean = true,
    val appRouting: ResolvedAppRouting = ResolvedAppRouting(),
)

/**
 * 把订阅内容 / 分享链接组装成一份完整的 mihomo 配置。
 *
 * 设计要点：
 * - 订阅里的 `proxies` / `proxy-groups` / `rules` 逐字保留，不重写用户规则；
 * - 只覆盖由本应用接管的运行期顶层键（TUN / DNS / 端口 / 控制器）；
 * - 订阅缺少 `rules` 或 `proxy-groups` 时按最小可用规则补齐并记录到日志。
 */
object ConfigComposer {

    data class Composition(
        val yaml: String,
        val nodeCount: Int,
        val notes: List<String>,
    )

    fun compose(subscriptionConfig: String, opts: RuntimeOptions): Composition {
        val notes = mutableListOf<String>()
        val doc = ClashYaml.parse(subscriptionConfig)
        val nodes = doc.entries("proxies")
        val groups = doc.entries("proxy-groups")
        val makeGroup = nodes.isNotEmpty() && groups.isEmpty() && "proxy-providers" !in doc.fields && nodes.none { it.name == "PROXY" }
        val policy = opts.appRouting
        require(!policy.enabled || opts.enableTun) { "应用分流必须使用 root TUN" }
        // 直接引用一个具体静态节点，不使用可能选到 DIRECT、COMPATIBLE 或 PASS 的订阅组。
        // mihomo 的规则以逗号分割，不能安全引用含逗号或边缘空格的名称。
        fun usableName(name: String) = ',' !in name && name.trim() == name
        val forced = nodes.firstOrNull {
            it.type in ResolvedAppRouting.REMOTE_TYPES && usableName(it.name) &&
                it.name !in setOf("GLOBAL", "DIRECT", "REJECT", "COMPATIBLE", "PASS", "PASS-RULE", "REJECT-DROP")
        }?.name ?: "REJECT"
        when (policy.mode) {
            AppRoutingMode.OFF -> if (policy.hasProxy) notes += if (forced == "REJECT") {
                "没有可确认的静态远端节点（仅动态 provider 也不够）；指定代理的连接将拒绝"
            } else "指定代理固定使用订阅静态节点「$forced」，不跟随可切换直连的订阅组；不支持的 UDP 将拒绝"
            AppRoutingMode.WHITELIST -> notes += if (forced == "REJECT") {
                "白名单模式没有可确认的静态远端节点；勾选应用将被拒绝，其余应用直连"
            } else "白名单模式：仅勾选应用固定使用「$forced」，其余应用的 TUN 流量直连；不支持的 UDP 将拒绝"
            AppRoutingMode.BLACKLIST -> notes += "黑名单模式：勾选应用直连，其余应用按订阅规则"
        }
        val prefix = policy.prefix(forced)
        val removed = if (makeGroup) ClashYaml.MANAGED_KEYS + "proxy-groups" else ClashYaml.MANAGED_KEYS
        val yaml = buildString {
            appendLine("# 由 RootTun Clash 生成：运行期设置与用户分流规则优先")
            append(runtimeOverrides(opts))
            append(doc.body(removed, prefix))
            if (makeGroup) {
                appendLine("proxy-groups:")
                appendLine("  - name: PROXY")
                appendLine("    type: select")
                appendLine("    proxies:")
                nodes.forEach { appendLine("      - ${YamlWrite.quote(it.name)}") }
                appendLine("      - DIRECT")
                notes += "订阅缺少分组，已生成 PROXY（select）"
            }
            if ("rules" !in doc.fields) {
                val target = if (makeGroup) "PROXY" else (groups.firstOrNull()?.name ?: nodes.firstOrNull()?.name ?: "DIRECT")
                require(usableName(target)) { "缺少 rules 且默认出口名不能用作规则目标；请在订阅中明确 rules" }
                appendLine("rules:")
                (prefix + "MATCH,$target").forEach { appendLine("  - ${YamlWrite.quote(it)}") }
                notes += "订阅缺少 rules，已追加 MATCH,$target"
            }
        }
        return Composition(yaml, nodes.size, notes)
    }

    fun composeFromLinks(
        report: ShareLinks.ParseReport,
        opts: RuntimeOptions,
        geoipAvailable: Boolean,
    ): Composition {
        val notes = mutableListOf<String>()
        report.warnings.forEach { LogBus.warn("SUBSCRIPTION", it) }

        val yaml = buildString {
            appendLine("# 由 RootTun Clash 生成：来自分享链接订阅")
            appendLine(if (report.nodes.isEmpty()) "proxies: []" else "proxies:")
            report.nodes.forEach { node -> appendLine("  - ${YamlWrite.flowMap(node.fields + ("name" to node.name))}") }
            appendLine()
            appendLine("proxy-groups:")
            appendLine("  - name: PROXY")
            appendLine("    type: select")
            appendLine("    proxies:")
            appendLine("      - DIRECT")
            report.nodes.forEach { node -> appendLine("      - ${YamlWrite.quote(node.name)}") }
            appendLine()
            appendLine("rules:")
            if (geoipAvailable) {
                appendLine("  - GEOIP,CN,DIRECT")
            } else {
                notes += "内置 geoip.metadb 不可用，已省略 GEOIP,CN,DIRECT 规则"
            }
            appendLine("  - MATCH,PROXY")
        }.trimEnd() + "\n"

        if (report.nodes.isEmpty()) {
            notes += "未解析出任何节点"
        }
        val result = compose(yaml, opts)
        return result.copy(notes = notes + result.notes)
    }

    /** 没有订阅时，跟随订阅者直连；被指定代理的应用仍然拒绝，不得降级为直连。 */
    fun directConfig(opts: RuntimeOptions): String = compose("proxies: []\nrules:\n  - MATCH,DIRECT\n", opts).yaml

    fun runtimeOverrides(opts: RuntimeOptions): String = buildString {
        appendLine("# ---------------- 运行期覆盖（RootTun Clash 接管） ----------------")
        // 不开放任何本地 TCP 监听：
        // - mixed-port = 0  → 不起 HTTP/SOCKS 监听（流量全部经 TUN，不需要它）
        // - external-controller 走 unix socket → 没有 127.0.0.1:9090
        // - 不写 dns.listen → 不起 127.0.0.1:1053，DNS 由 TUN 的 dns-hijack 在栈内处理
        // 这样目标应用扫描 127.0.0.1 时找不到任何 mihomo 端口。
        appendLine("mixed-port: 0")
        appendLine("bind-address: 127.0.0.1")
        appendLine("allow-lan: false")
        appendLine("mode: rule")
        if (opts.appRouting.enabled) appendLine("find-process-mode: strict")
        appendLine("log-level: info")
        appendLine("external-controller: \"\"")
        if (opts.controllerSocketPath.isNotBlank()) {
            appendLine("external-controller-unix: ${YamlWrite.quote(opts.controllerSocketPath)}")
        }
        appendLine("secret: \"\"")
        appendLine("unified-delay: true")
        appendLine("tcp-concurrent: true")
        appendLine("ipv6: true")
        // 出站 TLS 使用 uTLS 模拟 Chrome 指纹，降低远端对代理客户端的识别概率。
        // mihomo: geodata-mode=true 使用经典的 GeoIP.dat，false 使用内置的 geoip.metadb。
        // 本应用只随包提供 geoip.metadb / geosite.dat，因此必须保持 false，
        // 否则 GEOIP 规则会触发内核尝试联网下载 GeoIP.dat（实测会卡住 90 秒并导致启动失败）。
        appendLine("geodata-mode: false")
        appendLine("geo-auto-update: false")
        appendLine("profile:")
        appendLine("  store-selected: true")
        appendLine("  store-fake-ip: true")
        appendLine("dns:")
        appendLine("  enable: true")
        appendLine("  ipv6: true")
        appendLine("  enhanced-mode: fake-ip")
        // 使用不常见的 RFC 2544 测试段，避免应用以 198.18.0.0/15 反查代理身份。
        appendLine("  fake-ip-range: 198.19.0.0/16")
        appendLine("  fake-ip-filter:")
        appendLine("    - '*.lan'")
        appendLine("    - '*.local'")
        appendLine("    - '+.local'")
        appendLine("    - 'localhost.*'")
        appendLine("    - 'localhost.ptlogin2.qq.com'")
        appendLine("    - '+.msftconnecttest.com'")
        appendLine("    - '+.msftncsi.com'")
        appendLine("  default-nameserver:")
        appendLine("    - 223.5.5.5")
        appendLine("    - 119.29.29.29")
        appendLine("  nameserver:")
        appendLine("    - 223.5.5.5")
        appendLine("    - 119.29.29.29")
        if (opts.enableTun) {
            appendLine("tun:")
            appendLine("  enable: true")
            appendLine("  stack: ${opts.tunStack}")
            appendLine("  device: ${opts.tunDevice}")
            appendLine("  auto-route: true")
            appendLine("  auto-detect-interface: true")
            // strict-route 防止部分流量绕过 TUN（例如未命中路由的直连），减少真实 IP 泄漏。
            appendLine("  strict-route: ${opts.strictRoute}")
            appendLine("  dns-hijack:")
            appendLine("    - any:53")
            // 1500 是常见链路 MTU，9000 反而是一个不自然、容易被指纹化的取值。
            appendLine("  mtu: ${opts.tunMtu}")
        } else {
            appendLine("tun:")
            appendLine("  enable: false")
        }
    }.trimEnd() + "\n"
}
