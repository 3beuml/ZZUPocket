package com.zzupocket.app.data.lib

import android.util.Log
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 图书馆预约记录接口自检（带会话）。
 *
 * 上一轮（`LibProbe`）确认了传输层：明文 HTTP 通、需登录接口回 `10001 您尚未登录`。
 * 这一轮要回答三个真机问题（配电设备一次登录后即可）：
 *
 *  1. 会话走 **token 通道** 还是 **cookie 通道**？—— 同一接口分别
 *     `Authorization + Cookie 全带` / `只 Authorization` / `只 Cookie` 对照。
 *  2. 接口真实响应里记录的**字段名与值形态** —— 热力图聚合要的日期字段（`create_time`?）
 *     就靠这里 dump 实物确认。
 *  3. `limit` 能开多大（决定一次拉多远的历史）。
 *
 * 产出只有字段名与示例值，**不含 token / cookie 的值**。
 */
class LibRecordsProbe(
    private val lib: LibClient,
    private val sessionSource: com.zzupocket.app.data.lib.LibSessionSource,
    private val json: Json,
) {

    suspend fun run(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 图书馆记录接口自检 v2（带会话）===")
        sb.appendLine("时间：${LocalDate.now()}")

        val session = sessionSource.current()
        if (session.isEmpty()) {
            sb.appendLine("!! 还没有任何会话：先打开一次「图书馆」属页（WebView 会自动登录一次），")
            sb.appendLine("   登录成功后 App 会从 sessionStorage 抓到 token，再回来跑本自检。")
            return finish(sb.toString())
        }
        sb.appendLine("会话：token=${session.token?.length ?: 0} 字符，cookie 项数=${cookieCount(session.cookie)}")
        if (sessionSource.capturedAt() > 0L) {
            sb.appendLine("token 捕获于 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(sessionSource.capturedAt()))}")
        }

        // ── A. 会话通道对照（座位记录） ─────────────────────────────────
        val body = """{"type":"${LibClient.TYPE_SEAT_NORMAL}","page":1,"limit":10}"""
        sb.appendLine()
        sb.appendLine("========== A. 会话通道对照（/v4/member/seat）==========")
        val dual = section(sb, "A1 token + cookie 全带", "/v4/member/seat", body)
        if (codeOf(dual) == LIB_CODE_NO_SESSION) {
            sb.appendLine("→ 双通道仍 10001：会话可能是 WebView 这一刻才被换掉，重开图书馆页再试")
        }
        val tokenOnly = section(sb, "A2 只 Authorization（不带 Cookie）", "/v4/member/seat", body, cookie = false)
        val cookieOnly = section(sb, "A3 只 Cookie（不带 Authorization）", "/v4/member/seat", body, token = false)
        val verdict = when {
            codeOf(dual) == LIB_CODE_OK && codeOf(tokenOnly) == LIB_CODE_OK -> "token 通道足够"
            codeOf(dual) == LIB_CODE_OK && codeOf(cookieOnly) == LIB_CODE_OK -> "cookie 通道足够"
            codeOf(dual) == LIB_CODE_OK -> "需要双通道"
            else -> "两个通道都没打通（见上方原文）"
        }
        sb.appendLine("→ 通道结论：$verdict")

        // ── B. 记录字段实物 dump ────────────────────────────────────────
        if (codeOf(dual) == LIB_CODE_OK) {
            sb.appendLine()
            sb.appendLine("========== B. 记录字段（真实条目）==========")
            dumpRecord(sb, "B1 /v4/member/seat          座位预约记录", dual)
            dumpRecord(
                sb, "B2 /v4/member/seatRenege    座位违约记录",
                section(sb, "B2 调用", "/v4/member/seatRenege", body),
            )
            dumpRecord(
                sb, "B3 /v4/space/my_wait        待处理/候补",
                section(sb, "B3 调用", "/v4/space/my_wait", """{"page":1,"limit":10}"""),
            )
            dumpRecord(
                sb, "B4 /v4/seminar/books        空间预约记录",
                section(sb, "B4 调用", "/v4/seminar/books", """{"type":"1","page":1,"limit":10}"""),
            )
            dumpRecord(
                sb, "B5 /v4/seminar/reneges      空间违约记录",
                section(sb, "B5 调用", "/v4/seminar/reneges", """{"type":"1","page":1,"limit":10}"""),
            )
            // 三个座位类型的对照组
            section(sb, "B6 /v4/member/seat type=3 研习", "/v4/member/seat", """{"type":"3","page":1,"limit":10}""")
            section(sb, "B7 /v4/member/seat type=4 考研", "/v4/member/seat", """{"type":"4","page":1,"limit":10}""")

            // ── C. limit 能开多大 ─────────────────────────────────────────
            sb.appendLine()
            sb.appendLine("========== C. limit 探顶（决定一次拉多远历史）==========")
            for (limit in listOf(50, 100, 500)) {
                val text = section(sb, "C limit=$limit", "/v4/member/seat", """{"type":"1","page":1,"limit":$limit}""")
                sb.appendLine("   该响应里的条数=${itemsCount(text)}")
            }
        }

        return finish(sb.toString())
    }

    private suspend fun section(
        sb: StringBuilder,
        title: String,
        path: String,
        body: String,
        cookie: Boolean = true,
        token: Boolean = true,
    ): String {
        sb.appendLine()
        sb.appendLine("--- $title${if (!cookie) "（无 Cookie）" else ""}${if (!token) "（无 token）" else ""} ---")
        val (status, text) = try {
            lib.rawPost(path, body, withCookie = cookie, withToken = token)
        } catch (e: Throwable) {
            sb.appendLine("请求失败：${e.message}")
            return ""
        }
        sb.appendLine("HTTP $status，响应 ${text.length} 字符")
        if (text.isBlank()) return ""
        structure(sb, text)
        sb.appendLine("原文片段：${text.take(700)}")
        return text
    }

    private fun dumpRecord(sb: StringBuilder, title: String, text: String) {
        sb.appendLine()
        sb.appendLine("--- $title ---")
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        val payload = (root?.get("data")) as? JsonObject
        val items = (payload?.get("data")) as? JsonArray ?: return
        val first = items.firstOrNull() as? JsonObject
        if (first == null) {
            sb.appendLine("列表为空（data.data 0 项）")
            return
        }
        sb.appendLine("本页 ${items.size} 条，total=${payload?.get("total")}")
        sb.appendLine("第 1 条字段（字段名 = 值形态）：")
        first.forEach { (k, v) ->
            val shape = when (v) {
                is JsonPrimitive -> "「${v.contentOrNull?.take(40)}」"
                is JsonArray -> "数组[${v.size}]"
                is JsonObject -> "对象{${v.keys.sorted().joinToString(",")}}"
                else -> v.toString()
            }
            sb.appendLine("  $k = $shape")
        }
        val day = LibClient.firstReservationDay(first)
        sb.appendLine("热力图日期字段解析 → ${day ?: "没解析出（需要人工对字段）"}")
    }

    private fun codeOf(text: String): Int = try {
        (json.parseToJsonElement(text) as? JsonObject)?.get("code")
            ?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() } ?: -1
    } catch (_: Exception) {
        -1
    }

    private fun itemsCount(text: String): Int = try {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return -1
        ((root.get("data") as? JsonObject)?.get("data") as? JsonArray)?.size ?: -1
    } catch (_: Exception) {
        -1
    }

    private fun cookieCount(cookie: String?): Int =
        cookie?.split(';')?.count { it.trim().contains('=') } ?: 0

    /** 把 JSON 的字段结构列出来（与 EcardProbe 同款小工具） */
    private fun structure(sb: StringBuilder, text: String) {
        val element = try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            sb.appendLine("（响应不是 JSON）")
            return
        }
        val lines = mutableListOf<String>()
        flatten(element, "", lines, 0)
        sb.appendLine("字段结构：")
        lines.take(60).forEach { sb.appendLine("  $it") }
        if (lines.size > 60) sb.appendLine("  …（还有 ${lines.size - 60} 行）")
    }

    private fun flatten(element: JsonElement, path: String, out: MutableList<String>, depth: Int) {
        if (depth > 6) return
        when (element) {
            is JsonObject -> element.entries.forEach { (k, v) ->
                flatten(v, if (path.isEmpty()) k else "$path.$k", out, depth + 1)
            }

            is JsonArray -> {
                out.add("$path[] （${element.size} 项）")
                element.firstOrNull()?.let { flatten(it, "$path[]", out, depth + 1) }
            }

            else -> out.add("$path = ${element.toString().take(48)}")
        }
    }

    private fun finish(report: String): String {
        Log.i(TAG, "===== 图书馆记录接口自检 v2 开始 =====")
        report.chunked(2800).forEach { Log.i(TAG, it) }
        Log.i(TAG, "===== 图书馆记录接口自检 v2 结束 =====")
        return report
    }

    private companion object {
        const val TAG = "LibRecordsProbe"
    }
}
