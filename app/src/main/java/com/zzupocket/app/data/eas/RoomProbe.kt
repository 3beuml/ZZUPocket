package com.zzupocket.app.data.eas

import android.util.Log
import com.zzupocket.app.core.AppError
import com.zzupocket.app.core.JsonStructure
import com.zzupocket.app.data.auth.AuthRepository
import com.zzupocket.app.data.db.AppDatabase
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 教务「教室占用」接口自检 v3：专治 `/room/place/rooms` 的请求体形状。
 *
 * v2 已经把其余部分全部打通（接口清单来自学校 H5 的 JS）：
 *  - 鉴权：`Authorization: <CAS userToken>` 可用（不需要 X-Id-Token）
 *  - 校区：主校区 id = 2（另有南/北/东/…共 5 个）
 *  - 主校区有 61 栋楼（北1 id=5、北2 id=6…），楼层 1–20，教室类型 18 种
 *  - 节次：/room/system/course-units（已在用）
 *
 * 只有 `POST /room/place/rooms` 报 500，但服务端把原因写得很清楚：
 * ```
 * Can not construct instance of com.supwisdom.eams.system.place.domain.model.CampusAssoc:
 * no String-argument constructor/factory method to deserialize from String value ('2')
 * ```
 * → `campusAssoc` 要的是**对象**（域名模型），不是字符串。这个探针把几种可能形状都试一遍，
 * 并且把服务端返回的 `message` 原样打出来（那是唯一能指出"该怎么发"的线索）。
 */
class RoomProbe(
    private val eas: EasClient,
    private val auth: AuthRepository,
    private val db: AppDatabase,
    private val json: Json,
) {

    suspend fun run(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 教务教室占用接口自检 v3（请求体形状） ===")
        sb.appendLine("时间：${LocalDate.now()}")

        val token = try {
            auth.requireCasToken()
        } catch (e: Throwable) {
            sb.appendLine("!! 拿不到教务令牌：${e.message}（需要先登录）")
            return finish(sb.toString())
        }
        sb.appendLine("教务令牌：已获取（${token.length} 字符）")

        val today = LocalDate.now().toString()
        // v2 已确认：主校区 id=2、北1 id=5
        val campusId = runCatching {
            val text = call("/room/place/campus", token, null, false)
            mainCampusId(text.second)
        }.getOrNull() ?: "2"
        val buildingId = runCatching {
            val text = call("/room/place/building?campusAssoc=$campusId", token, null, false)
            firstIdOf(text.second)
        }.getOrNull() ?: "5"
        sb.appendLine("主校区 campusAssoc=$campusId，第一个楼栋 id=$buildingId（北1）")

        // ── 请求体形状矩阵 ────────────────────────────────────────────
        sb.appendLine()
        sb.appendLine("========== 请求体形状矩阵（POST /room/place/rooms） ==========")
        var roomText = ""
        var winner = ""
        for (variant in variants(campusId, buildingId, today)) {
            sb.appendLine()
            sb.appendLine("--- 形状 ${variant.first} ---")
            sb.appendLine("body = ${variant.second}")
            val (status, text) = call("/room/place/rooms", token, variant.second, usePost = true)
            sb.appendLine("HTTP $status，响应 ${text.length} 字符")
            if (status in 200..299) {
                winner = variant.first
                roomText = text
                sb.appendLine("✔ 这个形状可用")
                JsonStructure.describe(json, text, maxLines = 45).forEach { sb.appendLine("  $it") }
                sb.appendLine("原文片段：${text.take(700)}")
                break
            } else {
                // 服务端的 message 是唯一能指出"该怎么发"的线索，原样打出来
                sb.appendLine("服务端说：${serverMessage(text)}")
            }
        }

        if (winner.isEmpty()) {
            sb.appendLine()
            sb.appendLine("!! 三种形状都不行，需要看上面的 message 再定（可能是字段名不同）")
            return finish(sb.toString())
        }
        sb.appendLine()
        sb.appendLine("→ 可用形状：$winner")

        // ── 占用详情 ──────────────────────────────────────────────────
        sb.appendLine()
        sb.appendLine("========== 占用详情 GET /room/place/room-occupancy-info ==========")
        val roomId = firstRoomId(roomText)
        sb.appendLine("从教室列表里取到的 roomId = ${roomId ?: "（没解析出来，看上面字段结构）"}")
        if (roomId != null) {
            val units = runCatching {
                call("/room/system/course-units", token, null, false).second
            }.getOrDefault("")
            val unit1 = firstUnitTimes(units)
            sb.appendLine("第 1 节时间：$unit1")
            listOf(
                "roomId" to "?roomId=$roomId&date=$today",
                "id" to "?id=$roomId&date=$today",
                "room+时段" to "?roomId=$roomId&date=$today&startLocalTime=$unit1&endLocalTime=$unit1",
            ).forEach { (label, query) ->
                sb.appendLine()
                sb.appendLine("--- 参数写法：$label → $query ---")
                val (status, text) = call("/room/place/room-occupancy-info$query", token, null, false)
                sb.appendLine("HTTP $status，响应 ${text.length} 字符")
                if (status in 200..299) {
                    JsonStructure.describe(json, text, maxLines = 40).forEach { sb.appendLine("  $it") }
                    sb.appendLine("原文片段：${text.take(500)}")
                } else {
                    sb.appendLine("服务端说：${serverMessage(text)}")
                }
            }
        }

        return finish(sb.toString())
    }

    /** 几种可能的请求体形状，按可能性排序 */
    private fun variants(campusId: String, buildingId: String, date: String): List<Pair<String, String>> {
        fun body(campus: String, building: String): String = """{
            "currentPage":1,"pageSize":5,
            "campusAssoc":$campus,
            "buildingIds":[$building],
            "roomTypeIds":[],"floors":[],"minSeat":"","maxSeat":"",
            "date":"$date"
        }""".trimIndent()

        return listOf(
            // ① campusAssoc 当对象，里面放 id（最像域名模型）
            "A campusAssoc={id}" to body("""{"id":$campusId}""", buildingId),
            // ② 对象里放 campusId
            "B campusAssoc={campusId}" to body("""{"campusId":$campusId}""", buildingId),
            // ③ 干脆不传校区（只按楼栋查）
            "C 不传校区" to """{
                "currentPage":1,"pageSize":5,
                "buildingIds":[$buildingId],
                "roomTypeIds":[],"floors":[],"minSeat":"","maxSeat":"",
                "date":"$date"
            }""".trimIndent(),
            // ④ 校区传数字（对比：v2 传字符串报的错是不是只是类型问题）
            "D campusAssoc=数字" to body(campusId, buildingId),
        )
    }

    /** 从 Spring 的错误 JSON 里把 message 抠出来 —— 那一行才是"该怎么发"的线索 */
    private fun serverMessage(text: String): String = try {
        val root = json.parseToJsonElement(text) as? JsonObject
        val message = root?.get("message")?.jsonPrimitive?.contentOrNull
        val exception = root?.get("exception")?.jsonPrimitive?.contentOrNull
        listOfNotNull(message, exception).joinToString(" | ").take(400).ifEmpty { text.take(220) }
    } catch (_: Exception) {
        text.take(220)
    }

    private suspend fun call(
        path: String,
        token: String,
        body: String?,
        usePost: Boolean,
    ): Pair<Int, String> = try {
        eas.rawCall("/eams-micro-server/api/v1$path", token, body, usePost, "Authorization")
    } catch (e: AppError) {
        -1 to "请求失败：${e.message}"
    } catch (e: Throwable) {
        -1 to "请求失败：${e.message}"
    }

    private fun mainCampusId(text: String): String? = try {
        val root = json.parseToJsonElement(text) as? JsonObject
        val list = root?.get("data") as? JsonArray
        val items = list?.mapNotNull { it as? JsonObject }.orEmpty()
        val main = items.firstOrNull {
            it["nameZh"]?.jsonPrimitive?.contentOrNull?.contains("主校区") == true
        } ?: items.firstOrNull()
        main?.get("id")?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) {
        null
    }

    private fun firstIdOf(text: String): String? = try {
        val root = json.parseToJsonElement(text) as? JsonObject
        val list = root?.get("data") as? JsonArray
        (list?.firstOrNull() as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) {
        null
    }

    /** 教室列表里第一间教室的 id（字段名先按 id / roomId 两种找） */
    private fun firstRoomId(text: String): String? = try {
        val root = json.parseToJsonElement(text) as? JsonObject
        val data = root?.get("data") as? JsonObject
        val list = data?.get("data") as? JsonArray
        val room = list?.firstOrNull() as? JsonObject
        if (room == null) {
            null
        } else {
            listOf("id", "roomId", "roomAssoc")
                .firstNotNullOfOrNull { key -> room[key]?.jsonPrimitive?.contentOrNull }
        }
    } catch (_: Exception) {
        null
    }

    /** 第 1 节的 startTime（HHmm 整数），用来试占用详情的时段参数 */
    private fun firstUnitTimes(text: String): String = try {
        val root = json.parseToJsonElement(text) as? JsonObject
        val list = root?.get("data") as? JsonArray
        val unit = list?.firstOrNull() as? JsonObject
        unit?.get("startTime")?.jsonPrimitive?.contentOrNull ?: "800"
    } catch (_: Exception) {
        "800"
    }

    private fun finish(report: String): String {
        Log.i(TAG, "===== 教务教室占用接口自检 v3 开始 =====")
        report.chunked(2800).forEach { Log.i(TAG, it) }
        Log.i(TAG, "===== 教务教室占用接口自检 v3 结束 =====")
        return report
    }

    private companion object {
        const val TAG = "RoomProbe"
    }
}
