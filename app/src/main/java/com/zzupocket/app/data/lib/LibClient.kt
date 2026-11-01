package com.zzupocket.app.data.lib

import com.zzupocket.app.core.AppError
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 图书馆系统 JSON 信封的 code 值：0 = 成功 */
const val LIB_CODE_OK = 0

/** 未登录 / 会话失效的统一形态（所有需登录接口同码） */
const val LIB_CODE_NO_SESSION = 10001

/**
 * 图书馆空间预约系统（order.lib.zzu.edu.cn）的原生客户端：**预约记录 / 违约记录**。
 *
 * 接口与字段全部来自它自己 H5 前端包的逆向（见 docs/interfaces/图书馆空间预约系统接口逆向.md）：
 *  - 座位预约记录   `POST /v4/member/seat`      body {type:"1普通/3研习/4考研", page, limit}
 *  - 座位违约记录   `POST /v4/member/seatRenege` 同上
 *  - 空间预约记录   `POST /v4/seminar/books`
 *  - 空间违约记录   `POST /v4/seminar/reneges`
 *  - 待处理预约     `POST /v4/space/my_wait`    body {page, limit}
 *  响应信封 `{code:0, message:"操作成功", data:{data:[...], total}}`。
 *
 * 会话是双通道：`authorization: bearer<token>`（SPA 主会话，**bearer 后没有空格**，
 * 照抄它自己拦截器的写法）+ `Cookie`（PHPSESSID 兑票链落下）。服务端认哪一条由
 * 真机自检摸清；两条都带最稳妥。
 */
class LibClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val session: () -> LibSession,
) {

    // ── 信封 ────────────────────────────────────────────────────────────

    @Serializable
    internal data class Envelope(
        val code: Int = -1,
        val message: String? = null,
        val msg: String? = null,
        val data: JsonElement? = null,
    )

    /** 一页列表 */
    data class Page(val total: Int = 0, val items: List<JsonObject> = emptyList())

    // ── 记录接口 ────────────────────────────────────────────────────────

    /** 座位预约记录。[type] = 普通座位 [TYPE_SEAT_NORMAL] / 研习 [TYPE_SEAT_STUDY] / 考研 [TYPE_SEAT_EXAM] */
    suspend fun seatRecords(type: String, page: Int = 0, limit: Int = 50): Page =
        records("/v4/member/seat", """{"type":"$type","page":$page,"limit":$limit}""")

    /** 座位违约记录（未签到 / 未签离等，累计 3 次停权 7 天） */
    suspend fun seatReneges(type: String, page: Int = 0, limit: Int = 50): Page =
        records("/v4/member/seatRenege", """{"type":"$type","page":$page,"limit":$limit}""")

    /** 空间（研讨间）预约记录；`type` 的可选值待真机 dump（先发 1 看真实回包） */
    suspend fun seminarRecords(page: Int = 0, limit: Int = 50): Page =
        records("/v4/seminar/books", """{"type":"1","page":$page,"limit":$limit}""")

    /** 空间违约记录 */
    suspend fun seminarReneges(page: Int = 0, limit: Int = 50): Page =
        records("/v4/seminar/reneges", """{"type":"1","page":$page,"limit":$limit}""")

    /** 待处理预约（座位记录页顶部的"我的预约"横条，含候补） */
    suspend fun spacePending(page: Int = 0, limit: Int = 50): Page =
        records("/v4/space/my_wait", """{"page":$page,"limit":$limit}""")

    /**
     * 原始调用，给自检用：**不做任何反序列化**（自检就是要看服务端到底回了什么）。
     * [withToken]/[withCookie] 不是为了省事 —— 是自检要做的「单通道实验」：
     * 只带 Authorization 或只带 Cookie，看服务端认哪个。
     */
    suspend fun rawPost(
        path: String,
        body: String,
        withCookie: Boolean = true,
        withToken: Boolean = true,
    ): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val s = session()
            val builder = Request.Builder()
                .url("$BASE$path")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", "$BASE/h5/")
            if (withToken && !s.token.isNullOrBlank()) builder.header("Authorization", "bearer${s.token}")
            if (withCookie && !s.cookie.isNullOrBlank()) builder.header("Cookie", s.cookie!!)
            builder.post(body.toRequestBody(JSON_MEDIA))
            val response = try {
                client.newCall(builder.build()).execute()
            } catch (e: IOException) {
                throw AppError.Network("无法连接图书馆系统（校外只能走 HTTP:80）", e)
            }
            response.use { it.code to (it.body?.string().orEmpty()) }
        }

    private suspend fun records(path: String, body: String): Page {
        val (status, text) = rawPost(path, body)
        if (status !in 200..299) throw AppError.Server(status, "图书馆接口失败（HTTP $status，$path）")
        return parsePage(text)
            ?: throw AppError.Parse("图书馆响应无法解析（顶层字段：${topLevelKeys(text)}）", null)
    }

    // ── 解析（纯函数，单测覆盖）─────────────────────────────────────────

    /** 把 `{code, message/msg, data:{data:[], total}}` 解成分页；code 非 0 按语义抛错 */
    internal fun parsePage(text: String): Page? {
        // 实测（2026-10-31 真机）：/v4/space/my_wait 在"没有待处理预约"时返回**空响应体**，
        // 连信封都没有 —— 语义就是 0 条，别当解析失败。
        if (text.isBlank()) return Page()
        val env = try {
            json.decodeFromString(Envelope.serializer(), text)
        } catch (_: Exception) {
            return null
        }
        if (env.code == LIB_CODE_NO_SESSION) {
            throw AppError.ReLoginRequired("图书馆会话已失效：打开图书馆页（会自动登录一次）后再回来查记录")
        }
        if (env.code != LIB_CODE_OK) {
            throw AppError.Server(env.code, "图书馆拒绝了这次查询：${env.message ?: env.msg ?: "code=${env.code}"}")
        }
        val payload = env.data as? JsonObject ?: return Page()
        val items = (payload["data"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        val total = (payload["total"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: items.size
        return Page(total = total, items = items)
    }

    private fun topLevelKeys(json_text: String): String = try {
        (json.parseToJsonElement(json_text) as? JsonObject)?.keys?.joinToString(",") ?: "（不是 JSON 对象）"
    } catch (_: Exception) {
        "（无法解析为 JSON）"
    }

    companion object {
        /** ⚠️ 校外只有 80 端口可用（443 被校园网边界拒绝），network_security_config 已放行明文 */
        const val BASE = "http://order.lib.zzu.edu.cn"

        /** WebView 会话捕获后从 CookieManager 取 cookie 用的地址（站点根，PHPSESSID 落在根路径） */
        const val COOKIE_URL = "$BASE/"

        /** WebView 里读取会话 token：登录成功后 SPA 把它放在 sessionStorage */
        const val TOKEN_JS = "(function(){try{return String(sessionStorage.getItem('token')||'')}catch(e){return ''}})()"

        /** 与 H5 在手机 WebView 里同一形态的 UA */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; MEIZU 21 Note) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** 座位记录的 type 值（来自菜单配置）：普通座位 */
        const val TYPE_SEAT_NORMAL = "1"

        /** 研习座位 */
        const val TYPE_SEAT_STUDY = "3"

        /** 考研座位 */
        const val TYPE_SEAT_EXAM = "4"

        /** 记录条目里「预约发生在哪天」的候选字段（详情页组件实际画出来的名字） */
        internal val DATE_KEYS = listOf("create_time", "createTime", "beginTime", "begin_time", "examTime")

        /**
         * 从一条记录里取「预约发生在哪天」，热力图按它聚合。
         *
         * 值的形态有三种（同一后端不同字段可能不同）：`2026-10-29 12:34:56`、
         * ISO 字符串、纯数字时间戳（毫秒或秒）。
         */
        fun firstReservationDay(raw: JsonObject): LocalDate? {
            for (key in DATE_KEYS) {
                val text = (raw[key] as? JsonPrimitive)?.contentOrNull ?: continue
                if (text.isBlank()) continue
                parseDay(text)?.let { return it }
            }
            return null
        }

        /** 展示用时段文本：begin/end 成对字段拼成一行（只挑两端都有值的那种） */
        fun rangeText(raw: JsonObject): String? {
            val begin = firstText(raw, "beginTime", "begin_time")
            val end = firstText(raw, "endTime", "end_time")
            return when {
                begin == null && end == null -> null
                begin == end -> begin
                begin == null -> end
                end == null -> begin
                else -> "$begin ~ $end"
            }
        }

        private fun firstText(raw: JsonObject, vararg keys: String): String? =
            keys.firstNotNullOfOrNull { (raw[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }

        /** 把字段的原始值解成 LocalDate；认不出返回 null（调用方继续找下一个候选） */
        private fun parseDay(text0: String): LocalDate? {
            val t = text0.trim()
            // 纯数字 → 时间戳：10 位当秒、13 位当毫秒
            t.toLongOrNull()?.let { epoch ->
                val ms = if (kotlin.math.abs(epoch) < 100_000_000_000L) epoch * 1000 else epoch
                return runCatching { Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
            }
            // `2026-10-29 12:34:56` → 取日期部分直解
            runCatching { return LocalDate.parse(t.take(10), DateTimeFormatter.ISO_LOCAL_DATE) }
            // `2026-10-29T12:34:56`
            runCatching { return LocalDateTime.parse(t, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalDate() }
            return null
        }
    }
}
