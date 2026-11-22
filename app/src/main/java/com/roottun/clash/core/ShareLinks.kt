package com.roottun.clash.core

import android.net.Uri
import android.util.Base64
import org.json.JSONObject

/**
 * 分享链接（ss / vmess / vless / trojan / hysteria2 / tuic）解析器。
 *
 * 只负责把链接翻译成 mihomo 的 proxies 字段（Map），YAML 序列化由 [YamlWrite] 完成，
 * 因此新增/出错时都能给出可读的诊断信息，而不是整段订阅直接崩溃。
 */
object ShareLinks {

    data class Node(val name: String, val fields: LinkedHashMap<String, Any?>)

    data class ParseReport(
        val nodes: List<Node>,
        val warnings: List<String>,
        val skipped: Int,
    ) {
        val nodeCount: Int get() = nodes.size
    }

    private val SUPPORTED_PREFIXES = listOf(
        "ss://", "vmess://", "vless://", "trojan://",
        "hysteria2://", "hy2://", "tuic://", "ssr://",
    )

    fun isShareLink(line: String): Boolean {
        val trimmed = line.trim()
        return SUPPORTED_PREFIXES.any { trimmed.startsWith(it, ignoreCase = true) }
    }

    fun containsLinks(text: String): Boolean = text.lineSequence().any { isShareLink(it) }

    fun countLinks(text: String): Int = text.lineSequence().count { isShareLink(it) }

    fun parse(text: String): ParseReport {
        val nodes = mutableListOf<Node>()
        val warnings = mutableListOf<String>()
        val usedNames = mutableSetOf<String>()
        var skipped = 0

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val link = line.substringBefore(' ').trim()
            if (!isShareLink(link)) return@forEach
            val node = try {
                parseLink(link)
            } catch (t: Throwable) {
                warnings += "${link.substringBefore("://")} 节点解析失败（${t.javaClass.simpleName}），已跳过"
                null
            }
            if (node == null) {
                skipped++
                return@forEach
            }
            val uniqueName = uniqueName(node.name, usedNames)
            usedNames.add(uniqueName)
            nodes.add(node.copy(name = uniqueName))
        }
        return ParseReport(nodes, warnings, skipped)
    }

    private fun parseLink(link: String): Node? = when {
        link.startsWith("ss://", ignoreCase = true) -> parseSs(link)
        link.startsWith("vmess://", ignoreCase = true) -> parseVmess(link)
        link.startsWith("vless://", ignoreCase = true) -> parseVless(link)
        link.startsWith("trojan://", ignoreCase = true) -> parseTrojan(link)
        link.startsWith("hysteria2://", ignoreCase = true) || link.startsWith("hy2://", ignoreCase = true) ->
            parseHysteria2(link)
        link.startsWith("tuic://", ignoreCase = true) -> parseTuic(link)
        link.startsWith("ssr://", ignoreCase = true) -> null // mihomo 不支持 SSR，静默跳过并记录告警
        else -> null
    }

    // ---------------------------------------------------------------- ss://

    private fun parseSs(link: String): Node? {
        val raw = link.substringAfter("ss://")
        val (bodyRaw, fragment) = splitFragment(raw)
        var body = bodyRaw
        var query = ""
        val queryIndex = body.indexOf('?')
        if (queryIndex >= 0) {
            query = body.substring(queryIndex + 1)
            body = body.substring(0, queryIndex)
        }
        body = body.trimEnd('/')

        val at = body.lastIndexOf('@')
        val method: String
        val password: String
        val hostPort: String
        if (at >= 0) {
            val userInfoRaw = body.substring(0, at)
            hostPort = body.substring(at + 1)
            val userInfo = if (userInfoRaw.contains(':')) userInfoRaw else decodeBase64(userInfoRaw) ?: userInfoRaw
            val colon = userInfo.indexOf(':')
            if (colon <= 0) return null
            method = userInfo.substring(0, colon)
            password = userInfo.substring(colon + 1)
        } else {
            val decoded = decodeBase64(body) ?: return null
            val decodedAt = decoded.lastIndexOf('@')
            if (decodedAt <= 0) return null
            val userInfo = decoded.substring(0, decodedAt)
            hostPort = decoded.substring(decodedAt + 1)
            val colon = userInfo.indexOf(':')
            if (colon <= 0) return null
            method = userInfo.substring(0, colon)
            password = userInfo.substring(colon + 1)
        }

        val (host, port) = parseHostPort(hostPort) ?: return null
        val fields = linkedMapOf<String, Any?>(
            "name" to "",
            "type" to "ss",
            "server" to host,
            "port" to port,
            "cipher" to method,
            "password" to password,
            "udp" to true,
        )

        val plugin = queryParameter(query, "plugin")
        if (!plugin.isNullOrBlank()) {
            applySsPlugin(plugin, fields)
        }

        val name = decodeName(fragment).ifBlank { "ss-$host-$port" }
        fields["name"] = name
        return Node(name, fields)
    }

    private fun applySsPlugin(pluginParameter: String, fields: LinkedHashMap<String, Any?>) {
        val parts = pluginParameter.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        val pluginName = parts.first()
        val options = parts.drop(1).mapNotNull { item ->
            val idx = item.indexOf('=')
            if (idx <= 0) null else item.substring(0, idx) to item.substring(idx + 1)
        }.toMap()

        when {
            pluginName.contains("obfs") -> {
                fields["plugin"] = "obfs"
                val opts = linkedMapOf<String, Any?>(
                    "mode" to (options["obfs"] ?: "http"),
                )
                options["obfs-host"]?.let { opts["host"] = it }
                fields["plugin-opts"] = opts
            }
            pluginName.contains("v2ray") -> {
                fields["plugin"] = "v2ray-plugin"
                val opts = linkedMapOf<String, Any?>(
                    "mode" to (options["mode"] ?: "websocket"),
                    "tls" to (options.containsKey("tls") || options["tls"] == "true"),
                )
                options["host"]?.let { opts["host"] = it }
                options["path"]?.let { opts["path"] = it }
                fields["plugin-opts"] = opts
            }
            else -> {
                fields["plugin"] = pluginName
            }
        }
    }

    // -------------------------------------------------------------- vmess://

    private fun parseVmess(link: String): Node? {
        val payload = link.substringAfter("vmess://").substringBefore('#').trim().trimEnd('/')
        val json = decodeBase64(payload) ?: return null
        val obj = JSONObject(json)
        val host = obj.optString("add").ifBlank { return null }
        val port = obj.optString("port").toIntOrNull() ?: return null
        val uuid = obj.optString("id").ifBlank { return null }
        val ps = obj.optString("ps").ifBlank { "vmess-$host-$port" }

        val network = obj.optString("net", "tcp").ifBlank { "tcp" }
        val tls = obj.optString("tls", "").equals("tls", ignoreCase = true)
        val sni = obj.optString("sni").ifBlank { obj.optString("host") }

        val fields = linkedMapOf<String, Any?>(
            "name" to ps,
            "type" to "vmess",
            "server" to host,
            "port" to port,
            "uuid" to uuid,
            "alterId" to (obj.optString("aid", "0").toIntOrNull() ?: 0),
            "cipher" to obj.optString("scy", "auto").ifBlank { "auto" },
            "udp" to true,
        )
        applyTransport(fields, network, obj.optString("type"), obj.optString("host"), obj.optString("path"))
        if (tls) {
            fields["tls"] = true
            fields.putIfAbsent("client-fingerprint", "chrome")
            if (sni.isNotBlank()) fields["servername"] = sni
            obj.optString("alpn").takeIf { it.isNotBlank() }?.let { alpn ->
                fields["alpn"] = alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }
            obj.optString("fp").takeIf { it.isNotBlank() }?.let { fields["client-fingerprint"] = it }
        }
        return Node(ps, fields)
    }

    // ------------------------------------------------------------- vless://

    private fun parseVless(link: String): Node? {
        val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return null
        val uuid = uri.userInfo?.decodeUrl()?.ifBlank { null } ?: return null
        val host = uri.host ?: return null
        val port = uri.port
        if (port <= 0) return null
        val params = queryParameters(uri)
        val security = params["security"].orEmpty()
        val network = params["type"].orEmpty().ifBlank { "tcp" }
        val name = decodeName(uri.fragment).ifBlank { "vless-$host-$port" }

        val fields = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to "vless",
            "server" to host,
            "port" to port,
            "uuid" to uuid,
            "udp" to true,
        )
        if (security.equals("tls", true) || security.equals("reality", true)) {
            fields["tls"] = true
            fields.putIfAbsent("client-fingerprint", "chrome")
            val sni = params["sni"] ?: params["host"]
            if (!sni.isNullOrBlank()) fields["servername"] = sni
            params["alpn"]?.takeIf { it.isNotBlank() }?.let {
                fields["alpn"] = it.split(',').map { item -> item.trim() }.filter { item -> item.isNotEmpty() }
            }
            params["fp"]?.takeIf { it.isNotBlank() }?.let { fields["client-fingerprint"] = it }
            params["allowInsecure"]?.let { if (it == "1" || it == "true") fields["skip-cert-verify"] = true }
            if (security.equals("reality", true)) {
                val reality = linkedMapOf<String, Any?>()
                params["pbk"]?.let { reality["public-key"] = it }
                params["sid"]?.let { reality["short-id"] = it }
                if (reality.isNotEmpty()) fields["reality-opts"] = reality
            }
        }
        params["flow"]?.takeIf { it.isNotBlank() }?.let { fields["flow"] = it }
        applyTransport(fields, network, params["headerType"], params["host"], params["path"], params["serviceName"])
        return Node(name, fields)
    }

    // ------------------------------------------------------------ trojan://

    private fun parseTrojan(link: String): Node? {
        val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return null
        val password = uri.userInfo?.decodeUrl()?.ifBlank { null } ?: return null
        val host = uri.host ?: return null
        val port = uri.port
        if (port <= 0) return null
        val params = queryParameters(uri)
        val name = decodeName(uri.fragment).ifBlank { "trojan-$host-$port" }

        val fields = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to "trojan",
            "server" to host,
            "port" to port,
            "password" to password,
            "udp" to true,
        )
        val security = params["security"].orEmpty()
        if (!security.equals("none", true)) {
            fields.putIfAbsent("client-fingerprint", "chrome")
            fields["sni"] = params["sni"] ?: params["peer"] ?: host
            if (params["allowInsecure"] == "1" || params["insecure"] == "1") {
                fields["skip-cert-verify"] = true
            }
            params["alpn"]?.takeIf { it.isNotBlank() }?.let {
                fields["alpn"] = it.split(',').map { item -> item.trim() }.filter { item -> item.isNotEmpty() }
            }
        }
        applyTransport(
            fields,
            params["type"].orEmpty().ifBlank { "tcp" },
            params["headerType"],
            params["host"],
            params["path"],
            params["serviceName"],
        )
        return Node(name, fields)
    }

    // --------------------------------------------------------- hysteria2://

    private fun parseHysteria2(link: String): Node? {
        val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val port = uri.port
        if (port <= 0) return null
        val params = queryParameters(uri)
        val auth = uri.userInfo?.decodeUrl()?.substringAfter(':')?.ifBlank { null }
            ?: uri.userInfo?.decodeUrl()?.ifBlank { null }
        val name = decodeName(uri.fragment).ifBlank { "hy2-$host-$port" }

        val fields = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to "hysteria2",
            "server" to host,
            "port" to port,
        )
        auth?.let { fields["password"] = it }
        params["sni"]?.takeIf { it.isNotBlank() }?.let { fields["sni"] = it }
        if (params["insecure"] == "1" || params["allowInsecure"] == "1") {
            fields["skip-cert-verify"] = true
        }
        params["obfs"]?.takeIf { it.isNotBlank() }?.let { fields["obfs"] = it }
        params["obfs-password"]?.takeIf { it.isNotBlank() }?.let { fields["obfs-password"] = it }
        params["alpn"]?.takeIf { it.isNotBlank() }?.let {
            fields["alpn"] = it.split(',').map { item -> item.trim() }.filter { item -> item.isNotEmpty() }
        }
        return Node(name, fields)
    }

    // --------------------------------------------------------------- tuic://

    private fun parseTuic(link: String): Node? {
        val uri = runCatching { Uri.parse(link) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val port = uri.port
        if (port <= 0) return null
        val userInfo = uri.userInfo?.decodeUrl().orEmpty()
        val uuid = userInfo.substringBefore(':').ifBlank { return null }
        val password = userInfo.substringAfter(':', "")
        val params = queryParameters(uri)
        val name = decodeName(uri.fragment).ifBlank { "tuic-$host-$port" }

        val fields = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to "tuic",
            "server" to host,
            "port" to port,
            "uuid" to uuid,
        )
        if (password.isNotBlank()) fields["password"] = password
        params["sni"]?.takeIf { it.isNotBlank() }?.let { fields["sni"] = it }
        params["congestion_control"]?.takeIf { it.isNotBlank() }?.let { fields["congestion-controller"] = it }
        params["udp_relay_mode"]?.takeIf { it.isNotBlank() }?.let { fields["udp-relay-mode"] = it }
        if (params["allow_insecure"] == "1" || params["insecure"] == "1") {
            fields["skip-cert-verify"] = true
        }
        params["alpn"]?.takeIf { it.isNotBlank() }?.let {
            fields["alpn"] = it.split(',').map { item -> item.trim() }.filter { item -> item.isNotEmpty() }
        }
        return Node(name, fields)
    }

    // ------------------------------------------------------------ utilities

    private fun applyTransport(
        fields: LinkedHashMap<String, Any?>,
        network: String,
        headerType: String?,
        host: String?,
        path: String?,
        serviceName: String? = null,
    ) {
        when (network.lowercase()) {
            "", "tcp" -> {
                if (headerType.equals("http", true)) {
                    fields["network"] = "http"
                    val httpOpts = linkedMapOf<String, Any?>()
                    if (!path.isNullOrBlank()) httpOpts["path"] = path
                    if (!host.isNullOrBlank()) httpOpts["headers"] = linkedMapOf("Host" to host)
                    if (httpOpts.isNotEmpty()) fields["http-opts"] = httpOpts
                }
            }
            "ws" -> {
                fields["network"] = "ws"
                val wsOpts = linkedMapOf<String, Any?>()
                if (!path.isNullOrBlank()) wsOpts["path"] = path
                if (!host.isNullOrBlank()) wsOpts["headers"] = linkedMapOf("Host" to host)
                if (wsOpts.isNotEmpty()) fields["ws-opts"] = wsOpts
            }
            "grpc" -> {
                fields["network"] = "grpc"
                val service = serviceName?.takeIf { it.isNotBlank() } ?: path
                if (!service.isNullOrBlank()) {
                    fields["grpc-opts"] = linkedMapOf("grpc-service-name" to service)
                }
            }
            "h2", "http" -> {
                fields["network"] = "h2"
                val h2Opts = linkedMapOf<String, Any?>()
                if (!path.isNullOrBlank()) h2Opts["path"] = path
                if (!host.isNullOrBlank()) h2Opts["host"] = listOf(host)
                if (h2Opts.isNotEmpty()) fields["h2-opts"] = h2Opts
            }
            else -> fields["network"] = network
        }
    }

    private fun splitFragment(raw: String): Pair<String, String> {
        val index = raw.lastIndexOf('#')
        return if (index >= 0) raw.substring(0, index) to raw.substring(index + 1) else raw to ""
    }

    private fun decodeName(fragment: String?): String =
        fragment?.decodeUrl()?.trim()?.take(64).orEmpty()

    private fun String.decodeUrl(): String = runCatching { Uri.decode(this) }.getOrDefault(this)

    private fun parseHostPort(input: String): Pair<String, Int>? {
        val value = input.trim().trimEnd('/')
        if (value.isEmpty()) return null
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end <= 0) return null
            val host = value.substring(1, end)
            val rest = value.substring(end + 1).removePrefix(":")
            val port = rest.toIntOrNull() ?: return null
            return host to port
        }
        val index = value.lastIndexOf(':')
        if (index <= 0) return null
        val host = value.substring(0, index)
        val port = value.substring(index + 1).toIntOrNull() ?: return null
        return host to port
    }

    private fun queryParameters(uri: Uri): Map<String, String> {
        val names = runCatching { uri.queryParameterNames }.getOrNull() ?: return emptyMap()
        return names.associateWith { name -> runCatching { uri.getQueryParameter(name) }.getOrNull().orEmpty() }
    }

    private fun queryParameter(query: String, key: String): String? {
        if (query.isBlank()) return null
        val uri = runCatching { Uri.parse("ss://placeholder/?$query") }.getOrNull() ?: return null
        return runCatching { uri.getQueryParameter(key) }.getOrNull()
    }

    fun decodeBase64(input: String): String? {
        val cleaned = input.trim()
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
            .trimEnd('=')
        if (cleaned.isEmpty()) return null
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        val attempts = listOf(
            padded to (Base64.DEFAULT or Base64.NO_WRAP),
            padded to (Base64.URL_SAFE or Base64.NO_WRAP),
        )
        attempts.forEach { (data, flags) ->
            val text = runCatching { String(Base64.decode(data, flags), Charsets.UTF_8) }.getOrNull()
            if (!text.isNullOrBlank() && !text.contains('\uFFFD')) return text
        }
        return null
    }

    private fun uniqueName(base: String, used: MutableSet<String>): String {
        val clean = base.trim().ifBlank { "node" }.take(64)
        if (used.add(clean)) return clean
        var index = 2
        while (true) {
            val candidate = "$clean #$index"
            if (used.add(candidate)) return candidate
            index++
        }
    }
}
