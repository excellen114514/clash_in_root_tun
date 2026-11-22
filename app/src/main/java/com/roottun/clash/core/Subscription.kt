package com.roottun.clash.core

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SubscriptionSource { URL, FILE }

data class Subscription(
    val id: String,
    val name: String,
    val source: SubscriptionSource,
    val url: String = "",
    val fileName: String = "",
    val enabled: Boolean = true,
    val active: Boolean = false,
    val nodeCount: Int = 0,
    val lastFetchedAt: Long = 0L,
    val lastError: String? = null,
) {
    val sourceLabel: String
        get() = if (source == SubscriptionSource.URL) "远程 URL" else "本地 YAML"

    val fetchedAtText: String
        get() = if (lastFetchedAt <= 0L) "尚未拉取"
        else TIME_FORMAT.format(Date(lastFetchedAt))

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("source", source.name)
        put("url", url)
        put("fileName", fileName)
        put("enabled", enabled)
        put("nodeCount", nodeCount)
        put("lastFetchedAt", lastFetchedAt)
        put("lastError", lastError ?: JSONObject.NULL)
    }

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun fromJson(obj: JSONObject): Subscription = Subscription(
            id = obj.getString("id"),
            name = obj.optString("name", "未命名订阅"),
            source = runCatching { SubscriptionSource.valueOf(obj.optString("source", "URL")) }
                .getOrDefault(SubscriptionSource.URL),
            url = obj.optString("url", ""),
            fileName = obj.optString("fileName", ""),
            enabled = obj.optBoolean("enabled", true),
            nodeCount = obj.optInt("nodeCount", 0),
            lastFetchedAt = obj.optLong("lastFetchedAt", 0L),
            lastError = if (obj.isNull("lastError")) null else obj.optString("lastError").ifBlank { null },
        )
    }
}
