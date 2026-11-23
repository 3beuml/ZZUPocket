package com.zzupocket.app.data.ai

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AiException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * OpenAI 兼容的 chat/completions 客户端。
 *
 * 隐私边界：
 *  - 使用独立 OkHttpClient，不挂日志拦截器，Key 与正文不进 logcat；
 *  - 只在用户点「AI 提炼 / AI 速览」时调用，不后台自动请求；
 *  - 请求从设备直连用户配置的服务商，不经过本项目任何服务器。
 */
class AiSummaryClient(
    private val http: OkHttpClient = defaultClient(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    suspend fun test(config: AiProviderConfig): String = withContext(Dispatchers.IO) {
        val content = chat(
            config = config,
            system = "你是连通性测试助手，只回复 OK。",
            user = "ping",
            maxTokens = 8,
        )
        content.ifBlank { "连接成功" }
    }

    suspend fun summarize(
        config: AiProviderConfig,
        title: String,
        body: String,
        publisher: String = "",
        publishedAt: String = "",
    ): String = withContext(Dispatchers.IO) {
        chat(
            config = config,
            system = AiPrompts.articleSystem(),
            user = AiPrompts.articleUser(title, body, publisher, publishedAt),
            maxTokens = 500,
        )
    }

    suspend fun overview(
        config: AiProviderConfig,
        articles: List<AiPrompts.OverviewItem>,
    ): String = withContext(Dispatchers.IO) {
        require(articles.isNotEmpty()) { "没有可速览的资讯" }
        chat(
            config = config,
            system = AiPrompts.overviewSystem(),
            user = AiPrompts.overviewUser(articles),
            maxTokens = 450,
        )
    }

    private fun chat(
        config: AiProviderConfig,
        system: String,
        user: String,
        maxTokens: Int,
    ): String {
        val key = config.apiKey.trim()
        if (key.isBlank()) throw AiException("还没有配置 API Key")
        val url = runCatching { AiEndpoint.chatUrl(config.effectiveBaseUrl) }
            .getOrElse { throw AiException(it.message ?: "Base URL 不正确") }

        val payload = ChatRequest(
            model = config.effectiveModel,
            messages = listOf(
                ChatMessage("system", system),
                ChatMessage("user", user),
            ),
            maxTokens = maxTokens,
        )
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .post(json.encodeToString(ChatRequest.serializer(), payload).toRequestBody(JSON_MEDIA))
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw AiException("无法连接 AI 服务商，请检查网络或 Base URL", e)
        }
        response.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw AiException(httpMessage(it.code, raw))
            val content = runCatching {
                json.decodeFromString(ChatResponse.serializer(), raw)
                    .choices.firstOrNull()?.message?.content.orEmpty().trim()
            }.getOrElse { throw AiException("AI 返回格式无法解析") }
            if (content.isBlank()) throw AiException("服务商返回了空摘要")
            return content
        }
    }

    private fun httpMessage(code: Int, raw: String): String {
        val providerMessage = runCatching {
            json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject
                ?.get("message")?.jsonPrimitive?.content
        }.getOrNull()?.trim().orEmpty()

        val base = when (code) {
            401 -> "API Key 无效或已过期"
            402 -> "账户余额不足"
            403 -> "无权访问该接口或模型"
            404 -> "接口地址不存在，请检查 Base URL"
            429 -> "请求过于频繁或额度已用尽"
            in 500..599 -> "AI 服务商暂时不可用"
            else -> "请求失败"
        }
        val detail = providerMessage.take(180).takeIf(String::isNotBlank)
        return if (detail == null) "$base（HTTP $code）" else "$base（HTTP $code）：$detail"
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

@Serializable
private data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    @SerialName("max_tokens") val maxTokens: Int = 500,
    val stream: Boolean = false,
)

@Serializable
private data class ChatResponse(
    val choices: List<ChatChoice> = emptyList(),
)

@Serializable
private data class ChatChoice(
    val message: ChatMessage? = null,
)
