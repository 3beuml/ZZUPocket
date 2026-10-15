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
) {
    val recorder = remember { GmsProbe.Recorder() }
    val webRef = remember { mutableStateOf<WebView?>(null) }
    var shownUrl by remember { mutableStateOf(GmsProbe.ENTRY) }
    var hitCount by remember { mutableIntStateOf(0) }
    var navCount by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }

    fun inject(view: WebView?) {
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
                            Text("研究生系统自检", style = MaterialTheme.typography.titleMedium)
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
                            onClick = { webRef.value?.loadUrl(GmsProbe.ENTRY) },
                            modifier = Modifier.weight(1f),
                        ) { Text("重新加载") }
                        Button(
                            onClick = { onReport(recorder.report()) },
                            modifier = Modifier.weight(1.4f),
                        ) { Text("生成报告") }
                    }
                    Text(
                        "用你自己的研究生账号登录（密码在学校自己的页面上输）。进去后翻到「课表/我的课程」，" +
                            "再点「生成报告」。报告里只有接口路径与字段名，不含令牌、姓名、学号。",
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
                                loadUrl(GmsProbe.ENTRY)
                            }
                            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> startOnce("layout") }
                            post { startOnce("post") }

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
