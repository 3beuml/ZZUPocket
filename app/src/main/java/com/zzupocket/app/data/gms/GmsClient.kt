package com.zzupocket.app.data.gms

import com.zzupocket.app.core.AppError
import com.zzupocket.app.core.crypto.Crypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/**
 * 研究生教务（gmsstu.zzu.edu.cn / 新研究生系统）客户端。
 *
 * 协议全部来自页面实测（2026-10，外网直连 + 真实会话逐条复跑），取证记录见
 * `docs/gms-schedule-api.md`。
 *
 *  - 学期列表   GET  /stu/{b64}/pygl/xskbcx/kbcx_drop
 *  - 整学期课表 POST /stu/{b64}/pygl/xskbcx/loadKb，表单 `kblx=xs&termcode=<学期value>`
 *  - 本周课表   POST /stu/{b64}/pygl/xskbcx/bzkb_load，空表单（服务端按当前周返回）
 *
 * ⚠️ 与本科教务（EAS）的三个差别，接 ScheduleRepository 前必读：
 *
 *  1. 鉴权是 **CAS 网页会话 Cookie**，不是 EAS 的 JWT 裸令牌：进 `/stu` 会被 302 到
 *     `cas.s.zzu.edu.cn/cas/a/login?service=…`，登录后 302 回 service 落 ticket 与会话
 *     Cookie，之后接口只认 Cookie（实测挖掉 Cookie 的同一请求直接被 302 回 CAS 页）。
 *     → [client] 必须**自带 CookieJar 且跟随重定向**（与 `HttpClients.eas` 那套「不跟跳」
 *       正好相反，最快写法是 [Companion.newClient]）。
 *     → WebView 登录后取 Cookie 必须用 `https://gmsstu.zzu.edu.cn/stu/`：会话 Cookie
 *       `cn_com_southsoft_gmis_stu` 的 `Path=/stu`，用根路径 `getCookie` 取不到。
 *  2. 业务 URL 是 base64 包装：`/stu/{b64}`，其中
 *     `b64 = base64("{AppKey}{模块路径}#{session-guid}")` 且 `'=' → '_'`；
 *     AppKey 固定 "MyHeartWillGoOn"，guid 会话级随机（实测整段会话内不变）。
 *     **guid 就是会话 Cookie 的值**（2026-10-18 实测；当前站点 `/stu/` HTML 里已无
 *     `#uuid`，HTML 正则仅作旧版本兜底）。
 *  3. 课表形状是 **周区间 + 节次单值、无日期、无节次时刻**：
 *     「每周一条」用 [expandWeeks] 展开；节次只有「第N节」，没有时刻表。
 */
class GmsClient(
    private val client: OkHttpClient,
    private val json: Json,
) {

    // ── DTO。字段名与实测响应一字不差 ─────────────────────────────────────

    @Serializable
    data class TermDto(
        val value: String = "",
        val text: String = "",
        val selected: Boolean = false,
    )

    @Serializable
    data class TermListDto(val termcode: List<TermDto> = emptyList())

    @Serializable
    data class RowDto(
        val id: String? = null,
        val jcid: String = "",
        val mc: String = "",
        val sjbz: String? = null,
        val z1: String? = null,
        val z2: String? = null,
        val z3: String? = null,
        val z4: String? = null,
        val z5: String? = null,
        val z6: String? = null,
        val z7: String? = null,
        val xq: String? = null,
        val jsxm: String? = null,
        val xkfs: String? = null,
    )

    @Serializable
    data class TermTableDto(val rows: List<RowDto> = emptyList())

    @Serializable
    data class WeekLessonDto(
        val KCMC: String = "",

        /** 班级名；⚠️ 有的课这里放的是「课程号_班次」，用之前先按形态判断 */
        val BJMC: String? = null,

        /** 周次/节次文本，如「2-17周[连续],第7节,第8节」 */
        val ZS: String? = null,
        val DZ: String? = null,
        val ZH: String? = null,
    )

    @Serializable
    data class WeekDayDto(val zh: String = "", val bzkb: List<WeekLessonDto> = emptyList())

    @Serializable
    data class WeekTableDto(val bzkb: List<WeekDayDto> = emptyList())

    /** 课表里的一节课（已扁平化，风格对齐 EasClient.Session） */
    data class Session(
        /** 节次编号 jcid（1..11；99 =「无节次」占位行，已被过滤） */
        val period: Int,

        /** 「第1节」… */
        val periodName: String,

        /** 1..7，z1..z7 对应 周一..周日 */
        val weekday: Int,

        /** 周区间（如 10..17）；解析不出来为 null */
        val weeks: List<IntRange>?,
        val courseName: String,

        /** 课程号_班次（如 `135505702_001`）；无课程号的课为 null */
        val courseCode: String? = null,

        /** 班级名（如「主校区政治20班」），无课程号的课才有意义 */
        val className: String? = null,

        val teacher: String?,
        val room: String?,

        /** `##` 前缀：选课式课程 */
        val selective: Boolean = false,
    )

    // ── API ──────────────────────────────────────────────────────────────

    /** 学期下拉。`selected=true` 是当前学期，其 [TermDto.value] 传给 [fetchTermTable]。 */
    suspend fun fetchTerms(): List<TermDto> =
        getData(TermListDto.serializer(), route("pygl/xskbcx/kbcx_drop"), null, "学期列表").termcode

    /**
     * 拉整学期课表并扁平化成 [Session] 列表。
     * 一次请求拿全（实测 11 行 × 7 天），之后切周/展示全部本地算，不再发请求。
     */
    suspend fun fetchTermTable(termcode: String): List<Session> {
        val table = getData(
            TermTableDto.serializer(),
            route("pygl/xskbcx/loadKb"),
            mapOf("kblx" to "xs", "termcode" to termcode),
            "课表",
        )
        return rowsToSession(table.rows)
    }

    /** 本周课表；`bzkb[0..6].bzkb` 为周一~周日当周课程。 */
    suspend fun fetchWeekTable(): WeekTableDto =
        getData(WeekTableDto.serializer(), route("pygl/xskbcx/bzkb_load"), emptyMap(), "本周课表")

    /** RowDto 的 z1..z7 网格 → 一格一课的 [Session]。 */
    fun rowsToSession(rows: List<RowDto>): List<Session> {
        val out = ArrayList<Session>(rows.size)
        for (row in rows) {
            val period = row.jcid.toIntOrNull() ?: continue
            if (period <= 0 || period >= PERIOD_MAX) continue // jcid=99 是「无节次」占位行
            val cells = listOf(row.z1, row.z2, row.z3, row.z4, row.z5, row.z6, row.z7)
            cells.forEachIndexed { index, cell ->
                val entry = GmsScheduleParser.parseCell(cell) ?: return@forEachIndexed
                out += Session(
                    period = period,
                    periodName = row.mc,
                    weekday = index + 1,
                    weeks = GmsScheduleParser.parseWeeks(entry.weekText),
                    courseName = entry.courseName,
                    courseCode = entry.courseCode,
                    className = entry.className,
                    teacher = entry.teacher,
                    room = entry.room,
                    selective = entry.selective,
                )
            }
        }
        return out
    }

    /** 一节课落到具体教学周（喂给 CourseSessionEntity 的骨架） */
    data class WeekEntry(
        val weekIndex: Int,
        val weekday: Int,
        val period: Int,
        val courseName: String,
        val courseCode: String?,
        val className: String?,
        val teacher: String?,
        val room: String?,
    )

    /**
     * 本科课表每条记录自带 `weekIndex`（单周），研究生这边是「周区间」
     * （一条课覆盖 10-17 周），接周网格前先在本方法展开成每周一格。
     * 周次段缺失（实测数据未出现）的课直接不计入周网格。
     */
    fun expandWeeks(lessons: List<Session>): List<WeekEntry> {
        val out = ArrayList<WeekEntry>()
        for (lesson in lessons) {
            val ranges = lesson.weeks ?: continue
            for (range in ranges) for (week in range) {
                out += WeekEntry(
                    weekIndex = week,
                    weekday = lesson.weekday,
                    period = lesson.period,
                    courseName = lesson.courseName,
                    courseCode = lesson.courseCode,
                    className = lesson.className,
                    teacher = lesson.teacher,
                    room = lesson.room,
                )
            }
        }
        return out
    }

    // ── 会话与登录 ────────────────────────────────────────────────────────

    /**
     * 会话路由 guid —— 优先从会话 Cookie 的值取（站点当前版本）；
     * 旧版本页面把 `#uuid` 写在 HTML 里，保留正则兜底。
     */
    private var guid: String? = null

    /**
     * 会话是否存活：抓一次 `/stu/`，最终 URL 落回 CAS 登录页即未登录；
     * 成功时顺手把 guid 收好（[route] 复用）。网络错误照常上抛，不吞。
     */
    suspend fun isSessionAlive(): Boolean = try {
        // 清掉缓存，强制真发一次 /stu/；否则 withGuid() 会直接返回缓存的 guid，
        // 「检查会话」就永远显示有效。
        guid = null
        withGuid()
        true
    } catch (_: AppError.ReLoginRequired) {
        false
    }

    /** 打开 `/stu/`；成功时缓存 guid 并返回页面 HTML。 */
    private suspend fun withGuid(): String = withContext(Dispatchers.IO) {
        guid?.let { return@withContext it }
        val response = executeOrNull(Request.Builder().url(STU_HOME).get().build())
            ?: throw AppError.Network("无法连接研究生教务，请检查网络")
        response.use { r ->
            val html = r.body?.string().orEmpty()
            if (r.request.url.host != HOST_GMS || r.request.url.toString().contains(CAS_LOGIN_MARK)) {
                throw unlogin()
            }
            guid = GUID_RE.find(html)?.groupValues?.get(1)
                ?: guidFromCookie()
                ?: throw AppError.Parse("研究生教务页面缺少会话标识（可能改版了）")
            guid!!
        }
    }

    /**
     * 站点当前版本登录后的 HTML 里没有 `#uuid`，但会话 Cookie
     * `cn_com_southsoft_gmis_stu` 的值就是 guid（2026-10-18 实测）。
     * 从 CookieJar 读，取不到返回 null，交给旧版 HTML 正则或报错。
     */
    private fun guidFromCookie(): String? {
        val url = STU_HOME.toHttpUrlOrNull() ?: return null
        val cookies = client.cookieJar.loadForRequest(url).associate { it.name to it.value }
        return GmsCookie.guidOf(cookies)
    }

    private suspend fun route(modulePath: String): String =
        "$GMS_BASE/stu/" + encodeRoute(modulePath, withGuid())

    private fun unlogin() = AppError.ReLoginRequired("研究生教务未登录，请先完成账号登录")

    /**
     * 会话切换后清掉缓存的会话标识（guid）。
     *
     * 为什么这里没有账号密码登录：研究生系统只认网页 CAS 会话 Cookie（没有 JWT 可用），
     * 而 App 的底线是「只存令牌、不存密码」。所以登录交给 App 内置 WebView —— 用户在学校
     * 自己的页面上输密码，我方只把 CookieManager 里的会话 Cookie 经 GmsCookieJar.seed()
     * 灌进来，之后本类的接口请求会自动带上。切账号或会话过期后调本方法，下次请求重新取 guid。
     */
    fun resetSession() {
        guid = null
    }
    // ── 内部：单一请求通道（对齐 EasClient.getData 的职责） ───────────────

    private suspend fun <T> getData(
        serializer: KSerializer<T>,
        url: String,
        form: Map<String, String>?,
        what: String,
    ): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
        if (form == null) {
            builder.get()
        } else {
            val bodyBuilder = FormBody.Builder()
            form.forEach { (k, v) -> bodyBuilder.add(k, v) }
            builder.post(bodyBuilder.build())
        }
        val response = executeOrNull(builder.build())
            ?: throw AppError.Network("无法连接研究生教务，请检查网络")
        response.use {
            val text = it.body?.string().orEmpty()
            if (it.request.url.host != HOST_GMS || it.request.url.toString().contains(CAS_LOGIN_MARK)) {
                // 会话丢了的统一形状：任何接口都会被 302 到 CAS
                throw AppError.ReLoginRequired("研究生教务登录态已失效，请重新登录")
            }
            if (!it.isSuccessful) {
                throw AppError.Server(it.code, "研究生教务返回 HTTP ${it.code}（$what）")
            }
            try {
                json.decodeFromString(serializer, text)
            } catch (e: Exception) {
                throw AppError.Parse("研究生教务${what}响应无法解析（开头 120 字：${text.take(120)}）", e)
            }
        }
    }

    private fun executeOrNull(request: Request): Response? = try {
        client.newCall(request).execute()
    } catch (e: IOException) {
        null
    }

    companion object {
        /**
         * 与实测串逐字节比对过的路由编码：
         * 标准 base64（RFC 4648）后把 `=` 换成 `_`。这些模块路径（固定 AppKey + ASCII）
         * 的编码结果不会出现 `+`/`/`，不需要额外映射。单元测试覆盖。
         */
        fun encodeRoute(modulePath: String, guid: String): String {
            val plain = "$ROUTE_APPKEY$modulePath#$guid"
            return Base64.getEncoder()
                .encodeToString(plain.toByteArray(Charsets.US_ASCII))
                .replace('=', '_')
        }

        /** 独立 OkHttp client：跟重定向（CAS 登录链靠 302）+ 会话 CookieJar + 与 HttpClients 同一套超时 */
        fun newClient(jar: CookieJar = InMemoryCookieJar()): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .cookieJar(jar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private const val ROUTE_APPKEY = "MyHeartWillGoOn"
        private const val HOST_GMS = "gmsstu.zzu.edu.cn"
        private const val HOST_CAS = "cas.s.zzu.edu.cn"
        private const val CAS_LOGIN_MARK = "/cas/a/login"
        private const val GMS_BASE = "https://gmsstu.zzu.edu.cn"
        private const val CAS_BASE = "https://cas.s.zzu.edu.cn"
        private const val STU_HOME = "$GMS_BASE/stu/"
        private const val EXECUTION_RES =
            "name=\"execution\"[^>]*value=\"([^\"]+)|value=\"([^\"]+)\"[^>]*name=\"execution\""
        private const val PERIOD_MAX = 12 // 实测节次 1..11，99 为「无节次」占位

        private val GUID_RE = Regex(
            "#([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})",
        )

        /** 进程内 CookieJar：CAS(TGT) + gmsstu 的会话 Cookie 全存这里 */
        private class InMemoryCookieJar : CookieJar {
            private val store = HashMap<String, MutableList<Cookie>>()
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
                store[url.host] = cookies.toMutableList()
            }

            override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> =
                store[url.host].orEmpty()
        }
    }
}
