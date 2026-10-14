package com.zzupocket.app.data.eas

import android.util.Log
import com.zzupocket.app.core.net.HttpClients
import com.zzupocket.app.data.auth.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import java.util.Base64

/**
 * 教务鉴权自检：回答「到底是我们发得不对，还是服务端不认这个令牌」。
 *
 * 起因（2026-10-14 用户报告）：统一身份认证与一卡通都正常，唯独课表一直报
 * 「教务登录态已失效，请重新登录」，重新登录仍复现。静态读代码只能证明我们发的是
 * `Authorization: <CAS userToken>`（与学校 H5 的写法一致，见 docs/interfaces/），
 * 但服务端为何拒绝，本地看不出来 —— 教务网关（openresty）对任何不带有效令牌的请求
 * 都返回 **401 且响应体为空**，所以"把服务端 message 打出来"这条路走不通（实测五种
 * 鉴权写法全是空体 401）。
 *
 * 于是这个自检只做能做的事：**把原始事实摊开**。
 *  1. 本地令牌形态：长度、是否 JWT、`alg`、`exp`、剩余有效期 —— **不打印令牌本身，也不打印 payload**
 *  2. 用这个令牌挨个请求教务在用的接口，原样记录状态码、关键响应头、响应体长度与前缀
 *  3. 换鉴权形状再试一遍（不带 / 裸令牌 / Bearer / 加 cookie userToken / 加 X-Id-Token）：
 *     **只要有一种形状能过，就说明是形态问题，而不是账号或令牌问题**
 *
 * 刻意**不走** [EasClient] 的错误映射 —— 这里要的是原始事实，不要给用户看的文案。
 */
class EasAuthProbe(private val auth: AuthRepository) {

    private val client = HttpClients.eas

    /** `semesterId` 用于课表接口（它就是用户报告里失败的那个接口）；为空则跳过。 */
    suspend fun run(semesterId: Int? = null): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("=== 教务鉴权自检 v1 ===")
        sb.appendLine("目标网关：jwxt.zzu.edu.cn（eams-door / eams-micro-server）")

        val token = try {
            auth.requireCasToken()
        } catch (e: Throwable) {
            sb.appendLine()
            sb.appendLine("!! 本地拿不到教务令牌：${e.javaClass.simpleName} ${e.message}")
            sb.appendLine("   说明本地这一关就没过（令牌缺失或已过 exp - 15 分钟安全窗），")
            sb.appendLine("   先去登录一次，再回来跑这个自检。")
            return@withContext finish(sb.toString())
        }

        appendTokenShape(sb, token)

        sb.appendLine()
        sb.appendLine("——— 一、用这个令牌请求教务在用的接口 ———")
        for ((label, url) in endpoints(semesterId)) {
            sb.appendLine()
            sb.appendLine("[$label] $url")
            val r = call(url, auth = token)
            sb.appendLine("  → ${describe(r)}")
        }

        sb.appendLine()
        sb.appendLine("——— 二、换鉴权形状（都打同一个接口，只看服务端反应有没有区别）———")
        sb.appendLine("   若某一种形状返回 2xx，那问题就是「请求形态」，而不是账号或令牌。")
        val probeUrl = "${EasClient.JWXT}/eams-micro-server/api/v1/semester/current-semester"
        for (v in variants(token)) {
            val r = call(probeUrl, headers = v.second)
            sb.appendLine("  ${v.first.padEnd(22)} → ${describe(r)}")
        }

        finish(sb.toString())
    }

    // ── 令牌形态（只报结构，不报内容）────────────────────────────────────

    private fun appendTokenShape(sb: StringBuilder, token: String) {
        val parts = token.split('.')
        sb.appendLine("令牌：${token.length} 字符，用 '.' 分段 ${parts.size} 段${if (parts.size == 3) "（JWT 形状）" else "（不是标准 JWT）"}")
        val expiresAt = runCatching { com.zzupocket.app.core.crypto.Crypto.jwtExpiresAtMillis(token) }.getOrNull()
        if (expiresAt != null) {
            val remainMin = (expiresAt - System.currentTimeMillis()) / 60000
            sb.appendLine("  exp=${java.util.Date(expiresAt)}，剩余 $remainMin 分钟（本地判定有效阈值：>15 分钟）")
        } else {
            sb.appendLine("  exp 解析失败（Crypto.jwtExpiresAtMillis 返回 null）")
        }
        // 只取 JWT 头部里的 alg 与标准声明 iss/aud，**绝不碰 payload 里的个人信息**
        val header = decodeSegment(parts.getOrNull(0))
        if (header != null) sb.appendLine("  header: ${header.take(120)}")
        val payloadClaims = claimSubset(decodeSegment(parts.getOrNull(1)))
        if (payloadClaims != null) sb.appendLine("  payload 标准声明：$payloadClaims")
        sb.appendLine("  ⚠️ 报告里不含令牌值本身，可以放心整段发给开发者。")
    }

    private fun decodeSegment(segment: String?): String? = try {
        if (segment.isNullOrEmpty()) null
        else String(Base64.getUrlDecoder().decode(pad(segment)))
    } catch (_: Throwable) {
        null
    }

    /** 从 payload 里只挑与鉴权有关的字段，避开姓名/学号 */
    private fun claimSubset(payload: String?): String? {
        if (payload == null) return null
        val keys = listOf("iss", "aud", "iat", "exp", "jti")
        val found = keys.mapNotNull { k ->
            val m = Regex("\"$k\"\\s*:\\s*(\"[^\"]*\"|-?\\d+)").find(payload) ?: return@mapNotNull null
            "$k=${m.groupValues[1]}"
        }
        return found.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun pad(s: String): String = s + "=".repeat((4 - s.length % 4) % 4)

    // ── 请求与描述 ───────────────────────────────────────────────────────

    private data class Result(
        val status: Int,
        val bodyLength: Int,
        val bodyPrefix: String,
        val contentType: String?,
        val server: String?,
        val hasSetCookie: Boolean,
        val error: String?,
    )

    private fun call(url: String, auth: String? = null, headers: Map<String, String> = emptyMap()): Result {
        val builder = Request.Builder().url(url)
        if (auth != null) builder.header("Authorization", auth)
        for ((k, v) in headers) builder.header(k, v)
        return try {
            client.newCall(builder.build()).execute().use { r -> toResult(r) }
        } catch (e: Throwable) {
            Result(0, 0, "", null, null, false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun toResult(r: Response): Result {
        // 401/403 的体是空的，但**其他状态**的体可能是有用的（改版/报错 JSON），所以照记不误
        val text = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
        return Result(
            status = r.code,
            bodyLength = text.length,
            bodyPrefix = text.replace(Regex("\\s+"), " ").take(200),
            contentType = r.header("Content-Type"),
            server = r.header("Server"),
            hasSetCookie = r.headers("Set-Cookie").isNotEmpty(),
            error = null,
        )
    }

    private fun describe(r: Result): String {
        if (r.error != null) return "网络失败：${r.error}"
        val verdict = when (r.status) {
            200 -> "✔ 通过（令牌被接受）"
            401 -> "✘ 未通过鉴权（网关不认这个令牌）"
            403 -> "✘ 令牌被识别但拒绝访问（权限问题，不是登录问题）"
            302 -> "→ 被重定向（可能弹回登录页）"
            else -> "状态码 ${r.status}"
        }
        val body = if (r.bodyLength == 0) "响应体空" else "响应体 ${r.bodyLength} 字符：${r.bodyPrefix}"
        return "HTTP ${r.status} $verdict｜$body｜Server=${r.server ?: "?"}｜Set-Cookie=${if (r.hasSetCookie) "有" else "无"}｜Content-Type=${r.contentType ?: "?"}"
    }

    // ── 待测接口与待测形状 ───────────────────────────────────────────────

    private fun endpoints(semesterId: Int?): List<Pair<String, String>> = buildList {
        add("学期列表" to "${EasClient.JWXT}/eams-door/api/v1/calendar/get-all-semesters")
        add("当前学期" to "${EasClient.JWXT}/eams-micro-server/api/v1/semester/current-semester")
        add("课程节次" to "${EasClient.JWXT}/eams-micro-server/api/v1/room/system/course-units")
        if (semesterId != null) {
            add("课表（用户报告的失败接口）" to "${EasClient.JWXT}/eams-micro-server/api/v1/lesson/student/course-table/$semesterId")
        } else {
            add("课表（无学期 id，跳过）" to "")
        }
    }

    /** 返回 形状名 → 附加头。空 map = 完全不带鉴权（对照组）。 */
    private fun variants(token: String): List<Pair<String, Map<String, String>>> = listOf(
        "不带任何鉴权（对照）" to emptyMap(),
        "裸令牌 Authorization" to mapOf("Authorization" to token),
        "Bearer 前缀" to mapOf("Authorization" to "Bearer $token"),
        "裸令牌 + cookie" to mapOf("Authorization" to token, "Cookie" to "userToken=$token"),
        "裸令牌 + X-Id-Token" to mapOf("Authorization" to token, "X-Id-Token" to token),
        "只用 X-Id-Token" to mapOf("X-Id-Token" to token),
    )

    private fun finish(report: String): String {
        report.lineSequence().forEach { Log.i(TAG, it) }
        return report
    }

    companion object {
        /** logcat 里用它取这份报告 */
        const val TAG = "EasAuthProbe"
    }
}
