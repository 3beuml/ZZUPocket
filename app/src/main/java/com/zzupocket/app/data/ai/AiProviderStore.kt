package com.zzupocket.app.data.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AI 提供商配置的本地存储。
 *
 * ⚠️ 与校园令牌分开文件：
 *  - 校园账号登出时 [com.zzupocket.app.data.auth.TokenStore.clearAll] 会清空令牌，
 *    但 AI Key 与学校账号无关，不该被一起清掉；
 *  - `ai_provider_prefs.xml` 同样加入备份/换机排除项，Key 不离开设备。
 */
class AiProviderStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // 密钥库在极少数设备上可能不可用；AI 是可选功能，读取失败时退回默认配置，
    // 不能让资讯雷达或设置页直接崩掉。
    private val _config = MutableStateFlow(runCatching { load() }.getOrElse { AiProviderConfig() })
    val config: StateFlow<AiProviderConfig> = _config.asStateFlow()

    /**
     * 保存配置。[apiKey] 为 null 表示"用户没改 Key"，保留原值；
     * 传空串表示显式清除。
     */
    fun save(
        provider: AiProvider,
        baseUrl: String,
        model: String,
        apiKey: String?,
    ): Boolean = runCatching {
        val nextKey = apiKey ?: _config.value.apiKey
        prefs.edit().apply {
            putString(KEY_PROVIDER, provider.name)
            if (baseUrl.isBlank()) remove(KEY_BASE_URL) else putString(KEY_BASE_URL, baseUrl.trim())
            if (model.isBlank()) remove(KEY_MODEL) else putString(KEY_MODEL, model.trim())
            if (nextKey.isBlank()) remove(KEY_API_KEY) else putString(KEY_API_KEY, nextKey.trim())
        }.apply()

        _config.value = AiProviderConfig(
            provider = provider,
            baseUrl = baseUrl.trim(),
            model = model.trim(),
            apiKey = nextKey.trim(),
        )
        true
    }.getOrDefault(false)

    fun clearKey(): Boolean = runCatching {
        prefs.edit().remove(KEY_API_KEY).apply()
        _config.value = _config.value.copy(apiKey = "")
        true
    }.getOrDefault(false)

    private fun load(): AiProviderConfig = AiProviderConfig(
        provider = prefs.getString(KEY_PROVIDER, null)
            ?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() }
            ?: AiProvider.DEEPSEEK,
        baseUrl = prefs.getString(KEY_BASE_URL, null).orEmpty(),
        model = prefs.getString(KEY_MODEL, null).orEmpty(),
        apiKey = prefs.getString(KEY_API_KEY, null).orEmpty(),
    )

    private companion object {
        const val FILE_NAME = "ai_provider_prefs"
        const val KEY_PROVIDER = "provider"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY = "api_key"
    }
}
