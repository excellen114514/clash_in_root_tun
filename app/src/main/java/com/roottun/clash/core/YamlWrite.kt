package com.roottun.clash.core

/** 生成 mihomo 可接受的 YAML 片段（flow 风格映射，避免手写缩进出错）。 */
object YamlWrite {

    fun scalar(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Int, is Long, is Short, is Byte -> value.toString()
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Float -> value.toString()
        is Map<*, *> -> flowMap(value.entries.associate { it.key.toString() to it.value })
        is Iterable<*> -> "[" + value.joinToString(", ") { scalar(it) } + "]"
        else -> quote(value.toString())
    }

    fun flowMap(map: Map<String, Any?>): String =
        "{ " + map.entries.joinToString(", ") { (key, value) -> "$key: ${scalar(value)}" } + " }"

    fun quote(text: String): String {
        val needsQuote = text.isEmpty() ||
            text.first().isWhitespace() ||
            text.last().isWhitespace() ||
            text.any { it in ":#{}[],&*?|-<>=!%@`\"'\\\n\t" } ||
            text.equals("true", ignoreCase = true) ||
            text.equals("false", ignoreCase = true) ||
            text.equals("null", ignoreCase = true) ||
            text.equals("yes", ignoreCase = true) ||
            text.equals("no", ignoreCase = true) ||
            text.equals("on", ignoreCase = true) ||
            text.equals("off", ignoreCase = true) ||
            text.toDoubleOrNull() != null
        if (!needsQuote) return text
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", " ")
        return "\"$escaped\""
    }
}
