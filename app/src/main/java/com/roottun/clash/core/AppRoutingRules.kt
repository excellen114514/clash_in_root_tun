package com.roottun.clash.core

enum class AppRoute(val label: String) { FOLLOW("跟随订阅"), DIRECT("直连"), PROXY("代理") }

/** 应用分流工作模式：关闭时按单应用三态设置；白名单/黑名单按勾选集合生效。 */
enum class AppRoutingMode(val label: String) {
    OFF("关闭"),
    WHITELIST("白名单"),
    BLACKLIST("黑名单"),
}

data class AppRouteOwner(val packageName: String, val uid: Int, val route: AppRoute)

data class ResolvedAppRouting(
    val owners: List<AppRouteOwner> = emptyList(),
    val mode: AppRoutingMode = AppRoutingMode.OFF,
    val selected: List<AppRouteOwner> = emptyList(),
) {
    val enabled get() = mode != AppRoutingMode.OFF || owners.isNotEmpty()

    /** 只有白名单或关闭模式下的单应用「代理」才会固定走代理节点。 */
    val hasProxy get() = when (mode) {
        AppRoutingMode.OFF -> owners.any { it.route == AppRoute.PROXY }
        AppRoutingMode.WHITELIST -> selected.isNotEmpty()
        AppRoutingMode.BLACKLIST -> false
    }

    internal fun prefix(proxyTarget: String): List<String> = when (mode) {
        AppRoutingMode.OFF -> offPrefix(proxyTarget)
        AppRoutingMode.WHITELIST -> whitelistPrefix(proxyTarget)
        AppRoutingMode.BLACKLIST -> blacklistPrefix()
    }

    private fun offPrefix(proxyTarget: String): List<String> = buildList {
        require(owners.distinctBy { it.uid }.size == owners.size) { "不能为共享 UID 单独设置分流" }
        if (hasProxy) {
            // v1.19.34 的 UID,0 永远不匹配；限制在 TUN 内，不拦内核自己的 DNS 等 INNER 请求。
            add("AND,((IN-TYPE,TUN),(NOT,((UID,1-4294967295)))),REJECT")
        }
        for ((pkg, uid, route) in owners.sortedBy { it.uid }) {
            require(uid > 0 && PACKAGE.matches(pkg) && route != AppRoute.FOLLOW) { "应用分流身份无效" }
            when (route) {
                AppRoute.DIRECT -> add("UID,$uid,DIRECT")
                AppRoute.PROXY -> {
                    // UID 级规则覆盖该应用的 TCP 与 UDP；同一 UID 的后续规则只拒绝，不授权旧出口。
                    if (proxyTarget != "REJECT") add("UID,$uid,$proxyTarget")
                    add("UID,$uid,REJECT")
                }
                AppRoute.FOLLOW -> error("跟随订阅不应生成规则")
            }
        }
    }

    /**
     * 白名单：只有勾选的应用走代理节点；其余进入 TUN 的应用流量一律 DIRECT。
     * 勾选应用同样保留 UID 级 REJECT 兜底，避免其 UDP/未识别进程回落到直连。
     */
    private fun whitelistPrefix(proxyTarget: String): List<String> = buildList {
        require(selected.distinctBy { it.uid }.size == selected.size) { "不能为共享 UID 单独设置分流" }
        if (selected.isNotEmpty()) {
            add("AND,((IN-TYPE,TUN),(NOT,((UID,1-4294967295)))),REJECT")
        }
        for ((pkg, uid, _) in selected.sortedBy { it.uid }) {
            require(uid > 0 && PACKAGE.matches(pkg)) { "白名单应用身份无效" }
            if (proxyTarget != "REJECT") add("UID,$uid,$proxyTarget")
            add("UID,$uid,REJECT")
        }
        // mihomo 的 AND 规则至少需要两个条件，因此分别覆盖 TCP / UDP。
        add("AND,((IN-TYPE,TUN),(NETWORK,TCP)),DIRECT")
        add("AND,((IN-TYPE,TUN),(NETWORK,UDP)),DIRECT")
    }

    /** 黑名单：勾选应用直连；未勾选应用继续走订阅规则（通常即代理）。 */
    private fun blacklistPrefix(): List<String> = buildList {
        require(selected.distinctBy { it.uid }.size == selected.size) { "不能为共享 UID 单独设置分流" }
        for ((pkg, uid, _) in selected.sortedBy { it.uid }) {
            require(uid > 0 && PACKAGE.matches(pkg)) { "黑名单应用身份无效" }
            // UID 级直连同时覆盖 TCP/UDP；安装事件会停止旧会话，避免 UID 复用。
            add("UID,$uid,DIRECT")
        }
    }

    internal companion object {
        val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
        // 只使用可静态确认会连接远端的协议。不能从名字推断一组/一个 provider 最终是否为 DIRECT。
        val REMOTE_TYPES = setOf("ss", "ssr", "vmess", "vless", "trojan", "socks5", "http", "hysteria", "hysteria2", "tuic", "snell", "ssh", "anytls")
    }
}
