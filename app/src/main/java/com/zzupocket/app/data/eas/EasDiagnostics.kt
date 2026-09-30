package com.zzupocket.app.data.eas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 诊断辅助：只取 JSON 顶层字段名，绝不带出字段值（响应里可能含个人信息） */
object EasDiagnostics {
    fun topKeys(json: Json, text: String): String = try {
        val obj = json.parseToJsonElement(text) as? JsonObject
        obj?.keys?.joinToString(",") ?: "（不是 JSON 对象）"
    } catch (_: Exception) {
        "（无法解析为 JSON，前 120 字符：${text.take(120)}）"
    }
}
