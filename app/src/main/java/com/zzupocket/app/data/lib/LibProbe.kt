package com.zzupocket.app.data.lib

import android.util.Log
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 图书馆系统「传输层」自检。
 *
 * 背景（已从它的 H5 主包里查清）：
 *  - 它不是 CAS 令牌换会话，而是**自己的学号+密码登录**（`/v4/login/login`，
 *    而且 `isCrypto:true` —— 请求体在客户端加密），所以我们**不逆向它的加密**；
 *  - 会话是 **cookie 形态**（未登录访问会返回 `{"code":10001,"message":"您尚未登录"}`）；
 *  → 结论：走「WebView 只当登录器 + 之后原生带 cookie 请求」这条路。
 *
 * 唯一剩下的未知是**传输层**：校外只能走 HTTP:80，而 Android 默认禁止明文。
 * 这个自检就确认三件事（全部只读、不需要登录）：
 *  1. 手机能不能访问公开接口（验证明文放行配置有效）
 *  2. 能不能真的拿到内容（预约规则全文）
 *  3. 需要登录的接口是否回"您尚未登录"（确认 cookie 会话 + 接口存在）
 */
object LibProbe {

    /** ⚠️ 校外只有 80 端口可用（443 被校园网边界拒绝） */
    private const val BASE = "http://order.lib.zzu.edu.cn"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun run(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.appendLine("=== 图书馆接口自检（传输层）===")
        sb.appendLine("时间：${LocalDate.now()}")
        sb.appendLine("目标：$BASE （校外只能走明文 HTTP:80）")

        probe(sb, "1 公开接口 /api/index/time", "/api/index/time")
        probe(sb, "2 预约规则 /v4/index/bookingRules", "/v4/index/bookingRules")
        probe(sb, "3 需登录 /v4/member/seat", "/v4/member/seat")

        sb.appendLine()
        sb.appendLine("=== 判读 ===")
        sb.appendLine("· 1、2 有真实内容 → 明文放行有效、手机上能访问图书馆系统")
        sb.appendLine("· 3 回「您尚未登录」→ 确认是 cookie 会话，WebView 登录后即可原生查询")
        sb.appendLine("· 出现 CLEARTEXT 字样 → 明文配置没生效")
        sb.appendLine("· 全部超时 → 当前网络到图书馆不通（换校园网再试）")
        finish(sb.toString())
    }

    private fun probe(sb: StringBuilder, title: String, path: String) {
        sb.appendLine()
        sb.appendLine("--- $title ---")
        val request = Request.Builder()
            .url("$BASE$path")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                sb.appendLine("HTTP ${response.code}，content-type=${response.header("Content-Type") ?: "-"}，${text.length} 字符")
                sb.appendLine("响应：${text.take(600)}")
            }
        } catch (e: IOException) {
            sb.appendLine("请求失败：${e.javaClass.simpleName}: ${e.message}")
        } catch (e: Throwable) {
            sb.appendLine("请求失败：${e.message}")
        }
    }

    /** 结果同时写 logcat：不用截图、不用复制，直接从日志取 */
    private fun finish(report: String): String {
        Log.i(TAG, "===== 图书馆接口自检 开始 =====")
        report.chunked(2800).forEach { Log.i(TAG, it) }
        Log.i(TAG, "===== 图书馆接口自检 结束 =====")
        return report
    }

    private const val TAG = "LibProbe"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; MEIZU 21 Note) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
}
