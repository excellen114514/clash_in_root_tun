package com.roottun.clash.core

import org.yaml.snakeyaml.DumperOptions.FlowStyle
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag

/** 用 YAML 节点的源位置编辑原文，不重新序列化订阅凭据或规则。 */
object ClashYaml {
    private val KEY = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")
    private val MARKERS = setOf("proxies", "proxy-groups", "proxy-providers", "rules", "rule-providers", "dns", "tun", "port", "mixed-port")
    val MANAGED_KEYS = setOf(
        "mixed-port", "port", "socks-port", "redir-port", "tproxy-port", "listeners", "tunnels", "ss-config", "vmess-config", "lan-allowed-ips", "lan-disallowed-ips",
        "external-controller", "external-ui", "secret", "allow-lan", "mode", "log-level",
        "external-controller-unix", "external-controller-pipe", "external-controller-tls",
        "ipv6", "tun", "dns", "profile", "unified-delay", "tcp-concurrent",
        "geodata-mode", "geodata-loader", "geo-auto-update", "geo-auto-update-interval",
        "find-process-mode", "routing-mark", "interface-name", "bind-address",
        "global-client-fingerprint", "keep-alive-interval", "keep-alive-idle",
        "disable-keep-alive", "udp", "hosts", "use-hosts", "use-system-hosts",
    )

    data class Section(val key: String?, val startLine: Int, val lines: List<String>)
    internal data class Entry(val name: String, val type: String?)

    internal class Document(val fields: Map<String, Node>, val sections: List<Section>) {
        private fun sequence(key: String): SequenceNode? = fields[key]?.let {
            it as? SequenceNode ?: error("订阅的 $key 必须是 YAML 列表；拒绝覆盖原有内容")
        }

        fun entries(key: String): List<Entry> = sequence(key)?.value.orEmpty().map { item ->
            val map = item as? MappingNode ?: error("订阅的 $key 列表项必须是映射")
            val values = map.value.associate { (it.keyNode as ScalarNode).value to it.valueNode }
            val name = values["name"] as? ScalarNode ?: error("订阅的 $key 列表项缺少 name")
            require(name.value.isNotBlank() && name.value.none(Char::isISOControl)) { "订阅节点或组名称为空或含控制字符" }
            Entry(name.value, (values["type"] as? ScalarNode)?.value)
        }

        fun body(removed: Set<String>, prefix: List<String> = emptyList()): String {
            val rules = if (prefix.isEmpty()) null else sequence("rules")
            if (rules != null) require(rules.value.all { it is ScalarNode && it.tag == Tag.STR }) {
                "订阅的 rules 含非字符串规则，无法保留原语义"
            }
            // 内置自检配置也可能由启动流程再次组装；已经相同的前缀无需插入两次。
            val insert = rules != null && rules.value.take(prefix.size).map { (it as ScalarNode).value } != prefix
            return buildString {
                sections.filterNot { it.key in removed }.forEach { section ->
                    section.lines.forEachIndexed { i, line ->
                        if (insert && rules != null && section.key == "rules" && section.startLine + i == rules.startMark.line) {
                            val col = line.offsetByCodePoints(0, rules.startMark.column)
                            if (rules.flowStyle == FlowStyle.FLOW) {
                                require(line.getOrNull(col) == '[') { "无法定位 rules 的行内列表，未修改订阅" }
                                append(line.take(col + 1))
                                append(prefix.joinToString(", ") { YamlWrite.quote(it) })
                                if (rules.value.isNotEmpty()) append(", ")
                                appendLine(line.drop(col + 1))
                            } else {
                                require(line.take(col).isBlank() && line.getOrNull(col) == '-') { "无法定位 rules 的首条规则，未修改订阅" }
                                prefix.forEach { appendLine("${line.take(col)}- ${YamlWrite.quote(it)}") }
                                appendLine(line)
                            }
                        } else appendLine(line)
                    }
                }
            }
        }
    }

    internal fun parse(text: String): Document {
        val source = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = source.lines()
        require(lines.none { it.startsWith('%') || it.matches(Regex("(?:---|\\.\\.\\.)(?:\\s.*)?")) }) {
            "暂不支持 YAML 指令或文档分隔符；请使用单个块映射文档"
        }
        // compose 仅读取节点，不构造任何由订阅指定的 Java 类型。
        val root = try {
            Yaml(LoaderOptions().apply { maxAliasesForCollections = 0; nestingDepthLimit = 48 }).compose(source.reader())
        } catch (e: YAMLException) {
            val line = (e as? MarkedYAMLException)?.problemMark?.line?.plus(1)
            error("订阅 YAML 语法或复杂度不受支持${line?.let { "（第 $it 行）" }.orEmpty()}；未应用分流")
        }
        require(root is MappingNode && root.flowStyle == FlowStyle.BLOCK) { "订阅顶层必须是单个块映射，不能安全编辑此写法" }
        fun validate(node: Node) {
            require(node.anchor == null && node.tag != Tag.MERGE) { "暂不支持 YAML 锚点、别名或合并键；请先在本地展开" }
            when (node) {
                is MappingNode -> {
                    val seen = mutableSetOf<String>()
                    node.value.forEach { pair ->
                        val key = pair.keyNode as? ScalarNode ?: error("不支持复合 YAML 键")
                        require(seen.add(key.value)) { "订阅 YAML 第 ${key.startMark.line + 1} 行有重复键" }
                        validate(key); validate(pair.valueNode)
                    }
                }
                is SequenceNode -> node.value.forEach(::validate)
            }
        }
        validate(root)
        val starts = mutableListOf<Pair<String?, Int>>()
        val fields = linkedMapOf<String, Node>()
        root.value.forEach { pair ->
            val key = pair.keyNode as ScalarNode
            val row = key.startMark.line
            require(key.startMark.column == 0 && KEY.matches(key.value) &&
                (lines[row].startsWith("${key.value}:") || lines[row].startsWith("\"${key.value}\":") || lines[row].startsWith("'${key.value}':"))) {
                "第 ${row + 1} 行使用不支持的顶层键写法，无法安全编辑订阅"
            }
            if (starts.isEmpty() && row > 0) starts += null to 0
            starts += key.value to row
            fields[key.value] = pair.valueNode
        }
        val sections = starts.mapIndexed { i, (key, row) -> Section(key, row, lines.subList(row, starts.getOrNull(i + 1)?.second ?: lines.size)) }
        return Document(fields, sections)
    }

    // 格式探测也用于非 YAML 的分享链接，不能在这里把普通链接当成解析错误。
    fun topLevelKeys(text: String): List<String> = text.lineSequence().mapNotNull { line ->
        line.substringBefore(':', "").removeSurrounding("\"").removeSurrounding("'").takeIf(KEY::matches)
    }.toList()
    fun isClashConfig(text: String): Boolean = topLevelKeys(text).any { it in MARKERS }
    fun hasKey(text: String, key: String): Boolean = key in topLevelKeys(text)
    fun extractProxyNames(text: String): List<String> = parse(text).entries("proxies").map { it.name }
    fun sections(text: String): List<Section> = parse(text).sections
    fun stripKeys(text: String, removed: Set<String>): String = parse(text).body(removed)
}
