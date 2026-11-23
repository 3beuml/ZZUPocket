package com.zzupocket.app.data.ai

import java.net.URI

/**
 * OpenAI 兼容接口的地址解析。
 *
 * 用户填的是「根地址」（通常到 /v1 为止），这里统一补 `/chat/completions`；
 * 如果用户直接粘贴了完整端点，也原样接受，避免设置页反复试错。
 */
object AiEndpoint {

    /** 返回 null 表示合法；否则是给用户看的中文原因。 */
    fun validate(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val uri = runCatching { URI(value) }.getOrNull()
            ?: return "地址格式不正确"
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return "只支持 https:// 地址"
        }
        if (uri.host.isNullOrBlank()) return "地址缺少域名"
        if (uri.query != null || uri.fragment != null) {
            return "地址不要带查询参数或 # 片段"
        }
        return null
    }

    fun chatUrl(raw: String? = null): String {
        val cause = validate(raw)
        require(cause == null) { cause.orEmpty() }
        val base = raw?.trim().orEmpty()
            .ifBlank { AiProviderConfig.DEFAULT_BASE_URL }
            .trimEnd('/')
        return if (base.endsWith("/chat/completions", ignoreCase = true)) {
            base
        } else {
            "$base/chat/completions"
        }
    }
}
