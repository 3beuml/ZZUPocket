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
        if (cookies.isNotEmpty()) store[url.host] = cookies.toMutableList()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
}
