package com.zzupocket.app.ui.library

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zzupocket.app.BuildConfig
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream

/**
 * 图书馆（形态 1：内嵌学校自己的网页）。
 *
 * 这一页踩过的坑，按踩到的顺序：
 *
 * 1. **只能走明文 HTTP**：校外 443 被校园网边界拒绝 → network_security_config 只对它放行。
 * 2. **index.html 引了国外 CDN（unpkg/vconsole）**：国内直连不上会一直等，把页面拖死
 *    → 这些域名直接拦掉并返回空脚本。
 * 3. **最阴的一个：视口高度 = 0 时完成首屏布局**。
 *    WebView 在 Compose 里是"先创建、后测量"的，如果在布局之前就 loadUrl，
 *    页面（Vue 单页）会在这时候完成首次布局，把整条百分比高度链算成 0：
 *      html / body / #app / .MobileLayout 全是 0 高
 *    内容其实都在（元素几何正常），但它们从 0 高的祖先里溢出、又被 #app 的
 *    overflow:hidden 整片裁掉 → 屏幕上只剩 html 的背景色，看起来就是"灰屏/黑屏"。
 *    修法：**等视图真的测量出高度再 loadUrl**（见 startOnce）。
 * 4. 加载期间页面本来就是白的，直接看就是一片空白、很容易以为卡死了
 *    → 盖一层"正在打开"的提示，等页面真的渲染出文字再收起来（见 READY_JS）。
 *
 * 关键事件都写进日志（tag=LibraryWeb），再出问题可以直接从 logcat 看出来。
 */
private const val LIBRARY_URL = "http://order.lib.zzu.edu.cn/h5/"
private const val TAG = "LibraryWeb"

/** 不需要、且国内直连不稳定的第三方域名（拦掉，避免拖死页面） */
private val BLOCKED_HOSTS = listOf("unpkg.com", "cdn.jsdelivr.net", "fonts.googleapis.com")

/**
 * 页面状态检测 + 立即自愈（加载期每 0.25 秒一次，稳定后每 2 秒当看门狗）。
 *
 * 实测结论（logcat 里 probe#N 那几行）：
 *  - 这个页面在 WebView 里会"DOM 全部挂载好、文字也有，但 html/body/#app 的高度是 0"，
 *    内容从 0 高的祖先溢出、又被 #app 的 overflow:hidden 裁掉 → 屏幕上只剩 html 背景色（灰屏）。
 *  - 触发 resize **不能**解决；把 html/body/#app 的高度用**像素值**顶成视口高度**立刻**解决
 *    （日志：force-height 之后 600ms 就 ready:746）。
 *  - 所以这里一旦发现"有文字但高度≈0"就当场自愈，不再干等。
 *    像素值来自 window.innerHeight（vh/% 都不行，因为出问题的正是百分比高度的解析基准）。
 */
private const val PROBE_JS = """
(function(){
  try{
    var a=document.querySelector('#app');
    if(!a) return 'noapp';
    var t=(a.innerText||'').replace(/\s+/g,'');
    var h=Math.round(a.getBoundingClientRect().height);
    if(h>50 && t.length>8) return 'ready:'+h;
    if(t.length>0 && h<50){
      var vh=window.innerHeight;
      if(vh>50){
        var de=document.documentElement,b=document.body;
        de.style.height=vh+'px';
        if(b) b.style.height=vh+'px';
        a.style.height=vh+'px';
        window.dispatchEvent(new Event('resize'));
        return 'repaired->'+vh;
      }
    }
    return 'wait:h='+h+',txt='+t.length;
  }catch(e){ return 'ERR '+e.message; }
})()
"""

/** 补一发 resize：真实浏览器加载完后地址栏收起会触发 resize，页面借机重量一次视口 */
private const val NUDGE_JS = """
(function(){
  try{
    window.dispatchEvent(new Event('resize'));
    if(window.visualViewport) window.visualViewport.dispatchEvent(new Event('resize'));
    return 'nudged';
  }catch(e){ return 'ERR '+e.message; }
})()
"""

/**
 * **把页面里所有 `vh` 换成像素值** —— 修复"使用须知看不到、无法预约座位"的根因。
 *
 * 实测（真机 + CDP 逐条量出来的，别再猜）：
 *  - 这个 WebView 里 **`vh` 单位恒等于 0**：`height:100vh` / `50vh` / `calc(100vh - 68px)` 全部算成 **0px**，
 *    而 `window.innerHeight=746`、`document.documentElement.clientHeight=747`、
 *    `position:fixed;height:100%` 的元素也确实是 747 —— 也就是只有 `vh` 坏掉（`vw`/`px`/`%` 都正常）。
 *  - 座位预约的「使用须知」弹层内部正是 `height:calc(100vh - 68px)`：
 *    实测 `van-list` 高度 = 0（scrollHeight 却是 944，正文 1357 字符的 HTML 都在），
 *    于是**正文被裁掉看不见**，而且列表的 load 事件永不触发 →「已知悉」按钮永久禁用（disabled）。
 *    注入像素高度 + 滚到底之后，按钮立刻变成「已知悉:可」—— 因果链实测闭合。
 *
 * 做法：把 `vh` 按当前视口高度换算成像素，注入为高优先级样式；同时兜住内联样式里的 `vh`
 * （「预约规则」弹窗用的是内联 `height:100vh`）。视口变化（旋转/键盘）时重算。
 * 只在我们的 WebView 里注入，浏览器访问不受影响。
 */
private const val VH_FIX_JS = """
(function(){
  function px(vh){ return Math.round(vh*window.innerHeight/100)+'px'; }
  function rewrite(v){ return v.replace(/([\d.]+)vh/g, function(_,n){ return px(parseFloat(n)); }); }
  function applyOnce(){
    var st=document.getElementById('zzp-vhfix');
    if(!st){ st=document.createElement('style'); st.id='zzp-vhfix';
      (document.head||document.documentElement).appendChild(st); }
    // 1) 自己补的几条：都是实测过 vh 塌成 0 的地方
    var h=window.innerHeight;
    var out=[
      // ⚠️ 这条必须限定 :not(.ruleConMo)：它是 PC 分支的 50vh，
      // 若不加限定，!important 会盖掉移动端的 .ruleCon.ruleConMo{height:100%}，
      // 把弹层压成半个屏幕高（实测踩过）。
      '.ruleCon:not(.ruleConMo){height:'+Math.round(h*0.5)+'px !important}',
      '.ruleCon.ruleConMo .van-list{height:'+Math.max(120,Math.round(h-68))+'px !important}',
      '.popupContent{height:'+Math.round(h*0.4)+'px !important}',
      '.bookRuleModal .ant-modal,.bookRuleModal .van-dialog,.bookRuleModal .ant-modal-content{height:'+h+'px !important}',
      '.van-floating-panel:after{height:'+h+'px !important;bottom:-'+h+'px !important}'
    ];
    // 2) 页面样式表里凡是用了 vh 的声明，统一改写成像素（含 @media 内的）
    if(!window.__zzpVhRulesDone){
      window.__zzpVhRulesDone=true;
      try{
        var walk=function(rules,cond){
          for(var i=0;i<rules.length;i++){
            var r=rules[i];
            if(r.cssRules){ walk(r.cssRules,(cond?cond+' and ':'')+(r.conditionText||'')); continue; }
            if(!r.style||!r.selectorText) continue;
            var decls=[];
            for(var j=0;j<r.style.length;j++){
              var p=r.style[j], v=r.style.getPropertyValue(p);
              if(v&&v.indexOf('vh')>=0) decls.push(p+':'+rewrite(v)+' !important');
            }
            if(!decls.length) continue;
            var body=r.selectorText+'{'+decls.join(';')+'}';
            out.push(cond?('@media '+cond+'{'+body+'}'):body);
          }
        };
        for(var i=0;i<document.styleSheets.length;i++){
          try{ if(document.styleSheets[i].cssRules) walk(document.styleSheets[i].cssRules,''); }catch(e){}
        }
      }catch(e){}
    }
    st.textContent=out.join('\n');
    // 3) 内联样式里的 vh（「预约规则」弹窗就是这么写的，样式表管不到）
    var els=document.querySelectorAll('[style*="vh"]');
    var n=0;
    for(var k=0;k<els.length;k++){
      var s=els[k].getAttribute('style')||'';
      if(s.indexOf('vh')<0) continue;
      els[k].setAttribute('style',rewrite(s)); n++;
    }
    return 'vhfix h='+h+' css='+out.length+' inline='+n;
  }
  window.__zzpVhFix=applyOnce;
  if(!window.__zzpVhHooked){
    window.__zzpVhHooked=true;
    window.addEventListener('resize',function(){ setTimeout(function(){ try{ applyOnce(); }catch(e){} },300); });
  }
  try{ return applyOnce(); }catch(e){ return 'ERR '+e.message; }
})()
"""

/**
 * 弹层诊断：把"使用须知"弹层的**真实几何与按钮状态**打出来。
 *
 * 为什么需要：座位预约的"使用须知"是一个 `van-popup` + `van-list` 弹层，
 * 正文靠 list 的 load 事件才解禁「已知悉」按钮。它在系统浏览器里正常、在我们的 WebView 里出问题，
 * 而这个 WebView 已知存在"百分比/vh 高度基准为 0"的怪病 —— 到底是
 * **正文没拿到**、**容器高度为 0 被裁掉**、还是 **list 没触发 load**，
 * 必须靠实测数据区分，不能猜。没有弹层时返回空串（不产生日志噪音）。
 */
private const val DIAG_JS = """
(function(){
  try{
    var p=document.querySelector('.van-popup');
    if(!p) return '';
    function h(el){ return el?Math.round(el.getBoundingClientRect().height):-1; }
    var rc=document.querySelector('.ruleCon');
    var list=document.querySelector('.ruleCon .van-list');
    var props=document.querySelector('.propsCon');
    var bs=document.querySelectorAll('button');
    var st=[];
    for(var i=0;i<bs.length;i++){
      var t=(bs[i].innerText||'').trim().slice(0,10);
      if(!t) continue;
      st.push(t+':'+(bs[i].disabled?'禁':'可'));
    }
    return 'popup='+h(p)+' ruleCon='+h(rc)+' list='+h(list)+' props='+h(props)+
      ' htmlLen='+(props?((props.innerHTML||'').length):-1)+
      ' innerH='+window.innerHeight+' docH='+document.documentElement.clientHeight+
      ' 按钮['+st.join('|')+']';
  }catch(e){ return 'DIAG_ERR '+e.message; }
})()
"""

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LibraryScreen(onBack: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastUrl by remember { mutableStateOf<String?>(null) }

    /** 页面真正渲染出内容之前，盖一层"正在打开"的提示，别让用户对着一片空白猜 */
    var contentReady by remember { mutableStateOf(false) }
    var loadStartedAt by remember { mutableLongStateOf(0L) }
    /**
     * 每次"重新加载"都 +1，用来**重新武装**下面的内容探测器。
     * 踩过的坑：探测协程成功一次就结束了，不换 key 就永远不会再跑，
     * 于是用户点一次刷新以后遮罩就再也收不起来（表现为"一直卡在正在打开"）。
     */
    var probeToken by remember { mutableIntStateOf(0) }
    /** 等太久没探测到内容时，给一个"直接显示页面"的出口 */
    var showSkip by remember { mutableStateOf(false) }

    fun restart() {
        error = null
        contentReady = false
        showSkip = false
        loadStartedAt = System.currentTimeMillis()
        probeToken++
        webView?.reload()
    }

    BackHandler {
        val view = webView
        if (view?.canGoBack() == true) view.goBack() else onBack()
    }

    // 加载期高频探测（每 0.25 秒）→ 一出问题立刻自愈；稳定后降为 2 秒一次的看门狗，
    // 这样页面自己重新加载 / 换路由把布局弄坏时还能自动救回来。
    LaunchedEffect(webView, probeToken) {
        val view = webView ?: return@LaunchedEffect
        var tries = 0
        var last = ""
        var lastDiag = ""
        var lastVhFix = ""
        while (true) {
            delay(if (contentReady) 2000L else 250L)
            if (!contentReady) {
                tries++
                if (tries > 80) {
                    contentReady = true
                    Log.w(TAG, "content probe gave up, showing page as-is")
                    continue
                }
            }
            runCatching {
                view.evaluateJavascript(PROBE_JS) { result ->
                    val r = result.orEmpty()
                    if (r != last) {
                        last = r
                        Log.i(TAG, "probe#$tries $r")
                    }
                    if (r.contains("ready") && !contentReady) {
                        contentReady = true
                        val cost = if (loadStartedAt > 0) {
                            System.currentTimeMillis() - loadStartedAt
                        } else {
                            -1L
                        }
                        Log.i(TAG, "content ready after ${cost}ms (probes=$tries, $r)")
                    }
                }
            }
            // 弹层诊断：只有出现弹层时才有输出，变化时才记一行
            runCatching {
                view.evaluateJavascript(DIAG_JS) { result ->
                    val d = result.orEmpty().replace("\\u003d", "=").trim('"')
                    if (d.isNotEmpty() && d != lastDiag) {
                        lastDiag = d
                        Log.i(TAG, "diagnostics $d")
                    }
                }
            }
            // vh 兜底：SPA 换路由/弹层后补挂（内联样式里的 vh 需要这一步）
            runCatching {
                view.evaluateJavascript(VH_FIX_JS) { result ->
                    val v = result.orEmpty().trim('"')
                    if (v != lastVhFix) {
                        lastVhFix = v
                        Log.i(TAG, "vhfix -> $v")
                    }
                }
            }
        }
    }

    // 等 8 秒还没好，就摆一个"直接显示页面"的按钮，用户不会被无限期挡住
    LaunchedEffect(webView, probeToken) {
        showSkip = false
        delay(8000)
        if (!contentReady) showSkip = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图书馆") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { restart() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.White),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        // debug 包打开 WebView 远程调试（chrome://inspect / 直接用 CDP 进页面查），
                        // release 包保持关闭 —— 开着它等于把页面内容暴露给任何能连 adb 的人。
                        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        // 这个网页没有深色主题，别让系统/ROM 给它套"强制深色"
                        @Suppress("DEPRECATION")
                        runCatching { settings.forceDark = WebSettings.FORCE_DARK_OFF }
                        setBackgroundColor(android.graphics.Color.WHITE)
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        // ⚠️ 必须在视图被测量出高度之后再 loadUrl。
                        // 否则页面会在"视口高度=0"时完成首屏布局，整条高度链塌成 0：
                        // 内容全在、却被 overflow:hidden 裁掉，只剩 html 背景色（灰屏）。
                        var started = false
                        fun startOnce(how: String) {
                            if (started) return
                            if (width <= 0 || height <= 0) return
                            started = true
                            loadStartedAt = System.currentTimeMillis()
                            Log.i(TAG, "loadUrl($how) view=${width}x$height")
                            loadUrl(LIBRARY_URL)
                        }
                        addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                            Log.i(TAG, "view size ${right - left}x${bottom - top}")
                            startOnce("onLayout")
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                progress = 0
                                lastUrl = url
                                Log.i(TAG, "start $url")
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                progress = 100
                                Log.i(TAG, "finish $url")
                                view?.postDelayed({
                                    runCatching {
                                        view.evaluateJavascript(NUDGE_JS) { r ->
                                            Log.i(TAG, "nudge $url -> $r")
                                        }
                                    }
                                    // 页面加载完立刻修 vh（弹层是后出现的，但样式表已经挂好，一出现就生效）
                                    runCatching {
                                        view.evaluateJavascript(VH_FIX_JS) { r ->
                                            Log.i(TAG, "vhfix(finish) -> $r")
                                        }
                                    }
                                }, 250)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                err: WebResourceError?,
                            ) {
                                Log.w(
                                    TAG,
                                    "error main=${request?.isForMainFrame} code=${err?.errorCode} " +
                                        "desc=${err?.description} url=${request?.url}",
                                )
                                if (request?.isForMainFrame == true) {
                                    error = "打不开图书馆系统：${err?.description ?: "网络错误"}"
                                    contentReady = true
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?,
                            ) {
                                Log.w(TAG, "http ${errorResponse?.statusCode} ${request?.url}")
                            }

                            /** 拦掉国外 CDN：连不上又一直等，正是页面卡住的常见原因 */
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                val url = request?.url?.toString().orEmpty()
                                if (BLOCKED_HOSTS.any { url.contains(it) }) {
                                    Log.i(TAG, "blocked $url")
                                    return WebResourceResponse(
                                        "application/javascript",
                                        "utf-8",
                                        ByteArrayInputStream(ByteArray(0)),
                                    )
                                }
                                return null
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                                Log.i(TAG, "console ${message?.messageLevel()} ${message?.message()}")
                                return true
                            }
                        }

                        Log.i(TAG, "factory created, waiting for layout")
                        post { startOnce("post") }
                        postDelayed({ startOnce("fallback") }, 1200)
                        webView = this
                    }
                },
                update = { webView = it },
            )

            if (!contentReady) {
                LoadingVeil(
                    progress = progress,
                    showSkip = showSkip,
                    onSkip = {
                        Log.i(TAG, "user skipped the loading veil")
                        contentReady = true
                    },
                )
            }

            error?.let { message ->
                ErrorPane(
                    message = message,
                    lastUrl = lastUrl,
                    onRetry = { restart() },
                )
            }
        }
    }
}

/** 打开期间盖住整页：学校服务器在校外本来就慢，得让用户知道"在加载"而不是"卡死了" */
@Composable
private fun LoadingVeil(progress: Int, showSkip: Boolean, onSkip: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator()
            Text("正在打开图书馆系统", style = MaterialTheme.typography.titleMedium)
            Text(
                "校外访问要连学校自己的服务器，第一次打开比较慢（通常 10–20 秒）。\n" +
                    "登录会自动完成，请稍等一下，不用退出。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (progress in 1..99) {
                Text("加载中 $progress%", style = MaterialTheme.typography.labelMedium)
            }
            if (showSkip) {
                TextButton(onClick = onSkip) { Text("等太久了？点这里直接显示页面") }
            }
        }
    }
}

@Composable
private fun ErrorPane(message: String, lastUrl: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "图书馆系统校外只能走明文 HTTP（学校侧限制），建议连校园网再试。" +
                (lastUrl?.let { "\n刚尝试的地址：$it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("重试") }
    }
}
