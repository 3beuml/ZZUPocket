package com.zzupocket.app.ui.diag

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zzupocket.app.BuildConfig
import com.zzupocket.app.data.gms.GmsProbe

/**
 * 研究生系统探测界面：**登录器 + 记录器**。
 *
 * 为什么是 WebView 而不是原生登录：那个系统走学校统一身份认证的 CAS 跳转，
 * 让用户在**学校自己的页面**上输密码/短信码，我们只负责读它登录后自己发出的接口请求 ——
 * 密码不经过我们，令牌也不用我们保管。
 *
 * 记录原理见 [GmsProbe]：向页面注入脚本钩住 fetch / XHR，通过 console.log 回传。
 *
 * ## 两个踩过的坑（别再改回去）
 * 1. **按钮必须放顶部**。放底部时，用户一输入（键盘弹起）按钮就被顶出屏幕，点不到。
 * 2. **`loadUrl` 必须等视图测量出高度之后再调**。在 `factory` 里直接 load，页面会按
 *    "视口高度=0"完成首屏布局，整条高度链塌成 0，表现是画面异常/空白
 *    （LibraryScreen 里踩过同一个坑，注释也在那儿）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GmsProbeDialog(
    onDismiss: () -> Unit,
    onReport: (String) -> Unit,
    /** true = 当登录器用（课表页进来），false = 当自检用（诊断页进来） */
    loginMode: Boolean = false,
) {
    val recorder = remember { GmsProbe.Recorder() }
    val webRef = remember { mutableStateOf<WebView?>(null) }
    // 登录模式直接从 CAS 登录页进：从 /stu 进在部分网络会无限重定向（见 GmsProbe.CAS_SERVICE_ENTRY 注释）
    val entryUrl = if (loginMode) GmsProbe.CAS_SERVICE_ENTRY else GmsProbe.ENTRY
    var shownUrl by remember { mutableStateOf(entryUrl) }
    var hitCount by remember { mutableIntStateOf(0) }
    var navCount by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    /**
     * 只清掉这两个站点的 cookie。
     *
     * 为什么需要：上次半途而废的登录会留下 CAS/gmsstu 的旧会话 —— CAS 以为你已登录就把你
     * 踢回研究生系统，那边又认为没票，再踢回 CAS …… 循环几次后 WebView 报
     * `ERR_TOO_MANY_REDIRECTS`，表现就是"网页无法打开"（2026-10-17 实测就是这个）。
     * 不能 removeAllCookies()：那会连图书馆 WebView 的会话一起清掉。
     */
    fun clearHostCookies(host: String) {
        val cm = CookieManager.getInstance()
        val raw = cm.getCookie("https://$host") ?: return
        raw.split(';').forEach { part ->
            val name = part.substringBefore('=').trim()
            if (name.isNotEmpty()) cm.setCookie("https://$host/", "$name=; Max-Age=0; path=/")
        }
        cm.flush()
    }

    fun resetSessions() {
        clearHostCookies("cas.s.zzu.edu.cn")
        clearHostCookies(com.zzupocket.app.data.gms.GmsScheduleSource.HOST)
        loadError = null
    }

    /** 登录模式不注入探针钩子：它覆盖 fetch/XHR，登录页用不上，反而可能干扰 CAS 前端流程 */
    fun inject(view: WebView?) {
        if (loginMode) return
        view?.evaluateJavascript(GmsProbe.JS_HOOK, null)
    }

    fun snapshotCookies(url: String) {
        runCatching { recorder.noteCookies(url, CookieManager.getInstance().getCookie(url)) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── 顶部：标题 + 进度 + 按钮（按钮放顶部，键盘弹起也点得到）──────
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (loginMode) "登录研究生系统" else "研究生系统自检", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "已记录请求 $hitCount 条 · 跳转 $navCount 次 · ${if (ready) "页面已加载" else "加载中…"}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "关闭",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Text(
                        text = shownUrl,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { inject(webRef.value) },
                            modifier = Modifier.weight(1f),
                        ) { Text("补注入") }
                        OutlinedButton(
                            onClick = { webRef.value?.loadUrl(entryUrl) },
                            modifier = Modifier.weight(1f),
                        ) { Text("重新加载") }
                        OutlinedButton(
                            onClick = {
                                loadError = null
                                if (!loginMode) resetSessions()
                                webRef.value?.loadUrl(com.zzupocket.app.data.gms.GmsProbe.ENTRY)
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("清会话重试") }
                        Button(
                            onClick = { onReport(recorder.report()) },
                            modifier = Modifier.weight(1.4f),
                        ) { Text(if (loginMode) "完成登录" else "生成报告") }
                    }
                    Text(
                        if (loginMode) {
                            "用你的研究生账号登录 —— 密码在学校自己的页面上输，我们不接触、也不保存。" +
                                "登录成功、回到研究生系统页面之后，点下面的「完成登录」就能拉课表。"
                        } else {
                            "用你自己的研究生账号登录（密码在学校自己的页面上输）。进去后翻到「课表/我的课程」，" +
                                "再点「生成报告」。报告里只有接口路径与字段名，不含令牌、姓名、学号。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── 下方：网页本体 ─────────────────────────────────────────
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            // ⚠️ WebView 的默认 UA 里带 "; wv" 标记（"内嵌浏览器"特征）。
                            // 实测：同一个 CAS 地址，系统浏览器能打开、我们的 WebView 却报
                            // ERR_TOO_MANY_REDIRECTS —— 去掉这个标记就恢复了正常浏览器身份。
                            settings.userAgentString = settings.userAgentString
                                .replace("; wv", "")
                                .replace(Regex(" Version/\\d+\\.\\d+"), "")
                            android.util.Log.i(
                                com.zzupocket.app.data.gms.GmsProbe.TAG,
                                "UA=" + settings.userAgentString,
                            )
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                            // 这个站点入口会在 HTTP/HTTPS 之间跳，不放混合内容会白屏
                            settings.mixedContentMode =
                                android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            setBackgroundColor(android.graphics.Color.WHITE)
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            // ⚠️ 必须等测量出高度再 load（见类注释第 2 条）
                            fun startOnce(how: String) {
                                if (ready || width <= 0 || height <= 0) return
                                ready = true
                                // 清掉上次残留的站点会话，避免 CAS ↔ 研究生系统来回重定向
                                if (loginMode) {
                                    // 彻底清空本 App 的 WebView cookie 再加载。
                                    // 为什么：CAS 上一次半途而废的会话会让 CAS ↔ 研究生系统互相重定向
                                    // （实测 ERR_TOO_MANY_REDIRECTS）；而按名字 setCookie 空值清理不可靠，
                                    // 残留 cookie 仍在。2026-10-17 实测：刚装完（cookie 罐是空的）能打开，
                                    // 一旦被污染就打不开。
                                    val cm = CookieManager.getInstance()
                                    cm.removeAllCookies {
                                        cm.flush()
                                        post { loadUrl(entryUrl) }
                                    }
                                } else {
                                    resetSessions()
                                    loadUrl(entryUrl)
                                }
                            }
                            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> startOnce("layout") }
                            post { startOnce("post") }

                            // 周期性补注入：实测只注入一次容易漏（页面自己的脚本可能在
                            // onPageStarted 之后才装上 XHR，SPA/iframe 更明显），
                            // 所以每隔 1.5 秒补一次。hook 里有 __gmsProbeInstalled 幂等保护，
                            // 重复注入不会重复上报。
                            val wv = this
                            val reinject = object : Runnable {
                                override fun run() {
                                    inject(wv)
                                    wv.postDelayed(this, 1500)
                                }
                            }
                            postDelayed(reinject, 800)

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    url?.let {
                                        recorder.noteNavigation(it)
                                        navCount++
                                        shownUrl = it
                                        snapshotCookies(it)
                                    }
                                    inject(view)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    url?.let {
                                        recorder.noteNavigation(it)
                                        shownUrl = it
                                        snapshotCookies(it)
                                    }
                                    inject(view)
                                    // 这个 WebView 里 CSS 的 vh 恒等于 0（见
                                    // docs/interfaces/图书馆WebView灰屏.md）：CAS 登录页靠 100vh 布局，
                                    // 不修就会出现「账号密码登录」和「修改安全手机」叠在一起。
                                    com.zzupocket.app.ui.web.WebViewVhFix.install(view)
                                }
                                /**
                                 * 加载失败必须留证：WebView 自带的错误页只会说"网页无法打开"，
 * 不告诉我们是 DNS、超时、证书还是被拦。
                                 */
                                override fun onReceivedError(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?,
                                    error: android.webkit.WebResourceError?,
                                ) {
                                    val code = error?.errorCode ?: 0
                                    val desc = error?.description?.toString().orEmpty()
                                    val url = request?.url?.toString().orEmpty()
                                    val main = request?.isForMainFrame == true
                                    android.util.Log.w(com.zzupocket.app.data.gms.GmsProbe.TAG, "LOAD-ERR code=$code main=$main desc=$desc url=$url")
                                    if (main) loadError = "加载失败 code=$code：$desc"
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?,
                                    response: android.webkit.WebResourceResponse?,
                                ) {
                                    val status = response?.statusCode ?: 0
                                    val url = request?.url?.toString().orEmpty()
                                    android.util.Log.w(com.zzupocket.app.data.gms.GmsProbe.TAG, "HTTP-ERR $status url=$url")
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                                    if (recorder.recordConsole(msg.message())) {
                                        hitCount = recorder.hitCount()
                                        return true
                                    }
                                    return false
                                }
                            }
                            webRef.value = this
                        }
                    },
                    update = { webRef.value = it },
                )
            }
        }
    }
}
