package com.zzupocket.app.data.ecard

import android.util.Log
import com.zzupocket.app.core.AppError
import com.zzupocket.app.data.auth.AuthRepository
import com.zzupocket.app.data.db.AppDatabase
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 一卡通接口自检。
 *
 * 用途：P4「电费图形化」和「校园卡消费」要用的接口，路径是从一卡通 H5 的 JS 包里
 * 挖出来的（共 504 条）。第一轮自检已确认：
 *  - `usageDetailsV2` 在服务器上不存在（404），只有 v1 的 `usageDetails`；
 *  - v1 对**照明房间**回"所选房间不支持查询历史用电量"（空调房间上轮误用了 v2，没测到）；
 *  - `user/tradeList` 存在，但 tradeType 必填；`tradeListType` 可能要求 POST；
 *  - `/bllt/query*` 三个汇总接口都回 CORE10004 操作失败。
 *
 * 所以第二轮按"逐个排除可能性"来设计：换房间、换日期范围、换 utilityType、
 * 换 HTTP 方法、换 tradeType 候选值，把能用的那个试出来。
 *
 * 产出里**只有字段名和示例值，没有令牌、没有密码**。
 */
class EcardProbe(
    private val ecard: EcardClient,
    private val auth: AuthRepository,
    private val db: AppDatabase,
    private val json: Json,
) {

    suspend fun run(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 一卡通接口自检 v2 ===")
        sb.appendLine("时间：${LocalDate.now()}")

        val settings = runCatching { db.settingsDao().get() }.getOrNull()
        // 房间号是每个人自己的，学校没有默认值 —— **绝不在代码里写死任何房间号**
        // （曾经拿作者本人的寝室当默认值，开源前已删除）。没设置就跳过跟房间有关的自检。
        val lightRoom = settings?.lightRoomId.orEmpty()
        val acRoom = settings?.acRoomId.orEmpty()
        sb.appendLine("照明房间：${lightRoom.ifBlank { "（未设置）" }}")
        sb.appendLine("空调房间：${acRoom.ifBlank { "（未设置）" }}")

        val token = try {
            auth.requireEcardAccessToken()
        } catch (e: Throwable) {
            sb.appendLine()
            sb.appendLine("!! 拿不到一卡通令牌：${e.message}")
            sb.appendLine("（需要先登录，然后随便查一次电费让 App 换取一卡通令牌）")
            return finish(sb.toString())
        }
        sb.appendLine("一卡通令牌：已获取（${token.length} 字符，不打印内容）")

        val today = LocalDate.now()
        val monthStart = today.withDayOfMonth(1).toString()
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth()).toString()
        val ym = today.toString().substring(0, 7)
        val wideFrom = "2020-01-01"

        // ── A. 电费：配置与房间 ────────────────────────────────────────
        sb.appendLine()
        sb.appendLine("========== A. 电费配置 / 房间 ==========")
        section(
            sb, "A1 /utilities/config  utilityType=electric", token,
            "/utilities/config", """{"utilityType":"electric"}""",
        )
        if (lightRoom.isBlank()) {
            sb.appendLine("A2 需要房间号，未设置 → 已跳过")
        } else {
            section(
                sb, "A2 /utilities/config  utilityType + 房间", token,
                "/utilities/config", configBody(lightRoom),
            )
        }
        section(
            sb, "A3 /utilities/location  locationType=area", token,
            "/utilities/location", """{"utilityType":"electric","locationType":"area"}""",
        )
        section(
            sb, "A4 /utilities/location  locationType=1", token,
            "/utilities/location", """{"utilityType":"electric","locationType":"1"}""",
        )
        section(
            sb, "A5 /utilities/typeList  （缴费类型字典）", token,
            "/utilities/typeList", "{}",
        )

        // ── B. 历史用电：换房间 / 换范围 / 换 utilityType ──────────────
        sb.appendLine()
        sb.appendLine("========== B. 历史用电明细（逐个排除） ==========")
        if (lightRoom.isBlank() || acRoom.isBlank()) {
            sb.appendLine("B1~B5 需要房间号，未设置 → 已跳过")
        } else {
            section(
                sb, "B1 空调房间 + 本月", token,
                "/utilities/usageDetails", electricityBody(acRoom, monthStart, monthEnd, 5),
            )
            section(
                sb, "B2 空调房间 + 2020 至今", token,
                "/utilities/usageDetails", electricityBody(acRoom, wideFrom, today.toString(), 5),
            )
            section(
                sb, "B3 照明房间 + 2020 至今", token,
                "/utilities/usageDetails", electricityBody(lightRoom, wideFrom, today.toString(), 5),
            )
            section(
                sb, "B4 照明房间 + utilityType=light", token,
                "/utilities/usageDetails",
                electricityBody(lightRoom, monthStart, monthEnd, 5, utilityType = "light"),
            )
            section(
                sb, "B5 照明房间 + 不带日期（只带房间）", token,
                "/utilities/usageDetails",
                electricityBody(lightRoom, "", "", 5, withDates = false),
            )
        }

        // ── C. 校园卡：类型字典（改 POST） ─────────────────────────────
        sb.appendLine()
        sb.appendLine("========== C. 校园卡交易类型 ==========")
        val typeText = section(
            sb, "C1 /user/tradeListType  POST {}", token,
            "/user/tradeListType", "{}",
        )
        if (!looksOk(typeText)) {
            section(
                sb, "C2 /user/tradeListType  POST {balance:false}", token,
                "/user/tradeListType", """{"balance":false}""",
            )
        }
        val types = tradeTypesOf(typeText)
        // 字典里带出来的 type 值，拼进报告方便对照
        if (types.isNotEmpty()) sb.appendLine("字典里的 tradeType 候选：${types.joinToString(",")}")

        // ── D. 交易流水：找出合法的 tradeType ──────────────────────────
        sb.appendLine()
        sb.appendLine("========== D. 交易流水（找合法 tradeType） ==========")
        val candidates = (types + listOf("0", "1", "2", "-1")).distinct().take(6)
        var winner = ""
        for (type in candidates) {
            val text = section(
                sb, "D tradeType=$type", token,
                "/user/tradeList", tradeBody(type, monthStart, monthEnd, 2),
            )
            if (winner.isEmpty() && looksOk(text)) winner = type
        }
        if (winner.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("→ 可用的 tradeType：$winner")
            section(
                sb, "E  /user/tradeList tradeType=$winner  2015 至今（看能追溯多久）", token,
                "/user/tradeList", tradeBody(winner, "2015-01-01", today.toString(), 5),
            )
        } else {
            sb.appendLine()
            sb.appendLine("→ 没有试出可用的 tradeType（需要看 C 段字典是否拿到了）")
        }

        // ── F. 汇总接口再试一次（换方法/带分页参数） ───────────────────
        sb.appendLine()
        sb.appendLine("========== F. 汇总接口重试 ==========")
        section(sb, "F1 /bllt/queryChartList GET", token, "/bllt/queryChartList", null)
        section(
            sb, "F2 /bllt/queryChartList POST {pageNo,pageSize}", token,
            "/bllt/queryChartList", """{"pageNo":0,"pageSize":10}""",
        )
        section(
            sb, "F3 /bllt/queryConsumeTotalDay startdate=$ym chartId=0", token,
            "/bllt/queryConsumeTotalDay", """{"startdate":"$ym","enddate":"$ym","chartId":"0"}""",
        )

        // ── G. 校园卡余额：另一个域名 + X-Id-Token + CAS userToken ──────
        sb.appendLine()
        sb.appendLine("========== G. 校园卡余额（原始响应，用来确认取哪个下标） ==========")
        try {
            val (status, text) = ecard.rawCardBalance(auth.requireCasToken())
            sb.appendLine("HTTP $status，响应 ${text.length} 字符")
            if (text.isNotBlank()) {
                structure(sb, text)
                sb.appendLine("原文片段：${text.take(900)}")
            }
        } catch (e: Throwable) {
            sb.appendLine("请求失败：${e.message}")
        }

        return finish(sb.toString())
    }

    /** 调用一个接口并把「状态码 + 字段结构 + 原文片段」写进报告；返回原文 */
    private suspend fun section(
        sb: StringBuilder,
        title: String,
        token: String,
        path: String,
        body: String?,
    ): String {
        sb.appendLine()
        sb.appendLine("--- $title ---")
        val (status, text) = try {
            var result = ecard.rawCall(path, token, body)
            // 有些接口只认 GET：POST 被拒时再试一次，并记下哪种能用
            if (result.first == 405 || result.first == 404) {
                val retry = runCatching { ecard.rawCall(path, token, null) }.getOrNull()
                if (retry != null && retry.first in 200..299 && body != null) {
                    sb.appendLine("（POST 被拒，改 GET 成功）")
                    result = retry
                }
            }
            result
        } catch (e: AppError) {
            sb.appendLine("请求失败：${e.message}")
            return ""
        } catch (e: Throwable) {
            sb.appendLine("请求失败：${e.message}")
            return ""
        }

        sb.appendLine("HTTP $status，响应 ${text.length} 字符")
        if (text.isBlank()) return text
        structure(sb, text)
        sb.appendLine("原文片段：${text.take(600)}")
        return text
    }

    /** 服务端认为"这次调用没问题"的粗略判断 */
    private fun looksOk(text: String): Boolean =
        text.contains("\"success\":true") ||
            text.contains("\"resultData\":[") ||
            (text.contains("\"resultData\":{") && !text.contains("\"success\":false"))

    /** 从 tradeListType 的返回里取出 type 候选值 */
    private fun tradeTypesOf(text: String): List<String> = try {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        val list = root["resultData"] as? JsonArray ?: return emptyList()
        list.mapNotNull { element ->
            (element as? JsonObject)?.get("type")?.toString()?.trim('"')?.takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** 把 JSON 的字段结构列出来 —— 比整段原文更省地方，也更容易看出字段名 */
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
        lines.take(90).forEach { sb.appendLine("  $it") }
        if (lines.size > 90) sb.appendLine("  …（还有 ${lines.size - 90} 行）")
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

    /**
     * 用电明细的请求体 = areaInfo + 日期区间 + 分页（照抄前端页面的构造方式）。
     * [withDates] = false 时只发房间信息，用来排除"日期范围不被接受"的可能。
     */
    private fun electricityBody(
        roomId: String,
        from: String,
        to: String,
        pageSize: Int,
        utilityType: String = EcardClient.UTILITY_TYPE_ELECTRIC,
        withDates: Boolean = true,
    ): String {
        val parsed = runCatching { EcardClient.parseRoomId(roomId) }.getOrNull()
        return buildJsonObject {
            put("utilityType", utilityType)
            put("bigArea", "")
            put("area", parsed?.area.orEmpty())
            put("building", parsed?.building.orEmpty())
            put("unit", "")
            put("level", parsed?.level.orEmpty())
            put("room", parsed?.room ?: roomId)
            put("subArea", "")
            if (withDates) {
                put("fromDate", from)
                put("toDate", to)
            }
            put("pageSize", pageSize)
            put("pageNo", 0)
        }.toString()
    }

    /** 电费配置：带上房间信息，万一配置是按房间下发的 */
    private fun configBody(roomId: String): String {
        val parsed = runCatching { EcardClient.parseRoomId(roomId) }.getOrNull()
        return buildJsonObject {
            put("utilityType", EcardClient.UTILITY_TYPE_ELECTRIC)
            put("area", parsed?.area.orEmpty())
            put("building", parsed?.building.orEmpty())
            put("level", parsed?.level.orEmpty())
            put("room", parsed?.room ?: roomId)
        }.toString()
    }

    private fun tradeBody(type: String, from: String, to: String, pageSize: Int): String =
        buildJsonObject {
            put("pageSize", pageSize.toString())
            put("pageNo", 0)
            put("tradeType", type)
            put("fromDate", from)
            put("toDate", to)
        }.toString()

    /** 结果同时写一份到 logcat，这样不用截图 / 复制也能取到 */
    private fun finish(report: String): String {
        Log.i(TAG, "===== 一卡通接口自检 v2 开始 =====")
        report.chunked(2800).forEach { Log.i(TAG, it) }
        Log.i(TAG, "===== 一卡通接口自检 v2 结束 =====")
        return report
    }

    private companion object {
        const val TAG = "EcardProbe"
    }
}
