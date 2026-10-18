package com.zzupocket.app.data.gms

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 研究生教务（gmsstu）的会话 Cookie 处理。
 *
 * ## 为什么需要它
 * 那个系统**只认网页 CAS 会话 Cookie**（没有 JWT 可用），而 App 的底线是
 * 「只存令牌、不存密码」。所以登录交给内置 WebView —— 用户在**学校自己的页面**上输密码，
 * 我们只从 `CookieManager` 里取回它落下的会话 Cookie，交给这里的 [GmsCookieJar]。
 *
 * 这条链是 2026-10-17 与贡献者（Inspire2431）在真实会话里确认的：
 * `/stu` → 302 到 `cas.s.zzu.edu.cn/cas/a/login?service=…` → 登录后 302 带 ticket 回
 * → 落 gmsstu 会话 Cookie；之后接口只认它（把 Cookie 挖掉的同一请求会被 302 回 CAS）。
 */
object GmsCookie {

    /**
     * 研究生系统的会话 Cookie。
     *
     * 它的值就是路由用的 `session-guid`（2026-10-18 实测：登录后页面发出的 12 条
     * `/stu/{b64}` 请求解出来的 guid 与它逐字相同）。站点当前版本的 `/stu/` 与
     * `/stu/index` HTML 里已经没有 `#uuid`，所以 guid 优先从这枚 Cookie 取。
     */
    const val SESSION_COOKIE = "cn_com_southsoft_gmis_stu"

    private val GUID_VALUE_RE = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 把 `CookieManager.getCookie(url)` 那种 `k=v; k2=v2` 字符串拆成 Map。
     * **纯函数，便于单测**；解析不出任何键值对就返回空 Map（调用方据此判断"没会话"）。
     */
    fun parse(header: String?): Map<String, String> {
        if (header.isNullOrBlank()) return emptyMap()
        return header.split(';')
            .mapNotNull { part ->
                val t = part.trim()
                if (t.isEmpty()) return@mapNotNull null
                val eq = t.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val name = t.substring(0, eq).trim()
                val value = t.substring(eq + 1).trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
            .toMap()
    }

    /** 把 Map 拼回 `k=v; k2=v2`（写进存储用） */
    fun join(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /**
     * 从会话 Cookie 里取 `session-guid`；没有或形态不对返回 null。
     *
     * 调用方应保留 HTML 正则作为兜底：万一站点以后又改回把 guid 写进页面，
     * 两条路都能走。
     */
    fun guidOf(cookies: Map<String, String>): String? =
        cookies[SESSION_COOKIE]?.takeIf { GUID_VALUE_RE.matches(it) }
}

/**
 * 进程内会话 Cookie 存储：既可被 OkHttp 的请求流程读写，也可由 WebView 登录后**注入**。
 *
 * 注意研究生系统要求**跟随重定向**（CAS 登录链靠 302），所以配套的 client 与
 * `HttpClients.eas` 那套"不跟跳"正好相反。
 */
class GmsCookieJar : CookieJar {

    private val store = HashMap<String, MutableList<Cookie>>()

    /** 由 WebView 会话注入（humble：只保留 host/path/name/value 四项，够用） */
    fun seed(host: String, cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        store[host] = cookies.map { (name, value) ->
            Cookie.Builder()
                .domain(host)
                .path("/")
                .name(name)
                .value(value)
                .build()
        }.toMutableList()
    }

    /** 已注入的 cookie 条数（0 = 还没有会话） */
    fun size(): Int = store.values.sumOf { it.size }

    /** 清空（退出登录时用） */
    fun clear() = store.clear()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        // 合并而不是整体替换：服务端有时只在响应里回一个新 cookie（例如 route），
        // 整体替换会把 WebView 注入的会话 cookie 一并丢掉。
        val merged = store[url.host].orEmpty().associateBy { it.name }.toMutableMap()
        cookies.forEach { merged[it.name] = it }
        store[url.host] = merged.values.toMutableList()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
}
