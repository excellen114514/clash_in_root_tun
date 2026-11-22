package com.roottun.clash.core

import java.io.File

/**
 * TUN 设备节点准备 + 策略路由 / iptables 规则的安装与回滚。
 *
 * 设计原则（针对参考项目的已知问题）：
 * - Android 的 TUN 控制设备是 `/dev/tun`，mihomo 默认打开 `/dev/net/tun`，
 *   因此启动前必须先建立软链接；
 * - 默认使用 mihomo 的 `auto-route`（它会创建 fwmark 0x2023 + table 2022 的策略路由）；
 *   只有在检测不到 auto-route 时才安装本应用自己的手工规则；
 * - 无论成功还是失败，[cleanup] 都是幂等的，且会清理内核异常退出后遗留的规则，
 *   避免设备断网。
 */
class NetGuard(private val root: RootShell) {

    private companion object {
        const val TAG = "TUN"
        const val CHAIN = "TUNCLASH"
        const val LOCAL_CHAIN = "TUNCLASH_LOCAL"
        const val MANUAL_MARK = "0x0d15"
        const val MANUAL_TABLE = 2023
        const val MIHOMO_MARK = "0x2023"
        const val MIHOMO_TABLE = 2022
        const val BYPASS_PREF = 8100
    }

    data class TunReport(
        val available: Boolean,
        val symlinkCreated: Boolean,
        val detail: String,
    )

    data class RoutingReport(
        val autoRouteDetected: Boolean,
        val manualApplied: Boolean,
        val notes: List<String>,
    )

    /** 确保 `/dev/net/tun` 存在（必要时软链接到 Android 的 `/dev/tun`）。 */
    fun ensureTunDevice(): TunReport {
        val script = """
            if [ ! -e /dev/net/tun ] && [ -e /dev/tun ]; then
              mkdir -p /dev/net
              ln -sf /dev/tun /dev/net/tun
              echo "CREATED_SYMLINK"
            fi
            if [ -e /dev/net/tun ]; then
              ls -l /dev/net/tun
              echo "TUN_OK"
            else
              echo "TUN_MISSING"
            fi
        """.trimIndent()

        val result = root.execScript("ensure_tun.sh", script, timeoutMillis = 20_000, tag = TAG)
        val created = result.stdout.any { it.contains("CREATED_SYMLINK") }
        val available = result.stdout.any { it.contains("TUN_OK") }
        if (available) {
            LogBus.info(
                TAG,
                "/dev/net/tun 可用" + if (created) "（已创建软链接 → /dev/tun）" else "（节点已存在）",
            )
        } else {
            LogBus.error(TAG, "/dev/net/tun 不可用：内核可能未启用 TUN 模块，或设备缺少 /dev/tun")
        }
        return TunReport(available, created, result.stdoutText())
    }

    /**
     * 检查 auto-route，必要时为后续的应用 UID 规则准备独立路由表。
     */
    fun prepareRouting(opts: RuntimeOptions, manualFallbackEnabled: Boolean): RoutingReport {
        val notes = mutableListOf<String>()
        val rules = root.exec("ip rule show 2>&1", timeoutMillis = 8_000, tag = TAG, logStdout = false)
        val ruleText = rules.stdoutText()
        val autoRoute = rules.ok && hasTunDefault(MIHOMO_TABLE, opts.tunDevice) &&
            Regex("""\blookup\s+(?:table\s+)?$MIHOMO_TABLE\b""").containsMatchIn(ruleText)

        if (autoRoute) {
            notes += "检测到 mihomo auto-route 策略路由（fwmark $MIHOMO_MARK / table $MIHOMO_TABLE）"
            LogBus.info(TAG, "策略路由检查：mihomo auto-route 已接管，无需手工规则")
        }

        var manualApplied = false
        if (!autoRoute && manualFallbackEnabled) {
            LogBus.warn(TAG, "未检测到 auto-route，尝试安装手工策略路由（用户可在设置中关闭）")
            val script = """
                set -e
                ip -4 route add default dev ${Workspace.shellQuote(opts.tunDevice)} table $MANUAL_TABLE
                if ip -6 addr show dev ${Workspace.shellQuote(opts.tunDevice)} scope global | grep -q 'inet6 '; then
                  ip -6 route add default dev ${Workspace.shellQuote(opts.tunDevice)} table $MANUAL_TABLE
                fi
                echo "MANUAL_ROUTING_APPLIED"
            """.trimIndent()
            val result = root.execScript("policy_routing.sh", script, timeoutMillis = 30_000, tag = TAG)
            manualApplied = result.ok && result.stdout.any { it.contains("MANUAL_ROUTING_APPLIED") }
            notes += if (manualApplied) {
                "已准备应用 UID 接管所需的独立路由表 $MANUAL_TABLE → ${opts.tunDevice}"
            } else {
                "auto-route 未生效，且手工策略路由安装失败（详见日志），流量可能未被完全接管"
            }
            if (!manualApplied) {
                LogBus.error(TAG, "独立路由表创建失败：${result.stderrText().take(300)}")
            }
        } else if (!autoRoute) {
            notes += "未检测到 auto-route，且设置中已禁用手工策略路由"
            LogBus.warn(TAG, "auto-route 未生效且手工策略路由被禁用，流量是否被接管需自行验证")
        }

        return RoutingReport(autoRoute, manualApplied, notes)
    }

    // A socket may already have an Android network mark or a bound Wi-Fi source address.
    // The kernel's src=0/TUN-prefix auto rules alone do not cover that case. Route app
    // UIDs first, without touching the root-owned outbound sockets or loopback routes.
    private fun appRanges(splitOwn: Boolean): List<IntRange> {
        val own = android.os.Process.myUid()
        val user = own / 100000 * 100000
        val all = (user + 10000)..(user + 99999)
        return if (splitOwn && own in all) listOf(all.first until own, (own + 1)..all.last).filterNot { it.isEmpty() } else listOf(all)
    }

    fun captureApplicationTraffic(device: String, table: Int, bypassOwn: Boolean) {
        require(table == MIHOMO_TABLE || table == MANUAL_TABLE)
        for (family in listOf("-4", "-6")) {
            val routes = root.exec("ip $family route show table $table", tag = TAG, echo = false, logStdout = false)
            val usable = routes.ok && routes.stdout.any { Regex("""^default\s+.*\bdev\s+${Regex.escape(device)}(?:\s|$)""").containsMatchIn(it.trim()) }
            check(family != "-4" || usable) { "缺少到 $device 的 IPv4 默认路由" }
            for (range in appRanges(bypassOwn)) {
                val selector = "iif lo uidrange ${range.first}-${range.last}"
                val action = if (usable) "lookup $table" else "unreachable"
                val result = root.exec("ip $family rule add pref 8940 $selector $action", tag = TAG)
                check(result.ok) { "无法为应用 UID 安装 $family 接管规则：${result.stderrText().take(160)}" }
            }
            if (!usable) LogBus.warn(TAG, "无 $family TUN 默认路由，阻止受管应用经原网络旁路，直到重启内核")
        }
    }

    private fun removeApplicationCapture() {
        val ranges = (appRanges(false) + appRanges(true)).distinct()
        val script = buildString {
            for (family in listOf("-4", "-6")) for (range in ranges) {
                for (action in listOf("lookup $MIHOMO_TABLE", "lookup $MANUAL_TABLE", "unreachable")) {
                    appendLine("ip $family rule del pref 8940 iif lo uidrange ${range.first}-${range.last} $action 2>/dev/null || :")
                }
            }
        }
        root.exec(script, tag = TAG, echo = false, logStdout = false)
    }

    /**
     * 让本应用自己的流量绕过 TUN。
     *
     * 原因：TUN 接管后，应用自身的订阅拉取请求也会进入 mihomo；如果当前订阅的节点不可用，
     * 应用就无法再拉取/更新订阅（典型的自我锁死）。这里用一条优先级高于 mihomo auto-route
     * 的 `ip rule ... uidrange <uid> lookup <default-iface>` 规则，让管理流量始终直连。
     */
    fun enableOwnTrafficBypass(uid: Int): Boolean {
        if (uid <= 0) return false
        removeOwnTrafficBypass()
        val route = root.exec(
            "ip -4 route show table all match 0.0.0.0/0 2>/dev/null",
            tag = TAG,
            echo = false,
            logStdout = false,
        )
        val iface = route.stdout.asSequence().filter { it.trimStart().startsWith("default ") }
            .mapNotNull { Regex("""\bdev\s+([a-zA-Z0-9_.-]+)\s+table\s+\1(?:\s|$)""").find(it)?.groupValues?.get(1) }
            .firstOrNull()
        if (iface.isNullOrBlank()) {
            LogBus.warn(TAG, "未找到默认路由接口，跳过「自身流量绕过 TUN」")
            return false
        }
        val add = root.exec(
            "ip rule add pref $BYPASS_PREF uidrange $uid-$uid lookup ${Workspace.shellQuote(iface)}",
            tag = TAG,
        )
        val rules = root.exec("ip rule show 2>/dev/null", tag = TAG, echo = false, logStdout = false)
        val applied = add.ok && rules.stdout.any { it.trimStart().startsWith("$BYPASS_PREF:") && it.contains("uidrange $uid-$uid") && it.contains("lookup $iface") }
        if (applied) {
            LogBus.info(TAG, "已为应用自身（uid=$uid）添加绕过 TUN 的策略路由：lookup $iface（pref $BYPASS_PREF）")
        } else {
            LogBus.warn(TAG, "「自身流量绕过 TUN」规则添加失败：${add.stderrText().take(200)}")
        }
        return applied
    }

    fun removeOwnTrafficBypass() {
        val uid = android.os.Process.myUid()
        root.exec("ip -4 rule del pref $BYPASS_PREF uidrange $uid-$uid 2>/dev/null", tag = TAG, echo = false, logStdout = false)
    }

    /**
     * 阻断**其他应用**访问本机回环上的代理端口（mixed-port / external-controller / DNS）。
     *
     * 背景：即使 TUN 接口被隐藏，目标应用仍可通过扫描 `127.0.0.1` 上的监听端口发现本地代理。
     * 这里用 iptables owner 匹配按 UID 放行 root（mihomo 自身）与本应用（控制器/状态查询），
     * 其余 UID 对这几个端口一律 REJECT；IPv4 与 IPv6 回环同时处理。
     */
    fun applyLocalProxyIsolation(ports: List<Int>): Boolean {
        removeLocalProxyIsolation()
        val uniquePorts = ports.filter { it > 0 }.distinct()
        if (uniquePorts.isEmpty()) return false

        var applied = false
        listOf("iptables" to "127.0.0.1", "ip6tables" to "::1").forEach { (tool, address) ->
            root.exec("$tool -t filter -N $LOCAL_CHAIN 2>/dev/null; true", tag = TAG, echo = false)
            root.exec("$tool -t filter -F $LOCAL_CHAIN 2>/dev/null; true", tag = TAG, echo = false)
            
            uniquePorts.forEach { port ->
                root.exec(
                    "$tool -t filter -A $LOCAL_CHAIN -p tcp -d $address --dport $port -j REJECT",
                    tag = TAG,
                    echo = false,
                    logStdout = false,
                )
                root.exec(
                    "$tool -t filter -A $LOCAL_CHAIN -p udp -d $address --dport $port -j REJECT",
                    tag = TAG,
                    echo = false,
                    logStdout = false,
                )
            }
            val insert = root.exec(
                "$tool -t filter -C OUTPUT -j $LOCAL_CHAIN 2>/dev/null || " +
                    "$tool -t filter -I OUTPUT 1 -j $LOCAL_CHAIN",
                tag = TAG,
                echo = false,
                logStdout = false,
            )
            val verify = root.exec(
                "$tool -t filter -S $LOCAL_CHAIN 2>/dev/null",
                tag = TAG,
                echo = false,
                logStdout = false,
            )
            val ok = verify.stdoutText().contains("REJECT")
            applied = applied || ok
            if (!ok) {
                LogBus.warn(
                    TAG,
                    "$tool 本机端口隔离未生效：${insert.stderrText().ifBlank { verify.stderrText() }.take(200)}",
                )
            }
        }

        if (applied) {
            LogBus.info(
                TAG,
                "已在本机回环上封堵代理端口 $uniquePorts（127.0.0.1 / ::1 一律 REJECT；内核本身不监听这些端口）",
            )
        }
        return applied
    }

    fun removeLocalProxyIsolation() {
        listOf("iptables", "ip6tables").forEach { tool ->
            repeat(3) {
                root.exec(
                    "$tool -t filter -D OUTPUT -j $LOCAL_CHAIN 2>/dev/null",
                    tag = TAG,
                    echo = false,
                    logStdout = false,
                )
            }
            root.exec("$tool -t filter -F $LOCAL_CHAIN 2>/dev/null; true", tag = TAG, echo = false)
            root.exec("$tool -t filter -X $LOCAL_CHAIN 2>/dev/null; true", tag = TAG, echo = false)
        }
    }

    /** 自动路由不能只检查 fwmark：残留 rule 但没有到当前 TUN 的默认路由也会黑洞。 */
    private fun hasTunDefault(table: Int, device: String): Boolean {
        val rows = root.exec("ip -4 route show table $table", tag = TAG, echo = false, logStdout = false)
        val route = Regex("""^default\s+.*\bdev\s+${Regex.escape(device)}(?:\s|$)""")
        return rows.ok && rows.stdout.any { route.containsMatchIn(it.trim()) }
    }

    /**
     * 回滚所有由本应用或 mihomo 留下的 iptables / 策略路由 / TUN 设备。
     * 每一步都允许失败（重复调用必须安全），但失败原因会写入日志。
     */
    fun cleanup(tunDevice: String, includeTunDevice: Boolean = true) {
        LogBus.info(TAG, "开始回滚 iptables / 策略路由（tun=$tunDevice）…")
        val safeTun = tunDevice.ifBlank { "net0" }

        removeApplicationCapture()
        removeLocalProxyIsolation()
        removeOwnTrafficBypass()

        // 1) 移除托管链在 OUTPUT 上的跳转，然后清空并删除托管链。
        root.exec("iptables -t mangle -D OUTPUT -j $CHAIN 2>/dev/null; true", tag = TAG, echo = false, logStdout = false)
        root.exec("iptables -t mangle -F $CHAIN 2>/dev/null; true", tag = TAG, echo = false, logStdout = false)
        root.exec("iptables -t mangle -X $CHAIN 2>/dev/null; true", tag = TAG, echo = false, logStdout = false)

        // 2) 删除本应用的手工策略路由（fwmark 0x0d15 → table 2023）。
        repeat(4) {
            root.exec(
                "ip rule del fwmark $MANUAL_MARK lookup $MANUAL_TABLE 2>/dev/null",
                tag = TAG,
                echo = false,
                logStdout = false,
            )
        }
        // Remove only our TUN default routes, including the IPv6 route on dual-stack devices.
        for (family in listOf("-4", "-6")) {
            root.exec("ip $family route del default dev ${Workspace.shellQuote(safeTun)} table $MANUAL_TABLE 2>/dev/null || :", tag = TAG, echo = false, logStdout = false)
        }

        // 3) 清理 mihomo auto-route 的残留规则（仅当内核异常退出时才会存在）。
        val rules = root.exec("ip rule show 2>/dev/null", tag = TAG, echo = false, logStdout = false)
        val stalePriorities = rules.stdout
            .filter { it.contains(MIHOMO_MARK, ignoreCase = true) }
            .mapNotNull { line -> line.substringBefore(':').trim().toIntOrNull() }
            .distinct()
            .take(16)
        stalePriorities.forEach { priority ->
            root.exec("ip rule del pref $priority 2>/dev/null", tag = TAG, echo = false, logStdout = false)
        }
        if (stalePriorities.isNotEmpty()) {
            LogBus.warn(TAG, "清理了 ${stalePriorities.size} 条 mihomo auto-route 残留规则：$stalePriorities")
        }
        root.exec("ip route flush table $MIHOMO_TABLE 2>/dev/null; true", tag = TAG, echo = false, logStdout = false)

        // 4) 删除残留的 TUN 设备（正常情况下内核退出时会自行删除，只有异常退出才需要这里兜底）。
        if (includeTunDevice) {
            removeTunDevice(safeTun)
        }

        LogBus.info(TAG, "iptables / 策略路由回滚完成")
    }

    /** 删除指定 TUN 设备（若仍存在）。 */
    fun removeTunDevice(device: String) {
        val safeTun = device.ifBlank { "mihomo0" }
        val linkCheck = root.exec(
            "ip link show ${Workspace.shellQuote(safeTun)} 2>/dev/null | head -n 1",
            tag = TAG,
            echo = false,
            logStdout = false,
        )
        if (linkCheck.ok && linkCheck.stdoutText().contains(safeTun)) {
            root.exec(
                "ip link delete ${Workspace.shellQuote(safeTun)} 2>/dev/null",
                tag = TAG,
                echo = false,
                logStdout = false,
            )
            LogBus.info(TAG, "已删除残留 TUN 设备 $safeTun")
        }
    }

    /** 判断某个文件是否位于应用私有目录（避免误删系统节点）。 */
    fun isInsideWorkspace(path: String): Boolean =
        runCatching { File(path).canonicalPath.startsWith(Workspace.baseDir.canonicalPath) }.getOrDefault(false)
}
