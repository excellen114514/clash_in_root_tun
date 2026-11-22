package com.roottun.clash.core

/**
 * 订阅内容的「结构可用性」检查。
 *
 * 真实案例：某机场按 User-Agent 返回不同订阅——被识别为旧版 Clash 时返回
 * `proxies: []` + `proxies: [/日本/]` 这种**依赖客户端自行展开正则**的模板，
 * mihomo 会报 `proxy group[0]: jp自动切换: '/日本/' not found`。
 * 这里在写缓存之前就识别出这类内容，触发更换 User-Agent 重试。
 */
object ConfigSanity {

    data class Report(
        val ok: Boolean,
        val nodeCount: Int,
        val providerCount: Int,
        val problems: List<String>,
    )

    private val REGEX_PROXY_FLOW = Regex("""proxies\s*:\s*\[[^\]]*/[^/\]]+/""")
    private val REGEX_PROXY_ITEM = Regex("""(?m)^\s*-\s*/\s*[^/\n]+\s*/\s*$""")

    fun check(yaml: String): Report {
        val nodeCount = ClashYaml.extractProxyNames(yaml).size
        val providerCount = if (ClashYaml.hasKey(yaml, "proxy-providers")) 1 else 0
        val problems = mutableListOf<String>()

        if (nodeCount == 0 && providerCount == 0) {
            problems += "订阅不包含任何节点（proxies 为空，且没有 proxy-providers）"
        }

        val groupsText = ClashYaml.sections(yaml)
            .firstOrNull { it.key == "proxy-groups" }
            ?.lines
            ?.joinToString("\n")
            .orEmpty()
        if (groupsText.isNotBlank()) {
            val flowHits = REGEX_PROXY_FLOW.findAll(groupsText).count()
            val itemHits = REGEX_PROXY_ITEM.findAll(groupsText).count()
            if (flowHits + itemHits > 0) {
                problems += "代理组使用了 mihomo 不支持的 /正则/ 代理写法（$flowHits+$itemHits 处），" +
                    "通常是机场按 User-Agent 返回的模板配置"
            }
        }

        return Report(problems.isEmpty(), nodeCount, providerCount, problems)
    }
}
