package com.zzupocket.app.data.gms

import com.zzupocket.app.core.AppError
import com.zzupocket.app.data.auth.TokenStore

/**
 * 研究生课表的数据源（gmsstu / 新研究生系统）。
 *
 * ## 它在架构里的位置
 * 本科走 [com.zzupocket.app.data.eas.EasClient]（JWT 裸令牌）；研究生走这里（网页会话 Cookie）。
 * 两条链路的**数据源不同**，靠学籍判定分流（见 `domain.AcademicTrack`：本科教务认令牌=本科，
 * 被拒=非本科）。客户端与解析器由贡献者 Inspire2431 在真实会话里逆出（PR #3）。
 *
 * ## 会话怎么来（关键设计）
 * 那个系统只认**网页 CAS 会话 Cookie**，没有 JWT。为了守住"只存令牌、不存密码"的底线，
 * 登录放在 App 内置 WebView 里：用户在学校自己的页面上输密码 → 我们只取回它落下的会话
 * Cookie（[onWebViewLogin]）→ 落进加密存储并灌给 [GmsCookieJar]。
 *
 * ## 还没做的一步
 * 落库（`source = "gms"`）需要把"周次区间 + 星期 + 节次"展开成具体日期，那要用学期起止
 * 日期（gms 侧不提供），属于下一步；本类目前只负责"取回并解析成 [GmsClient.WeekEntry]"。
 */
class GmsScheduleSource(
    private val gms: GmsClient,
    private val jar: GmsCookieJar,
    private val store: TokenStore,
) {

    /** 启动时把上次存的会话灌回 CookieJar（没有就什么都不做） */
    fun restore() {
        val saved = GmsCookie.parse(store.gmsCookie)
        if (saved.isEmpty()) return
        jar.seed(HOST, saved)
        gms.resetSession()
    }

    /**
     * WebView 登录成功后调用：注入会话 Cookie 并落盘。
     * @param cookieHeader `CookieManager.getCookie(COOKIE_URL)` 的原样字符串
     * @return 是否真的拿到了会话（false = 没登录成功，别当成功用）
     */
    fun onWebViewLogin(cookieHeader: String?): Boolean {
        val parsed = GmsCookie.parse(cookieHeader)
        if (parsed.isEmpty()) return false
        jar.seed(HOST, parsed)
        store.gmsCookie = GmsCookie.join(parsed)
        store.gmsCookieAt = System.currentTimeMillis()
        gms.resetSession()
        return true
    }

    /** 退出登录：清内存与存储里的会话（登录状态卡片据此回到"未登录"） */
    fun clear() {
        jar.clear()
        store.gmsCookie = null
        store.gmsCookieAt = 0L
        gms.resetSession()
    }

    /** 上次登录研究生系统的时间（毫秒；0 = 从没登录过） */
    fun loginAt(): Long = store.gmsCookieAt

    fun hasSession(): Boolean = jar.size() > 0

    /** 会话是否还活着（会真发一次请求；未登录返回 false，网络问题照常上抛） */
    suspend fun sessionAlive(): Boolean = try {
        gms.isSessionAlive()
    } catch (_: AppError.ReLoginRequired) {
        false
    }

    /** 学期列表（`selected=true` 是当前学期） */
    suspend fun terms(): List<GmsClient.TermDto> {
        requireSession()
        return gms.fetchTerms()
    }

    /**
     * 某学期的整学期课表，已按"一周一条"展开。
     * @param termCode 学期下拉里的 `value`（如 `20261`）
     */
    suspend fun weekEntries(termCode: String): List<GmsClient.WeekEntry> {
        requireSession()
        val sessions = gms.fetchTermTable(termCode)
        return gms.expandWeeks(sessions)
    }

    /**
     * 本周课表（服务端按当前周返回）。
     *
     * 本周接口给的是「7 个天槽，每个槽里一个课列表」，字段是 `KCMC`（课程名）/`ZS`（周次节次文本，
     * 如「第7节,第8节」）/`DZ`（地点）—— 星期来自天槽的 `zh`（"周一"…"周日"），节次从 `ZS` 取。
     * 所以这里压平成 [GradLesson]，UI 直接能用。
     *
     * ⚠️ 星期**按 `zh` 解析，不按列表下标**：早期实现用 `index + 1` 当星期，一旦服务端
     * 天槽顺序不是"周一打头"（或某天缺失），整周键就会平移，反推周次时精确匹配不上，
     * 进而挑错周（2026-10-20 的第 7 周被判成第 8 周，怀疑与这类平移有关）。
     */
    suspend fun currentWeek(): List<GradLesson> {
        requireSession()
        return gms.fetchWeekTable().bzkb.flatMapIndexed { index, day ->
            // zh 解析不出来才退回下标（保底：至少不会丢课）
            val weekday = weekdayOf(day.zh) ?: (index + 1).coerceIn(1, 7)
            day.bzkb.mapNotNull { l ->
                val period = PERIOD_RE.find(l.ZS.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                val name = l.KCMC.trim().ifEmpty { return@mapNotNull null }
                GradLesson(
                    weekday = weekday,
                    period = period,
                    courseName = name,
                )
            }
        }
    }

    /**
     * "周一"…"周日" / "星期一"… → 1..7；认不出返回 null。
     * 只认单个汉字，避免"周日"与"周天"之类的写法差异。
     */
    private fun weekdayOf(zh: String): Int? {
        val text = zh.trim()
        if (text.isEmpty()) return null
        val ch = text.firstOrNull { it in WEEKDAY_CHARS } ?: return null
        return WEEKDAY_CHARS.indexOf(ch) + 1
    }

    /** 没会话就直接给出"该去哪登录"的提示，别让上层拼文案 */
    private fun requireSession() {
        if (!hasSession()) {
            throw AppError.ReLoginRequired(
                "研究生教务需要先登录一次：在课表页点「登录研究生系统」完成登录",
            )
        }
    }

    companion object {
        const val HOST = "gmsstu.zzu.edu.cn"

        /**
         * WebView 登录后从 CookieManager 取会话的 URL。
         *
         * 会话 Cookie `cn_com_southsoft_gmis_stu` 的 `Path=/stu`，用根路径
         * `https://gmsstu.zzu.edu.cn` 取不到（2026-10-18 实测）。
         */
        const val COOKIE_URL = "https://" + HOST + "/stu/"

        /** 落库时用这个来源名（与本科的 `"eas"` 区分，课表页按它标注来源） */
        const val SOURCE = "gms"

        /** 「第7节」→ 7 */
        private val PERIOD_RE = Regex("第\\s*(\\d{1,2})\\s*节")

        /** 星期汉字，下标 +1 就是 weekday（周一=1 … 周日=7） */
        private const val WEEKDAY_CHARS = "一二三四五六日"
    }
}

/**
 * 研究生课表的**扁平展示单元**（本周视图用）。
 *
 * 为什么单独建一个模型：研究生系统那边的原始 DTO 把星期放在"天槽下标"里、节次塞在
 * `ZS` 文本里（见 `GmsScheduleSource.currentWeek()`），直接给 UI 会很难读。
 */
data class GradLesson(
    /** 1..7 = 周一..周日 */
    val weekday: Int,
    /** 第几节（1 起） */
    val period: Int,
    val courseName: String,
)
