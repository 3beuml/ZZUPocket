package com.zzupocket.app.data.gms

import java.util.Base64

/**
 * 探针报告的统一脱敏。
 *
 * 这份报告是要贴到公开 issue 上的，所以只允许出现「字段名、路径、状态码」，
 * 不允许出现任何值。原先的打码只覆盖了 JSON 与查询串，留下两个真漏点
 * （2026-10-18 由 issue #2 的报告者指出，均已在真机上复现）：
 *
 *  1. [body] —— 只按 JSON 的 `"k": "v"` 形态打码；而统一身份认证的
 *     `/cas/mfa/detect` 是 `application/x-www-form-urlencoded`，
 *     `username=…&password=…` 会被**原样**写进报告。明文密码出报告，必须堵。
 *  2. [path] —— 只打码查询串的值；而研究生系统的业务 URL 是
 *     `/stu/{base64(AppKey + 模块路径 + "#" + session-guid)}`，guid 在**路径段**里，
 *     会原样出现在报告里。guid 等于会话凭证，等同于密码。
 *
 * 设计取向：宁可少给一点信息，也不能漏值。所以
 *  - 敏感字段（密码/账号/学号/令牌/guid 等）一律打成 `***`；
 *  - 非敏感字段保留原值，但长数字串与长令牌串仍会被兜底盖掉；
 *  - 路径里的路由段解出模块路径保留（诊断要的就是它），guid 打成 `***`。
 *
 * 纯函数、不碰 Android API，便于单测（[GmsRedactTest]）。
 */
object GmsRedact {

    /** 研究生系统业务 URL 的固定 AppKey（guid 之外唯一固定的部分） */
    private const val APP_KEY = "MyHeartWillGoOn"

    private val GUID_RE = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        RegexOption.IGNORE_CASE,
    )

    /** 像 base64 路由段：够长、只用 URL-safe 字符 */
    private val ROUTE_SEGMENT = Regex("^[A-Za-z0-9_-]{24,}$")

    /** JSON 的 `"k": <标量>` */
    private val JSON_FIELD = Regex(
        "\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:\\s*(\"[^\"]*\"|[0-9]+(?:\\.[0-9]+)?|true|false|null)",
    )

    /** 表单的 `k=v&…` */
    private val FORM_PAIR = Regex("([A-Za-z_][A-Za-z0-9_.-]*)=([^&]*)")

    private val LONG_DIGITS = Regex("\\d{8,}")
    private val LONG_TOKEN = Regex("[A-Za-z0-9_-]{40,}")

    /**
     * 命中的字段名一律打码。清单取「宁可多打」：多打掉一个字段名最多损失一点可读性，
     * 漏一个就是明文密码/学号/会话凭证上公网。
     */
    private val SENSITIVE_KEYS = setOf(
        "password", "pwd", "passwd", "pass", "secret",
        "username", "user", "account", "loginname", "login", "xh", "studentid", "userid", "uid",
        "idcard", "cardno", "mobile", "phone", "email", "name", "realname",
        "token", "usertoken", "idtoken", "accesstoken", "refreshtoken", "ticket", "code",
        "guid", "sessionid", "sessionguid", "captcha", "smscode", "verifycode", "credential",
    )

    /** 请求体：JSON 与表单两种形态都按字段名打码，再兜底盖长串 */
    fun body(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val text = raw.trim()
        val masked = if (text.startsWith("{") || text.startsWith("[")) maskJson(text) else maskForm(text)
        return scrub(masked).replace(WHITESPACE, " ").take(300)
    }

    /** 完整 URL：保留 host/path，路由段里的 guid 与查询串的值都打码 */
    fun url(raw: String): String = runCatching {
        val noQuery = raw.substringBefore('?')
        val query = raw.substringAfter('?', "")
        val sb = StringBuilder()
        val schemeEnd = noQuery.indexOf("://")
        val hostEnd = if (schemeEnd >= 0) noQuery.indexOf('/', schemeEnd + 3) else -1
        if (hostEnd < 0) {
            sb.append(noQuery)
        } else {
            sb.append(noQuery, 0, hostEnd)
            sb.append(maskSegments(noQuery.substring(hostEnd)))
        }
        if (query.isNotBlank()) {
            sb.append('?')
            sb.append(query.split('&').joinToString("&") { it.substringBefore('=') + "=*" })
        }
        sb.toString()
    }.getOrDefault("***")

    /** 只有路径：路由段解出模块路径、guid 打成 `***`，例如 `/stu/{pygl/xskbcx/loadKb#***}` */
    fun path(raw: String): String = runCatching {
        val noQuery = raw.substringBefore('?')
        val idx = noQuery.indexOf("://")
        val p = if (idx < 0) noQuery else noQuery.substring(noQuery.indexOf('/', idx + 3))
        maskSegments(p).take(120)
    }.getOrDefault("/***")

    // ── 内部 ─────────────────────────────────────────────────────────────

    private fun maskJson(text: String): String =
        JSON_FIELD.replace(text) { m ->
            val key = m.groupValues[1]
            if (isSensitive(key)) "\"$key\":\"***\"" else m.value
        }

    private fun maskForm(text: String): String =
        FORM_PAIR.replace(text) { m ->
            val key = m.groupValues[1]
            if (isSensitive(key)) "$key=***" else m.value
        }

    /** 兜底：长数字（学号/卡号）与长令牌串 */
    private fun scrub(text: String): String = text
        .replace(LONG_DIGITS, "***")
        .replace(LONG_TOKEN, "***")

    private fun isSensitive(key: String): Boolean {
        val k = key.lowercase().replace("_", "").replace("-", "")
        return k in SENSITIVE_KEYS
    }

    private fun maskSegments(path: String): String =
        path.split('/').joinToString("/") { redactSegment(it) }

    /**
     * 路由段：解出 `AppKey + 模块路径 + # + guid` 后，只保留模块路径，guid 打成 `***`。
     * 解不出来就整段盖掉——不知道里面是什么，就不给。
     */
    private fun redactSegment(segment: String): String {
        if (!ROUTE_SEGMENT.matches(segment)) return segment
        val module = decodeRoute(segment) ?: return "***"
        return "{${module.ifBlank { "?" }}#***}"
    }

    /**
     * 解出模块路径；解不出来返回 null。
     *
     * 编码端把 padding 的 `=` 换成了 `_`，而 URL-safe 字符集本身也有 `_`，
     * 所以不能简单替换 —— 这里列两个候选（原样 / 末尾 `_` 还原成 padding），
     * **用"末尾必须是 UUID 形态的 guid"来自校验**，解错自然落到下一个候选。
     */
    private fun decodeRoute(segment: String): String? {
        for (candidate in routeCandidates(segment)) {
            val bytes = runCatching { Base64.getUrlDecoder().decode(candidate) }.getOrNull() ?: continue
            val text = String(bytes, Charsets.UTF_8)
            if (text.isEmpty() || text.any { it.code !in 0x20..0x7e }) continue
            val hash = text.lastIndexOf('#')
            if (hash <= 0) continue
            if (!GUID_RE.matches(text.substring(hash + 1))) continue
            return text.substring(0, hash).removePrefix(APP_KEY).trim()
        }
        return null
    }

    private fun routeCandidates(segment: String): List<String> {
        val out = mutableListOf(segment)
        val trimmed = segment.trimEnd('_')
        val padCount = segment.length - trimmed.length
        if (padCount in 1..2) out += trimmed + "=".repeat(padCount)
        return out
    }

    private val WHITESPACE = Regex("\\s+")
}
