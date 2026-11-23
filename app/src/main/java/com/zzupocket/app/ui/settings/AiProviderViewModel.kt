package com.zzupocket.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.ai.AiEndpoint
import com.zzupocket.app.data.ai.AiProvider
import com.zzupocket.app.data.ai.AiProviderConfig
import com.zzupocket.app.data.ai.AiSummaryClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AiSettingsUi(
    val provider: AiProvider = AiProvider.DEEPSEEK,
    val baseUrl: String = "",
    val model: String = "",
    /** 只存用户这次新输入的 Key；留空表示沿用已保存的 Key */
    val keyInput: String = "",
    val hasKey: Boolean = false,
    val showKey: Boolean = false,
    val testing: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

class AiProviderViewModel : ViewModel() {
    private val store = AppGraph.aiProviderStore
    private val client = AiSummaryClient()

    private val _ui = MutableStateFlow(
        store.config.value.let { config ->
            AiSettingsUi(
                provider = config.provider,
                baseUrl = config.baseUrl,
                model = config.model,
                hasKey = config.configured,
            )
        },
    )
    val ui: StateFlow<AiSettingsUi> = _ui.asStateFlow()

    fun selectProvider(provider: AiProvider) {
        _ui.update { it.copy(provider = provider, message = null) }
    }

    fun setBaseUrl(value: String) = _ui.update { it.copy(baseUrl = value, message = null) }

    fun setModel(value: String) = _ui.update { it.copy(model = value, message = null) }

    fun setKey(value: String) = _ui.update { it.copy(keyInput = value, message = null) }

    fun toggleShowKey() = _ui.update { it.copy(showKey = !it.showKey) }

    fun save() {
        val state = _ui.value
        val error = AiEndpoint.validate(state.baseUrl)
        if (error != null) {
            _ui.update { it.copy(message = error, messageIsError = true) }
            return
        }
        val saved = store.save(
            provider = state.provider,
            baseUrl = state.baseUrl,
            model = state.model,
            apiKey = state.keyInput.takeIf(String::isNotBlank),
        )
        _ui.update {
            it.copy(
                keyInput = "",
                hasKey = store.config.value.configured,
                message = if (saved) "已保存" else "保存失败：系统密钥存储不可用",
                messageIsError = !saved,
            )
        }
    }

    fun clearKey() {
        val cleared = store.clearKey()
        _ui.update {
            it.copy(
                keyInput = "",
                hasKey = store.config.value.configured,
                message = if (cleared) "已清除 API Key" else "清除失败：系统密钥存储不可用",
                messageIsError = !cleared,
            )
        }
    }

    fun test() {
        if (_ui.value.testing) return
        val state = _ui.value
        val baseError = AiEndpoint.validate(state.baseUrl)
        if (baseError != null) {
            _ui.update { it.copy(message = baseError, messageIsError = true) }
            return
        }
        val config = AiProviderConfig(
            provider = state.provider,
            baseUrl = state.baseUrl,
            model = state.model,
            apiKey = state.keyInput.ifBlank { store.config.value.apiKey },
        )
        if (!config.configured) {
            _ui.update { it.copy(message = "请先填写 API Key", messageIsError = true) }
            return
        }

        viewModelScope.launch {
            _ui.update { it.copy(testing = true, message = null) }
            val result = runCatching { client.test(config) }
            _ui.update {
                it.copy(
                    testing = false,
                    message = result.fold(
                        onSuccess = { reply -> "连接成功：${config.effectiveModel} · $reply" },
                        onFailure = { e -> e.message ?: "连接失败" },
                    ),
                    messageIsError = result.isFailure,
                )
            }
        }
    }
}
