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
 * 两条腿，缺一不可：
 * 1. **样式表改写**：遍历样式表里所有含 `vh` 的声明，按 `window.innerHeight` 改成像素；
 *    再扫一遍内联样式里的 `vh`。这条只在样式表**同源**时有效。
 * 2. **结构性兜底**（`rescue`）：CAS 登录页的样式表挂在别的域名上、没有 CORS 头，
 *    `cssRules` 直接抛异常 → 上面那条一个字也改不到。于是改成量出来的：
 *    凡是「盒子高度塌成 0，却把绝对定位的后代丢在盒外」的容器，直接补满屏高。
 *    实测 CAS 页补完 `#vue_main` / `.main` 后，「账号密码登录」「忘记密码？」
 *    「修改安全手机」恢复成上中下三段的正常排布（量法见 `_research/cdp_cas_*.mjs`）。
 *
 * 视口变化时重算；只在我们自己的 WebView 里注入，浏览器访问不受影响。
 *
 * ## 为什么反复注入
 * 页面可能在 JS 里晚一步（异步）设置高度，单次注入会漏。重复注入是幂等的
 * （只改写样式/补高度，不建重复元素）。
 */
object WebViewVhFix {

    private const val TAG = "WebViewVhFix"

    /** 通用版：不依赖任何具体站点的选择器 */
    val JS: String = """
        (function(){
          function px(v){ return Math.round(v * window.innerHeight / 100) + 'px'; }
          function rewrite(text){ return text.replace(/([\d.]+)vh/g, function(_, n){ return px(parseFloat(n)); }); }

          // 结构性兜底：样式表读不到（跨域）时，靠测量把塌陷的定位容器补起来。
          // 判据 = 盒子高度为 0 + 有绝对/固定定位的后代 + 有内容溢出盒外。
          // 三条同时成立才动手，正常的 0 高容器（浮动清空、纯装饰）不会被误伤。
          //
          // 闸门：先扔一个 height:100vh 的探针进去。实测这个 WebView 里 1vh 恒为 0px
          // （innerHeight 却是 622），只有探针确实塌成 0 才说明这一页会中招；
          // vh 正常的页面一概不动，把误伤面收成 0。
          function vhBroken(){
            if (!document.body || window.innerHeight < 100) return false;
            var d = document.createElement('div');
            d.style.cssText = 'position:absolute;left:-9999px;top:0;width:10px;height:100vh';
            document.body.appendChild(d);
            var h = d.getBoundingClientRect().height;
            d.parentNode.removeChild(d);
            return h < 1;
          }

          function rescue(){
            var H = window.innerHeight;
            if (H < 100) return 0;
            // 先清上一轮补的：视口变了要按新值重算，也避免把旧值留在 DOM 上
            var old = document.querySelectorAll('[data-zzp-fix]');
            for (var i = 0; i < old.length; i++) {
              old[i].style.removeProperty('height');
              old[i].removeAttribute('data-zzp-fix');
            }
            var n = 0, budget = 6000;
            var all = document.querySelectorAll('body *');
            for (var k = 0; k < all.length; k++) {
              var el = all[k];
              var er = el.getBoundingClientRect();
              if (er.height >= 4) continue;          // 没塌陷
              if (budget <= 0) break;
              var kids = el.querySelectorAll('*');
              var positioned = false, spill = false;
              for (var j = 0; j < kids.length; j++) {
                if (--budget < 0) break;
                var kr = kids[j].getBoundingClientRect();
                if (!spill && kr.height > 0 && kr.bottom > er.bottom + 4) spill = true;
                if (!positioned) {
                  var cs = getComputedStyle(kids[j]);
                  if (cs.position === 'absolute' || cs.position === 'fixed') positioned = true;
                }
                if (positioned && spill) break;
              }
              if (!positioned || !spill) continue;
              el.style.setProperty('height', H + 'px', 'important');
              el.setAttribute('data-zzp-fix', '1');
              n++;
            }
            return n;
          }

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
            // 3) 跨域样式表读不到时的结构性兜底（仅当 vh 确实失效）
            var fixed = 0, broken = false;
            try { broken = vhBroken(); } catch (e) {}
            if (broken) { try { fixed = rescue(); } catch (e) {} }
            return 'vhfix h=' + window.innerHeight + ' vh=' + (broken ? 'broken' : 'ok')
                 + ' css=' + out.length + ' inline=' + n + ' rescued=' + fixed;
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
