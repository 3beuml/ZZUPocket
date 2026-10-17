package com.zzupocket.app.ui.web

import android.util.Log
import android.webkit.WebView

/**
 * WebView 的 `vh` 单位兜底修复。
 *
 * ## 修的是什么
 * 这个 App 的 WebView 里 **CSS 的 `vh` 单位恒等于 0**（`window.innerHeight` 却是正常的），
 * 于是页面里凡是靠 `100vh` / `calc(100vh - 68px)` 定高的容器都会塌成 0 高，里面的元素
 * 互相压在一起。真机 CDP 逐条量过的数据与完整排查过程见
 * `docs/interfaces/图书馆WebView灰屏.md`（同一个根因当年让图书馆页面灰屏、
 * 「使用须知」弹层高度为 0、按钮永久禁用）。
 *
 * 学校统一身份认证（CAS）登录页也是靠 `vh` 布局的 —— 不修它，登录页上
 * 「账号密码登录」和「修改安全手机」会重叠到一起。
 *
 * ## 怎么修
 * 遍历页面**样式表**里所有含 `vh` 的声明，按 `window.innerHeight` 改写成像素；
 * 再扫一遍**内联样式**里的 `vh`；视口变化时重算。只在我们自己的 WebView 里注入，
 * 浏览器访问不受影响。
 *
 * ## 为什么反复注入
 * 页面可能在 JS 里晚一步（异步）设置高度，单次注入会漏。重复注入是幂等的
 * （只改写样式，不建重复元素）。
 */
object WebViewVhFix {

    private const val TAG = "WebViewVhFix"

    /** 通用版：不依赖任何具体站点的选择器 */
    val JS: String = """
        (function(){
          function px(v){ return Math.round(v * window.innerHeight / 100) + 'px'; }
          function rewrite(text){ return text.replace(/([\d.]+)vh/g, function(_, n){ return px(parseFloat(n)); }); }
          function applyOnce(){
            var st = document.getElementById('zzp-vhfix');
            if (!st) {
              st = document.createElement('style');
              st.id = 'zzp-vhfix';
              (document.head || document.documentElement).appendChild(st);
            }
            var out = [];
            // 1) 样式表里所有含 vh 的声明 → 像素（含 @media 内的）
            if (!window.__zzpVhRulesDone) {
              window.__zzpVhRulesDone = true;
              try {
                var walk = function(rules, cond){
                  for (var i = 0; i < rules.length; i++) {
                    var r = rules[i];
                    if (r.cssRules) { walk(r.cssRules, (cond ? cond + ' and ' : '') + (r.conditionText || '')); continue; }
                    if (!r.style || !r.selectorText) continue;
                    var decls = [];
                    for (var j = 0; j < r.style.length; j++) {
                      var p = r.style[j], v = r.style.getPropertyValue(p);
                      if (v && v.indexOf('vh') >= 0) decls.push(p + ':' + rewrite(v) + ' !important');
                    }
                    if (!decls.length) continue;
                    var body = r.selectorText + '{' + decls.join(';') + '}';
                    out.push(cond ? ('@media ' + cond + '{' + body + '}') : body);
                  }
                };
                for (var i = 0; i < document.styleSheets.length; i++) {
                  try { if (document.styleSheets[i].cssRules) walk(document.styleSheets[i].cssRules, ''); } catch (e) {}
                }
              } catch (e) {}
            }
            st.textContent = out.join('\n');
            // 2) 内联样式里的 vh
            var els = document.querySelectorAll('[style*="vh"]');
            var n = 0;
            for (var k = 0; k < els.length; k++) {
              var s = els[k].getAttribute('style') || '';
              if (s.indexOf('vh') < 0) continue;
              els[k].setAttribute('style', rewrite(s)); n++;
            }
            return 'vhfix h=' + window.innerHeight + ' css=' + out.length + ' inline=' + n;
          }
          window.__zzpVhFix = applyOnce;
          if (!window.__zzpVhHooked) {
            window.__zzpVhHooked = true;
            window.addEventListener('resize', function(){
              setTimeout(function(){ try { applyOnce(); } catch (e) {} }, 300);
            });
          }
          try { return applyOnce(); } catch (e) { return 'ERR ' + e.message; }
        })()
    """.trimIndent()

    /**
     * 注入并重复几次（页面常在加载后异步设置高度）。
     * 幂等：只改写样式，不会重复建元素。
     */
    fun install(view: WebView?, rounds: Int = 6, intervalMs: Long = 700L) {
        val wv = view ?: return
        var left = rounds
        val task = object : Runnable {
            override fun run() {
                if (left-- <= 0) return
                runCatching {
                    wv.evaluateJavascript(JS) { result ->
                        // 只在最后一轮记一条，避免日志噪音
                        if (left <= 0) Log.i(TAG, "result=$result")
                    }
                }
                wv.postDelayed(this, intervalMs)
            }
        }
        wv.postDelayed(task, 300L)
    }
}
