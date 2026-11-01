package com.zzupocket.app.data.lib

import android.webkit.CookieManager
import com.zzupocket.app.data.auth.TokenStore

/**
 * 图书馆会话的**双通道快照**：token（`authorization: bearer<token>`）与 Cookie 头。
 * 两个都可为空 —— 自检正是要看"哪个通道在起作用"。
 */
data class LibSession(
    val token: String? = null,
    val cookie: String? = null,
) {
    fun isEmpty(): Boolean = token.isNullOrBlank() && cookie.isNullOrBlank()
}

/**
 * 图书馆会话的来源（与研究生课表的 `GmsScheduleSource` 同一套设计）：
 *
 * 登录发生在**学校自己的页面**上 —— App 的底线是「只存令牌、不存密码」，所以
 * WebView 打开 `LibraryScreen` 时（或用户手动登录一次后），SPA 会把 member token
 * 放进 sessionStorage、`PHPSESSID` 落进 CookieManager；这里在探测循环里把它们
 * **原样取回**并落进加密存储，之后的原生请求就不再打开 WebView。
 */
class LibSessionSource(private val store: TokenStore) {

    /** WebView 里捕获到会话时调用。token 不能为空；cookie 可选。@return 是否真的装进了会话 */
    fun onWebViewLogin(token: String?, cookieHeader: String?): Boolean {
        if (token.isNullOrBlank()) return false
        // token 依旧是同一枚时别反复落盘（SPA 每次眼界打开都会看到它）
        if (token != store.libToken) {
            store.libToken = token
            store.libTokenAt = System.currentTimeMillis()
        }
        if (!cookieHeader.isNullOrBlank()) store.libCookie = cookieHeader
        return true
    }

    /** 当前会话快照（给 [LibClient] 的 session 供给器） */
    fun current(): LibSession = LibSession(token = store.libToken, cookie = store.libCookie)

    fun hasSession(): Boolean = !store.libToken.isNullOrBlank() || !store.libCookie.isNullOrBlank()

    /** [store.libTokenAt]：捕获时间（毫秒，0 = 从没捕获） */
    fun capturedAt(): Long = store.libTokenAt

    /** WebView 正开着时顺路补抓 cookie（token 只在登录后变化） */
    fun refreshCookieFromWebView() {
        val header = runCatching { CookieManager.getInstance().getCookie(LibClient.COOKIE_URL) }.getOrNull()
        if (!header.isNullOrBlank()) store.libCookie = header
    }

    /** 退出登录时一并清掉（会话落在 TokenStore，auth.logout 的 clearAll 天然会清） */
    fun clear() {
        store.libToken = null
        store.libTokenAt = 0L
        store.libCookie = null
    }
}
