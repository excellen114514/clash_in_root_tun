package com.roottun.clash.core

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * 订阅仓库：增删改查 + 激活切换 + 拉取/解析 + 缓存。
 *
 * 缓存布局（全部在应用私有目录）：
 *   files/clash/subscriptions/<id>.raw   原始响应（排障用）
 *   files/clash/subscriptions/<id>.yaml  规范化后的 mihomo 配置
 */
class SubscriptionRepository(context: Context) {

    companion object {
        private const val TAG = "SUBSCRIPTION"
        private const val PREFS = "root_tun_clash_subscriptions"
        private const val KEY_LIST = "list"
        private const val KEY_ACTIVE = "activeId"
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val fetcher = SubscriptionFetcher()

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    @Volatile
    private var activeId: String? = null

    init {
        Workspace.ensureDirs()
        load()
    }

    // ------------------------------------------------------------- queries

    fun activeSubscription(): Subscription? =
        _subscriptions.value.firstOrNull { it.active }
            ?: activeId?.let { id -> _subscriptions.value.firstOrNull { it.id == id } }

    fun cachedConfig(subscription: Subscription): String? {
        val file = cacheFile(subscription)
        return if (file.exists() && file.length() > 0) file.readText() else null
    }

    fun cacheFile(subscription: Subscription): File = Workspace.subscriptionFile(subscription.id)

    fun rawFile(subscription: Subscription): File = File(Workspace.subscriptionDir, "${subscription.id}.raw")

    // ------------------------------------------------------------- mutations

    fun addUrlSubscription(name: String, url: String): Subscription {
        val trimmedUrl = url.trim()
        val existing = _subscriptions.value.firstOrNull {
            it.source == SubscriptionSource.URL && it.url.equals(trimmedUrl, ignoreCase = true)
        }
        if (existing != null) {
            LogBus.warn(TAG, "订阅已存在（「${existing.name}」），改为刷新而不是重复添加")
            return existing
        }
        val displayName = name.trim().ifBlank { defaultNameFor(trimmedUrl) }
        val subscription = Subscription(
            id = newId(),
            name = displayName,
            source = SubscriptionSource.URL,
            url = trimmedUrl,
        )
        commit(_subscriptions.value + subscription, activeId ?: subscription.id)
        LogBus.info(TAG, "已添加 URL 订阅「${subscription.name}」→ $trimmedUrl")
        return subscription
    }

    private fun defaultNameFor(url: String): String = runCatching {
        val uri = java.net.URI(url)
        // A subscription URL's final path segment is frequently a bearer token.
        uri.host.orEmpty()
    }.getOrNull().orEmpty().ifBlank { "远程订阅" }

    fun addLocalYaml(displayName: String, content: String): Result<Subscription> = try {
        val (normalized, nodeCount) = normalize(content)
        val subscription = Subscription(
            id = newId(),
            name = displayName.trim().ifBlank { "本地订阅" },
            source = SubscriptionSource.FILE,
            fileName = displayName,
            nodeCount = nodeCount,
            lastFetchedAt = System.currentTimeMillis(),
        )
        rawFile(subscription).writeText(content)
        cacheFile(subscription).writeText(normalized)
        commit(_subscriptions.value + subscription, activeId ?: subscription.id)
        LogBus.info(TAG, "已导入本地 YAML「${subscription.name}」：$nodeCount 个节点")
        Result.success(subscription)
    } catch (t: Throwable) {
        LogBus.error(TAG, "导入本地 YAML 失败：${t.message ?: t.javaClass.simpleName}")
        Result.failure(t)
    }

    fun remove(id: String) {
        val target = _subscriptions.value.firstOrNull { it.id == id } ?: return
        runCatching { rawFile(target).delete() }
        runCatching { cacheFile(target).delete() }
        val remaining = _subscriptions.value.filterNot { it.id == id }
        val nextActive = if (target.active) remaining.firstOrNull()?.id else activeId
        commit(remaining, nextActive)
        LogBus.info(TAG, "已删除订阅「${target.name}」")
    }

    fun setEnabled(id: String, enabled: Boolean) {
        update(id) { it.copy(enabled = enabled) }
        val name = _subscriptions.value.firstOrNull { it.id == id }?.name ?: id
        LogBus.info(TAG, "订阅「$name」已${if (enabled) "启用" else "禁用"}")
    }

    fun setActive(id: String) {
        if (_subscriptions.value.none { it.id == id }) return
        commit(_subscriptions.value, id)
        val name = _subscriptions.value.firstOrNull { it.id == id }?.name ?: id
        LogBus.info(TAG, "活动订阅已切换为「$name」")
    }

    // ------------------------------------------------------------- refresh

    /**
     * 拉取并解析订阅。
     *
     * 机场经常按 User-Agent 返回不同内容，因此这里会依次尝试【自定义 UA → CMFA → clash.meta →
     * Clash Verge】，并对每份内容做结构检查（[ConfigSanity]）：只有「有节点 / 有 provider /
     * 代理组不含 mihomo 不支持的 /正则/ 写法」的内容才会被接受并写入缓存。
     */
    suspend fun refresh(id: String): Result<Int> = withContext(Dispatchers.IO) {
        val subscription = _subscriptions.value.firstOrNull { it.id == id }
            ?: return@withContext Result.failure(IllegalStateException("订阅不存在：$id"))

        val candidates: List<String?> = if (subscription.source == SubscriptionSource.URL) {
            buildUserAgents()
        } else {
            listOf(null)
        }
        val attempts = mutableListOf<String>()

        for (userAgent in candidates) {
            val label = userAgent ?: "本地文件"
            try {
                val body = when (subscription.source) {
                    SubscriptionSource.URL -> {
                        LogBus.info(TAG, "拉取订阅「${subscription.name}」：${subscription.url}（UA=$label）")
                        fetcher.fetch(subscription.url, userAgent!!).body
                    }
                    SubscriptionSource.FILE -> {
                        val raw = rawFile(subscription)
                        if (!raw.exists()) {
                            throw IllegalStateException("本地订阅原始内容已丢失：${raw.absolutePath}")
                        }
                        raw.readText()
                    }
                }

                val (normalized, nodeCount) = normalize(body)
                val sanity = ConfigSanity.check(normalized)
                if (!sanity.ok) {
                    val problem = sanity.problems.joinToString("；")
                    attempts += "$label：内容不可用（$problem）"
                    LogBus.warn(TAG, "订阅内容不可用（UA=$label）：$problem，尝试下一个 UA")
                    continue
                }

                rawFile(subscription).writeText(body)
                cacheFile(subscription).writeText(normalized)
                update(id) {
                    it.copy(
                        nodeCount = nodeCount,
                        lastFetchedAt = System.currentTimeMillis(),
                        lastError = null,
                    )
                }
                LogBus.info(
                    TAG,
                    "订阅「${subscription.name}」更新成功：$nodeCount 个节点" +
                        if (userAgent != null) "（UA=$userAgent）" else "",
                )
                return@withContext Result.success(nodeCount)
            } catch (t: Throwable) {
                val reason = t.message ?: t.javaClass.simpleName
                attempts += "$label：$reason"
                LogBus.warn(TAG, "订阅「${subscription.name}」在 UA=$label 下失败：$reason")
            }
        }

        val reason = "已尝试 ${candidates.size} 种方式均失败：\n" +
            attempts.joinToString("\n") { "· $it" }
        update(id) { it.copy(lastError = reason) }
        LogBus.error(TAG, "订阅「${subscription.name}」更新失败。$reason")
        Result.failure(SubscriptionParseException(reason))
    }

    private fun buildUserAgents(): List<String> {
        val custom = AppSettings.state.value.subscriptionUserAgent.trim()
        val result = mutableListOf<String>()
        if (custom.isNotEmpty()) result.add(custom)
        SubscriptionFetcher.CANDIDATE_USER_AGENTS.forEach { ua ->
            if (!result.contains(ua)) result.add(ua)
        }
        return result
    }

    // -------------------------------------------------------------- helpers

    private fun normalize(body: String): Pair<String, Int> {
        val settings = AppSettings.state.value
        val options = RuntimeOptions(
            mixedPort = settings.mixedPort,
            controllerPort = settings.controllerPort,
            dnsPort = settings.dnsPort,
            tunDevice = settings.tunDevice,
            tunStack = settings.tunStack,
            tunMtu = settings.tunMtu,
            strictRoute = settings.strictRoute,
            controllerSocketPath = Workspace.controllerSocket.absolutePath,
        )
        return when (val parsed = SubscriptionParser.parse(body)) {
            is ParsedSubscription.ClashYaml -> parsed.yaml.trim() + "\n" to parsed.nodeCount
            is ParsedSubscription.Links -> {
                val geoip = File(Workspace.homeDir, "geoip.metadb").exists()
                val composition = ConfigComposer.composeFromLinks(parsed.report, options, geoip)
                composition.notes.forEach { LogBus.debug(TAG, it) }
                composition.yaml to composition.nodeCount
            }
        }
    }

    private fun update(id: String, transform: (Subscription) -> Subscription) {
        val next = _subscriptions.value.map { if (it.id == id) transform(it) else it }
        commit(next, activeId)
    }

    private fun commit(list: List<Subscription>, requestedActiveId: String?) {
        val effectiveActive = requestedActiveId?.takeIf { id -> list.any { it.id == id } }
            ?: list.firstOrNull()?.id
        activeId = effectiveActive
        val flagged = list.map { it.copy(active = it.id == effectiveActive) }
        _subscriptions.value = flagged

        val array = JSONArray()
        flagged.forEach { array.put(it.copy(active = false).toJson()) }
        prefs.edit()
            .putString(KEY_LIST, array.toString())
            .putString(KEY_ACTIVE, effectiveActive)
            .apply()
    }

    private fun load() {
        val raw = prefs.getString(KEY_LIST, null)
        val storedActive = prefs.getString(KEY_ACTIVE, null)
        if (raw.isNullOrBlank()) {
            commit(emptyList(), null)
            return
        }
        val list = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                runCatching { Subscription.fromJson(array.getJSONObject(index)) }.getOrNull()
            }
        }.getOrElse { error ->
            LogBus.error(TAG, "读取订阅列表失败（将重置）：${error.message}")
            emptyList()
        }
        commit(list, storedActive)
        LogBus.info(TAG, "已载入 ${list.size} 个订阅，活动订阅=${activeSubscription()?.name ?: "无"}")
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(10)
}
