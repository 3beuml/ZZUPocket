package com.zzupocket.app.data.eas

import com.zzupocket.app.domain.AcademicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 用**本科教务**判断学籍层次：这个接口（`/eams-door/api/v1/portal/home/user-info`）
 * 是校外可达的，本科账号返回 200，研究生账号被网关拒绝（401/403）。
 *
 * 为什么不问学校的身份接口：`authx-service.s.zzu.edu.cn` 实测在手机网络上解析不出来
 * （UnknownHostException，只在校园网内可用）。
 *
 * ⚠️ 响应里含姓名与学号 —— 本类**只取身份字段用于判定**，其余一律不向外暴露。
 * ⚠️ 必须在 IO 线程调用（内部已经 withContext(Dispatchers.IO)，不要再放主线程）。
 */
object UndergradIdentityProbe {

    private const val URL = "https://jwxt.zzu.edu.cn/eams-door/api/v1/portal/home/user-info"

    private val json = Json { ignoreUnknownKeys = true }

    /** 判定结果。`identity` 是服务端自己的说法（如 `STUDENT`），**不含任何个人信息**。 */
    data class Result(
        val status: Int,
        val track: AcademicTrack,
        val identity: String?,
        /** 仅内部使用，绝不打印 */
        val account: String?,
    )

    suspend fun probe(token: String, client: OkHttpClient): Result = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(URL)
            .header("Authorization", token)
            .get()
            .build()
        val (status, body) = try {
            client.newCall(request).execute().use { r -> r.code to r.body?.string().orEmpty() }
        } catch (e: Throwable) {
            0 to ""
        }
        val identity = extract(body, listOf("identity", "defaultIdentity"))
        val account = extract(body, listOf("account"))
        Result(
            status = status,
            track = AcademicTrack.fromUndergradProbe(status, identity, account),
            identity = identity,
            account = account,
        )
    }

    /** 从 `{"data":{...}}` 里挑字段；找不到返回 null */
    private fun extract(body: String, keys: List<String>): String? = runCatching {
        val root = json.parseToJsonElement(body)
        var found: String? = null
        fun walk(e: kotlinx.serialization.json.JsonElement) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) ->
                    if (k in keys && v is JsonPrimitive) found = found ?: v.content
                    walk(v)
                }
                is kotlinx.serialization.json.JsonArray -> e.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(root)
        found
    }.getOrNull()
}
