package com.zzupocket.app.data.gms

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request

/**
 * 研究生系统（`gmsstu.zzu.edu.cn/stu`）探测记录器。
 *
 * ## 为什么需要它
 * 研究生课表不在本科教务（`jwxt` 的树维 eams）里，而在研究生院那套系统。我们这边**没有
 * 研究生账号**，所以接口路径/参数/响应字段都没法自己造 —— 只能"借"一个真实会话看一眼。
 *
 * ## 为什么用「WebView 注入 + 控制台回传」而不是 shouldInterceptRequest
 * `WebResourceRequest` **拿不到 POST 的请求体**，而课表接口多半是 POST（要带学期 id 等）。
 * 所以在页面里注入脚本钩住 `fetch` 与 `XMLHttpRequest`，把「方法 / URL / 请求体 / 状态码 /
 * 响应体」通过 `console.log("__GMSPROBE__" + JSON)` 回传，再由 `WebChromeClient.onConsoleMessage`
 * 收下来。这样 POST 也能完整看到。
 *
 * ## 隐私边界（很重要）
 * - 报告里**只有字段名与类型**，不含任何字段值；URL 的查询串值也一律打码
 *   —— 因为这份报告是要贴到公开 issue 上的。
 * - 响应原文只写进 logcat（tag = [TAG]），不写进报告；开发者需要样本时从 logcat 取。
 * - 令牌/密码永不落进任何一处：登录发生在学校自己的 CAS 页面里，我们只接收页面自己发出的请求记录。
 */
object GmsProbe {

    /** logcat 里取完整记录（含响应原文，供本地开发） */
    const val TAG = "GmsProbe"

    /** 页面里回传用的前缀，改这里要同步改 [JS_HOOK] */
    private const val PREFIX = "__GMSPROBE__"

    /** 研究生系统入口。注意这个站点会在 HTTP/HTTPS 间跳，统一走带斜杠的 HTTPS 入口 */
    const val ENTRY = "https://gmsstu.zzu.edu.cn/stu/"

    /** 身份类型接口：学校认证服务自己返回学籍层次（本科生/研究生），是"按学籍路由"的关键 */
    const val IDENTITY_URL = "https://authx-service.zzu.edu.cn/personal/api/v1/personal/me/user"

    private val json = Json { ignoreUnknownKeys = true }

    /** 静态资源不记 —— 一条页面能发几百个，会把报告冲爆 */
    private val STATIC = Regex("\\.(js|css|png|jpe?g|gif|svg|ico|woff2?|ttf|eot|map)(\\?|$)", RegexOption.IGNORE_CASE)

    /** 注入脚本：钩住 fetch / XHR，把请求与响应回传。用 try 包住，绝不打断页面自身逻辑。 */
    val JS_HOOK: String = """
        (function(){
          if (window.__gmsProbeInstalled) return;
          window.__gmsProbeInstalled = true;
          var P = '$PREFIX';
          function send(o){ try { console.log(P + JSON.stringify(o)); } catch(e){} }
          function trim(s, n){ try { s = (typeof s === 'string') ? s : JSON.stringify(s); return s ? s.slice(0, n) : ''; } catch(e){ return ''; } }

          // fetch
          var of = window.fetch;
          if (of) {
            window.fetch = function(input, init){
              var url = (typeof input === 'string') ? input : (input && input.url) || '';
              var method = (init && init.method) || (input && input.method) || 'GET';
              var body = (init && init.body) ? trim(init.body, 3000) : '';
              send({ k: 'req', m: method, u: url, b: body });
              return of.apply(this, arguments).then(function(res){
                try {
                  res.clone().text().then(function(t){
                    send({ k: 'res', u: url, s: res.status, ct: res.headers.get('content-type') || '', t: trim(t, 20000) });
                  }).catch(function(){});
                } catch(e){}
                return res;
              });
            };
          }

          // XMLHttpRequest
          var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(m, u){ this.__m = m; this.__u = u; return oo.apply(this, arguments); };
          XMLHttpRequest.prototype.send = function(b){
            var self = this;
            send({ k: 'req', m: self.__m || 'GET', u: self.__u || '', b: trim(b, 3000) });
            self.addEventListener('load', function(){
              send({ k: 'res', u: self.__u || '', s: self.status, ct: self.getResponseHeader('content-type') || '', t: trim(self.responseText, 20000) });
            });
            return os.apply(this, arguments);
          };
        })();
    """.trimIndent()

    /** 一条被记录下来的请求 */
    private data class Entry(
        val method: String,
        val path: String,
        val queryKeys: List<String>,
        val postPreview: String,
        var status: Int? = null,
        var contentType: String? = null,
        var bodyChars: Int? = null,
        var shape: List<String> = emptyList(),
    )

    /**
     * 记录器：一个实例对应一次探测会话。
     * 全部方法都可能在 WebView 的回调线程上被调用，所以内部用 synchronized 保护。
     */
    class Recorder {

        private val entries = LinkedHashMap<String, Entry>()
        private val navigations = mutableListOf<String>()
        private val cookieSamples = LinkedHashMap<String, List<String>>()
        private var rawLines = 0

        /** 页面跳到哪儿了（含 CAS 跳转链）——这条链本身就是"能不能走统一认证"的答案 */
        fun noteNavigation(url: String) {
            synchronized(this) {
                if (navigations.lastOrNull() != url) navigations += url
            }
        }

        /** 当前 URL 上 WebView 持有的 cookie 名（**只要名字**，值不记） */
        fun noteCookies(url: String, cookieHeader: String?) {
            if (cookieHeader.isNullOrBlank()) return
            val names = cookieHeader.split(';').mapNotNull { it.substringBefore('=').trim().ifBlank { null } }
            synchronized(this) { cookieSamples[hostOf(url)] = names }
        }

        fun onRequest(method: String, url: String, postBody: String?) {
            if (STATIC.containsMatchIn(url)) return
            val path = pathOf(url)
            val key = "$method $path"
            synchronized(this) {
                if (!entries.containsKey(key)) {
                    entries[key] = Entry(
                        method = method.uppercase(),
                        path = path,
                        queryKeys = queryKeysOf(url),
                        postPreview = maskJson(postBody).take(300),
                    )
                }
            }
        }

        fun onResponse(url: String, status: Int, contentType: String?, body: String) {
            if (STATIC.containsMatchIn(url)) return
            val path = pathOf(url)
            synchronized(this) {
                val e = entries.entries.firstOrNull { it.key.endsWith(path) }?.value ?: return
                e.status = status
                e.contentType = contentType
                e.bodyChars = body.length
                e.shape = shapeOf(body)
                rawLines++
            }
            // 响应原文只进 logcat（可能含姓名/学号，不能进要外发的报告）
            runCatching {
                Log.i(TAG, "RAW $path [$status] ${body.take(4000)}")
            }
        }

        /** 已记录多少条请求（界面显示进度用） */
        fun hitCount(): Int = synchronized(this) { entries.size }

        fun recordConsole(line: String): Boolean {
            val at = line.indexOf(PREFIX)
            if (at < 0) return false
            val payload = line.substring(at + PREFIX.length)
            return runCatching {
                val obj = json.parseToJsonElement(payload) as JsonObject
                val kind = (obj["k"] as? JsonPrimitive)?.content
                val url = (obj["u"] as? JsonPrimitive)?.content.orEmpty()
                when (kind) {
                    "req" -> onRequest((obj["m"] as? JsonPrimitive)?.content ?: "GET", url, (obj["b"] as? JsonPrimitive)?.content)
                    "res" -> onResponse(
                        url,
                        (obj["s"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                        (obj["ct"] as? JsonPrimitive)?.content,
                        (obj["t"] as? JsonPrimitive)?.content.orEmpty(),
                    )
                    else -> Unit
                }
                true
            }.getOrDefault(false)
        }

        fun report(): String {
            val sb = StringBuilder()
            synchronized(this) {
                sb.appendLine("=== 研究生系统探测报告 ===")
                sb.appendLine("（只含字段名与路径；查询串的值已打码，不含任何令牌/姓名/学号）")
                sb.appendLine()
                sb.appendLine("【跳转链】能不能走学校统一身份认证，看这里：")
                navigations.forEach { sb.appendLine("  → ${maskUrl(it)}") }
                if (navigations.any { it.contains("cas.s.zzu.edu.cn") }) {
                    sb.appendLine("  ✔ 出现 cas.s.zzu.edu.cn，说明它走统一认证（CAS）")
                }
                sb.appendLine()
                sb.appendLine("【会话 cookie 名（值不记录）】")
                if (cookieSamples.isEmpty()) sb.appendLine("  （无）")
                cookieSamples.forEach { (host, names) -> sb.appendLine("  $host : ${names.joinToString(", ")}") }
                sb.appendLine()
                sb.appendLine("【记录到的请求 ${entries.size} 条】")
                if (entries.isEmpty()) sb.appendLine("  （无 —— 说明还没登录，或页面没发接口请求）")
                entries.values.sortedWith(compareBy({ it.path }, { it.method })).forEach { e ->
                    sb.appendLine("  ${e.method} ${e.path}")
                    if (e.queryKeys.isNotEmpty()) sb.appendLine("      查询参数名: ${e.queryKeys.joinToString(", ")}")
                    if (e.postPreview.isNotBlank()) sb.appendLine("      请求体: ${e.postPreview}")
                    val st = e.status?.toString() ?: "(未捕获响应)"
                    sb.appendLine("      响应: HTTP $st, ${e.bodyChars ?: 0} 字符, ${e.contentType ?: "?"}")
                    e.shape.take(25).forEach { sb.appendLine("        $it") }
                    if (e.shape.size > 25) sb.appendLine("        …（还有 ${e.shape.size - 25} 个字段）")
                }
                sb.appendLine()
                sb.appendLine("【课表相关（本报告的重点）】")
                val course = entries.values.filter { COURSE_HINT.containsMatchIn(it.path) }
                if (course.isEmpty()) {
                    sb.appendLine("  （这次没抓到课表接口 —— 请确认登录后进到了「课表/我的课程」页面）")
                } else {
                    course.forEach { sb.appendLine("  ✔ ${it.method} ${it.path}（${it.shape.size} 个字段）") }
                }
            }
            val text = sb.toString()
            runCatching { Log.i(TAG, "REPORT\n$text") }
            return text
        }

        companion object {
            private val COURSE_HINT = Regex("course|lesson|schedule|timetable|train|plan|kebiao|curriculum", RegexOption.IGNORE_CASE)
        }
    }

    // ── 身份类型（"按学籍自动路由"的前提）────────────────────────────────

    /**
     * 试几种鉴权写法问同一个身份接口，看哪种被接受，并读出 `identity_type_name`。
     *
     * 为什么试多种：zzupy 里这个接口的请求头我们没有 100% 确认，与其猜，不如一次摆开看结果 ——
     * 而且这个结论是**可以离线复用**的（拿到之后正式代码直接按能用的那种写）。
     */
    fun identityCheck(token: String, client: okhttp3.OkHttpClient): String {
        val sb = StringBuilder()
        sb.appendLine("=== 身份类型自检（判断本科/研究生的依据）===")
        sb.appendLine("接口：$IDENTITY_URL")
        sb.appendLine()
        val variants = listOf(
            "Authorization: 裸令牌" to mapOf("Authorization" to token),
            "Authorization: Bearer" to mapOf("Authorization" to "Bearer $token"),
            "X-Id-Token" to mapOf("X-Id-Token" to token),
            "裸令牌 + X-Id-Token" to mapOf("Authorization" to token, "X-Id-Token" to token),
        )
        var winner = ""
        for ((label, headers) in variants) {
            val result = runCatching {
                val builder = Request.Builder().url(IDENTITY_URL).get()
                headers.forEach { (k, v) -> builder.header(k, v) }
                client.newCall(builder.build()).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    Triple(r.code, text.length, text)
                }
            }.getOrElse { Triple(0, 0, "网络失败：${it.javaClass.simpleName}") }
            sb.appendLine("· $label → HTTP ${result.first}，${result.second} 字符")
            if (result.first in 200..299) {
                winner = label
                sb.appendLine("  ✔ 这个写法可用")
                sb.appendLine("  字段结构（只列名字与类型）：")
                shapeOf(result.third).take(40).forEach { sb.appendLine("    $it") }
                val identity = extractIdentity(result.third)
                sb.appendLine("  身份类型：${identity ?: "（响应里没找到 identity_type_* —— 把上面字段结构发我）"}")
                break
            }
        }
        if (winner.isEmpty()) sb.appendLine("\n四种写法都没通 —— 把这份结果发我，换别的头再试。")
        return sb.toString()
    }

    /** 只把 identity_type_* 与 roles 挑出来（不含姓名/学号） */
    private fun extractIdentity(text: String): String? = runCatching {
        val root = json.parseToJsonElement(text)
        val wanted = listOf("identity_type_id", "identity_type_name", "identity_type_code", "roles")
        val found = mutableListOf<String>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) ->
                    if (k in wanted) found += "$k=${(v as? JsonPrimitive)?.content ?: v.toString().take(60)}"
                    walk(v)
                }
                is JsonArray -> e.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(root)
        found.takeIf { it.isNotEmpty() }?.joinToString("  ")
    }.getOrNull()

    // ── 打码与结构提取 ───────────────────────────────────────────────────

    /** URL：保留 host/path，查询串只留"参数名=*" */
    private fun maskUrl(url: String): String = runCatching {
        val base = url.substringBefore('?')
        val q = url.substringAfter('?', "")
        if (q.isBlank()) base else "$base?" + q.split('&').joinToString("&") { it.substringBefore('=') + "=*" }
    }.getOrDefault(url.take(120))

    private fun pathOf(url: String): String = runCatching {
        val noQuery = url.substringBefore('?')
        val idx = noQuery.indexOf("://")
        if (idx < 0) noQuery.take(120) else noQuery.substring(noQuery.indexOf('/', idx + 3)).take(120)
    }.getOrDefault(url.take(120))

    private fun queryKeysOf(url: String): List<String> =
        url.substringAfter('?', "").takeIf { it.isNotBlank() }
            ?.split('&')?.map { it.substringBefore('=') }?.take(12) ?: emptyList()

    private fun hostOf(url: String): String = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

    /** 请求体：把看起来是凭据/个人信息的字段值打码 */
    private fun maskJson(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return SENSITIVE.replace(text) { m -> "${m.groupValues[1]}: \"***\"" }
    }

    private val SENSITIVE = Regex(
        "\"(password|pwd|token|userToken|idToken|accessToken|refreshToken|ticket|code|studentId|xh|name)\"\\s*:\\s*\"[^\"]*\"",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 只列「字段路径 + 类型 + 长度」，**一个字段值都不打印**。
     * 与 `core/JsonStructure` 的区别就在这里：那个会带示例值（贴给人看方便），
     * 而这份报告要贴到公开 issue 上，不能带任何值。
     */
    fun shapeOf(text: String): List<String> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return listOf("（不是 JSON：${text.take(60)}）")
        val out = mutableListOf<String>()
        fun kindOf(e: JsonElement): String = when (e) {
            is JsonObject -> "object(${e.size})"
            is JsonArray -> "array(${e.size})"
            is JsonPrimitive -> when {
                e.isString -> "string(${e.content.length})"
                else -> "number/bool"
            }
            else -> "?"
        }
        fun walk(e: JsonElement, path: String, depth: Int) {
            if (depth > 6) return
            when (e) {
                is JsonObject -> e.entries.take(40).forEach { (k, v) ->
                    val p = if (path.isEmpty()) k else "$path.$k"
                    out += "$p : ${kindOf(v)}"
                    walk(v, p, depth + 1)
                }
                is JsonArray -> e.firstOrNull()?.let { walk(it, "$path[]", depth + 1) }
                else -> Unit
            }
        }
        walk(root, "", 0)
        return out
    }
}
