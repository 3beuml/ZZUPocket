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
                // 顶部：说明 + 当前进度
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("研究生系统自检", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "用你自己的研究生账号登录（密码在学校自己的页面上输，我们不接触）。" +
                            "登录后进「课表 / 我的课程」页面翻一下，然后点下面的「生成报告」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "已记录请求 $hitCount 条 · 跳转 $navCount 次",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = shownUrl,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    url?.let {
                                        recorder.noteNavigation(it)
                                        navCount++
                                        shownUrl = it
                                        snapshotCookies(it)
                                    }
                                    // 尽早注入：脚本钩子必须在页面自己的 JS 之前装好
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
                                    val line = msg.message()
                                    if (recorder.recordConsole(line)) {
                                        hitCount = recorder.hitCount()
                                        return true
                                    }
                                    return false
                                }
                            }
                            loadUrl(GmsProbe.ENTRY)
                            webRef.value = this
                        }
                    },
                    update = { it -> webRef.value = it },
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { inject(webRef.value) },
                        modifier = Modifier.weight(1f),
                    ) { Text("补注入") }
                    OutlinedButton(
                        onClick = {
                            webRef.value?.let { it.loadUrl(GmsProbe.ENTRY) }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("重新加载") }
                    Button(
                        onClick = { onReport(recorder.report()) },
                        modifier = Modifier.weight(1.4f),
                    ) { Text("生成报告") }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}
