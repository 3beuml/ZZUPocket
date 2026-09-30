package com.zzupocket.app.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 把一段 JSON 的**字段结构**摊平成文本。
 *
 * 用途：接口探测时确认"服务端到底返回了什么字段"。
 * 只列字段名和示例值，不打印任何令牌 —— 结果是要贴给人看的，也可能贴进聊天记录。
 */
object JsonStructure {

    fun describe(json: Json, text: String, maxDepth: Int = 6, maxLines: Int = 90): List<String> {
        val element = try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            return listOf("（响应不是 JSON）")
        }
        val lines = mutableListOf<String>()
        flatten(element, "", lines, 0, maxDepth)
        return if (lines.size > maxLines) {
            lines.take(maxLines) + "…（还有 ${lines.size - maxLines} 行）"
        } else {
            lines
        }
    }

    private fun flatten(
        element: JsonElement,
        path: String,
        out: MutableList<String>,
        depth: Int,
        maxDepth: Int,
    ) {
        if (depth > maxDepth) return
        when (element) {
            is JsonObject -> element.entries.forEach { (key, value) ->
                flatten(value, if (path.isEmpty()) key else "$path.$key", out, depth + 1, maxDepth)
            }

            is JsonArray -> {
                out.add("$path[] （${element.size} 项）")
                element.firstOrNull()?.let { flatten(it, "$path[]", out, depth + 1, maxDepth) }
            }

            else -> out.add("$path = ${element.toString().take(48)}")
        }
    }
}
