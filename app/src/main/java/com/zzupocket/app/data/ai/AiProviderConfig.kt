package com.zzupocket.app.data.ai

/** 设置页的预设；两者都走 OpenAI 兼容的 chat/completions 协议。 */
enum class AiProvider(val label: String) {
    DEEPSEEK("DeepSeek"),
    CUSTOM("自定义"),
}

/**
 * AI 摘要提供商配置。
 *
 * Base URL / 模型都允许留空：请求时分别回退到 [DEFAULT_BASE_URL] / [DEFAULT_MODEL]。
 * 这样设置页可以把默认值作为 placeholder，而不是把默认值写死进用户存储。
 */
data class AiProviderConfig(
    val provider: AiProvider = AiProvider.DEEPSEEK,
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
) {
    val effectiveBaseUrl: String get() = baseUrl.trim().ifBlank { DEFAULT_BASE_URL }
    val effectiveModel: String get() = model.trim().ifBlank { DEFAULT_MODEL }
    val configured: Boolean get() = apiKey.isNotBlank()

    companion object {
        /** DeepSeek 官方根地址；请求时补 /chat/completions。 */
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-chat"
    }
}
