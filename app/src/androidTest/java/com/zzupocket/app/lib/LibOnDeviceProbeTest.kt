package com.zzupocket.app.lib

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.auth.TokenStore
import com.zzupocket.app.data.lib.LIB_CODE_NO_SESSION
import com.zzupocket.app.data.lib.LIB_CODE_OK
import com.zzupocket.app.data.lib.LibClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * 图书馆会话 + 记录接口的**真机自检**（instrumentation，人不用碰任何 UI）。
 *
 * 干三件事：
 *  1. 若已落过会话 → 直接用；否则起一个无头 WebView 打开 H5，
 *     盯着 sessionStorage 里的 token（CAS 的 TGT cookie 还有效时会自动完成登录）。
 *  2. 会话到手 → 同一接口分「token+cookie / 只 token / 只 cookie」各打一发，
 *     确认服务端认哪条通道。
 *  3. 把记录条目的真实字段与时间形态 dump 出来（热力图日期字段以实物为准）。
 *
 * 结果只打 **logcat（tag = libraryProbe）**，不打印任何 token / cookie 的值。
 */
@RunWith(AndroidJUnit4::class)
class LibOnDeviceProbeTest {

    @SuppressLint("SetJavaScriptEnabled")
    @Test
    fun captureSessionAndDumpRecords() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        AppGraph.init(context)
        val store = TokenStore(context)
        val source = AppGraph.libSessionSource

        var token = store.libToken
        var cookie = store.libCookie
        log(
            "已有的会话：token=${token?.length ?: 0} 字符，" +
                "cookie 项数=${countCookies(cookie)}，捕获时间=" +
                (if (store.libTokenAt > 0L) fmt(store.libTokenAt) else "从未"),
        )

        // ── 1. 已有会话先试一发；10001 = 过期 → 清掉，走无头 WebView 重登录 ──
        var seatBody = """{"type":"${LibClient.TYPE_SEAT_NORMAL}","page":1,"limit":10}"""
        if (!token.isNullOrBlank()) {
            val first = post("/v4/member/seat", seatBody, withCookie = true, withToken = true)
            log("存量会话试 /v4/member/seat → code=${codeOf(first.second)}")
            if (codeOf(first.second) == LIB_CODE_NO_SESSION) {
                log("存量会话已失效（图书馆会话有效期较短）→ 清掉，重新走登录链")
                source.clear()
                token = null
                cookie = null
            }
        }

        if (token.isNullOrBlank()) {
            log("起无头 WebView 打开 H5 等自动登录（最长 90 秒；CAS 的 TGT cookie 有效时全自动）…")
            token = captureTokenFromWebView()
            cookie = runCatching { CookieManager.getInstance().getCookie(LibClient.COOKIE_URL) }.getOrNull()
            if (token == null) {
                log("!! 90 秒里没等到 token —— CAS 的 SSO cookie 也失效了，需要人在 WebView 里输一次密码。")
                log("   处理：请在 App 的「图书馆」页手动登录一次（登录后 App 自动抓会话），")
                log("         然后重跑本测试。")
                return
            }
            source.onWebViewLogin(token, cookie)
            log("新会话捕获成功并已落盘（token ${token.length} 字符，cookie=${!cookie.isNullOrBlank()}）")
        }

        source.refreshCookieFromWebView()
        val sessionNow = source.current()
        log(
            "当前会话：token=${sessionNow.token?.length ?: 0} 字符，" +
                "cookie 项数=${countCookies(sessionNow.cookie)}",
        )

        // ── 2. 通道对照 ──────────────────────────────────────────────────
        val dual = post("/v4/member/seat", seatBody, withCookie = true, withToken = true)
        log("A1 token+cookie → HTTP ${dual.first}, code=${codeOf(dual.second)}")
        val tokenOnly = post("/v4/member/seat", seatBody, withCookie = false, withToken = true)
        log("A2 只token      → HTTP ${tokenOnly.first}, code=${codeOf(tokenOnly.second)}")
        val cookieOnly = post("/v4/member/seat", seatBody, withCookie = true, withToken = false)
        log("A3 只cookie     → HTTP ${cookieOnly.first}, code=${codeOf(cookieOnly.second)}")
        val verdict = when {
            codeOf(dual.second) == LIB_CODE_OK && codeOf(tokenOnly.second) == LIB_CODE_OK -> "token 通道足够"
            codeOf(dual.second) == LIB_CODE_OK && codeOf(cookieOnly.second) == LIB_CODE_OK -> "cookie 通道足够"
            codeOf(dual.second) == LIB_CODE_OK -> "需要双通道"
            else -> "两个通道都没打通"
        }
        log("→ 通道结论：$verdict")

        // ── 3. 实物字段 dump（token + cookie 全带）────────────────────────
        if (codeOf(dual.second) == LIB_CODE_OK) {
            dump("v4_member_seat 座位记录", dual.second)
            dump(
                "v4_space_my_wait 待处理",
                post("/v4/space/my_wait", """{"page":1,"limit":10}""", true, true).second,
            )
            dump(
                "v4_seminar_books 空间记录",
                post("/v4/seminar/books", """{"type":"1","page":1,"limit":10}""", true, true).second,
            )
            dump(
                "v4_member_seatRenege 座位违约",
                post("/v4/member/seatRenege", seatBody, true, true).second,
            )
            dump(
                "v4_seminar_reneges 空间违约",
                post("/v4/seminar/reneges", """{"type":"1","page":1,"limit":10}""", true, true).second,
            )
            val wide = post("/v4/member/seat", """{"type":"1","page":1,"limit":100}""", true, true)
            log("limit=100 → 响应 ${wide.second.length} 字符")
        }
        log("===== 自检结束 =====")
    }

    /** 一次 instrumentation 任务（主线程块） */
    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /** WebView 侧轮询：instrumentation 线程每 2 秒发一次 evaluateJavascript（主线程回调），拿到 token 或超时返回 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun captureTokenFromWebView(): String? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val captured = AtomicReference<String?>(null)
        onMain {
            webViewRef = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean {
                        log("webview 跳转 → ${url.orEmpty().take(120)}")
                        return false
                    }
                }
                loadUrl(LibClient.BASE + "/h5/")
            }
        }
        val start = System.currentTimeMillis()
        while (captured.get() == null && System.currentTimeMillis() - start < 90_000L) {
            Thread.sleep(2000)
            val webView = webViewRef ?: break
            onMain {
                webView.evaluateJavascript(LibClient.TOKEN_JS) { result ->
                    val t = result.orEmpty().trim('"')
                    if (t.length > 8 && t != "null") captured.set(t)
                }
            }
        }
        return captured.get()
    }

    /** WebView 只在主线程碰；测试线程只经 [onMain] 间接操作 */
    private var webViewRef: WebView? = null

    private fun post(path: String, body: String, withCookie: Boolean, withToken: Boolean): Pair<Int, String> =
        try {
            runBlocking {
                AppGraph.libClient.rawPost(path, body, withCookie = withCookie, withToken = withToken)
            }
        } catch (e: Throwable) {
            0 to "（请求失败：${e.message}）"
        }

    private fun codeOf(text: String): Int = try {
        ((AppGraph.json.parseToJsonElement(text) as? JsonObject)?.get("code") as? JsonPrimitive)
            ?.content?.toIntOrNull() ?: -1
    } catch (_: Exception) {
        -1
    }

    /** 把 data.data 第一条的字段（字段名=值形态）打出来；热力图日期一并试解 */
    private fun dump(title: String, text: String) {
        val root = runCatching { AppGraph.json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        val payload = root?.get("data") as? JsonObject
        val items = payload?.get("data") as? JsonArray
        if (items == null) {
            log("--- $title：没有 data.data（code=${codeOf(text)}，原文 ${text.take(280)}）")
            return
        }
        log("")
        log("--- $title ---")
        log("本页 ${items.size} 条，total=${payload["total"]}")
        val first = items.firstOrNull() as? JsonObject
        if (first == null) {
            log("列表为空")
            return
        }
        first.forEach { (k, v) ->
            val shape = when (v) {
                is JsonPrimitive -> "「" + v.content.take(48) + "」"
                is JsonArray -> "数组[${v.size}]"
                is JsonObject -> "对象{${v.keys.sorted().joinToString(",")}}"
                else -> v.toString()
            }
            log("  $k = $shape")
        }
        val day = LibClient.firstReservationDay(first)
        log("热力图日期解析 → ${day?.toString() ?: "没解析出"}")
    }

    private fun fmt(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(at))

    private fun countCookies(cookie: String?): Int =
        cookie?.split(';')?.count { it.trim().contains('=') } ?: 0

    private fun log(s: String) {
        android.util.Log.i(TAG, s)
    }

    private companion object {
        const val TAG = "libraryProbe"
    }
}
