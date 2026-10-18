package com.zzupocket.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.LoginInput
import com.zzupocket.app.core.toUserMessage
import com.zzupocket.app.data.auth.AuthRepository
import com.zzupocket.app.data.auth.CasClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoginPhase { Credentials, NeedSms, Done }

data class LoginUiState(
    val account: String = "",
    val password: String = "",
    val smsCode: String = "",
    val phase: LoginPhase = LoginPhase.Credentials,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** 密码的输入体检结果：非 null 时禁止提交，并把原因显示在密码框下面 */
    val inputIssue: LoginInput.Issue? = null,
    /**
     * 「重新发送验证码」的冷却剩余秒数。
     *
     * 学校侧约 1 分钟才允许重发，而且短信本身也可能要 1 分钟才到 —— 没有这个倒计时，
     * 用户会以为没发出去而连点重发，结果真码反而来得更晚、还容易把先到的码作废。
     */
    val resendCooldownSec: Int = 0,
)

class LoginViewModel : ViewModel() {

    private val auth: AuthRepository = AppGraph.authRepository

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    /** 短信步骤需要复用账密，只存在内存里，不落盘 */
    private var challenge: CasClient.MfaChallenge? = null
    private var pendingAccount: String = ""
    private var pendingPassword: String = ""
    private var cooldownJob: Job? = null

    /** 发码时刻存在本地，这样退出登录页再进来倒计时是"接着算"而不是重新开始 */
    private val loginPrefs = AppGraph.loginPrefsStore

    init {
        // 把上一次的冷却接回来。
        //
        // 学校的重发限流按账号算，不会因为我们重建了这个 ViewModel 就重置：
        // 如果倒计时只活在内存里，用户退出去重进一次就能再点「重新发送」，
        // 点下去只会再失败一次 —— 看起来就像按钮坏了。
        viewModelScope.launch {
            val sentAt = runCatching { loginPrefs.lastSmsSentAt() }.getOrNull() ?: return@launch
            startResendCooldown(LoginInput.cooldownLeftSec(sentAt, System.currentTimeMillis(), RESEND_COOLDOWN_SEC))
        }
    }

    fun onAccountChange(v: String) = _state.update { it.copy(account = v.trim(), error = null) }

    fun onPasswordChange(v: String) = _state.update {
        it.copy(password = v, error = null, inputIssue = LoginInput.inspect(v))
    }

    fun onSmsCodeChange(v: String) = _state.update { it.copy(smsCode = v.filter(Char::isDigit).take(8), error = null) }

    /**
     * 用户确认后把全角字符转成半角。
     *
     * 刻意**不做自动转换**：万一真有人把全角字符设进了密码，自动转换会把正确的密码改坏。
     */
    fun fixFullWidth() {
        val fixed = LoginInput.toHalfWidth(_state.value.password)
        _state.update { it.copy(password = fixed, error = null, inputIssue = LoginInput.inspect(fixed)) }
    }

    fun submitCredentials() {
        val s = _state.value
        if (s.account.isEmpty() || s.password.isEmpty()) {
            _state.update { it.copy(error = "请填写学号和密码") }
            return
        }
        // 输入体检：中文 / 全角 / 过长都在这里拦下来。
        // 不拦的话，用户看到的会是服务端一句"凭据不对"（HTTP 401 Bad credentials），
        // 或者 RSA 加密阶段抛出的英文技术异常 —— 两种都让人完全无从下手。
        LoginInput.inspect(s.password)?.let { issue ->
            _state.update { it.copy(inputIssue = issue, error = issue.message) }
            return
        }
        LoginInput.inspect(s.account)?.let { issue ->
            _state.update { it.copy(error = "学号里有异常字符：${issue.message}") }
            return
        }

        pendingAccount = s.account
        pendingPassword = s.password
        _state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                when (val step = auth.beginLogin(pendingAccount, pendingPassword)) {
                    is AuthRepository.LoginStep.NeedSms -> {
                        challenge = step.challenge
                        markSmsSent()
                        _state.update {
                            it.copy(
                                busy = false,
                                phase = LoginPhase.NeedSms,
                                notice = "验证码已发送，可能要 1 分钟才到达；" +
                                    "${RESEND_COOLDOWN_SEC} 秒内不能重新发送",
                            )
                        }
                    }
                    is AuthRepository.LoginStep.Success -> {
                        finishLogin()
                    }
                }
            } catch (e: Throwable) {
                _state.update { it.copy(busy = false, error = e.msg()) }
            }
        }
    }

    fun resendSms() {
        val c = challenge ?: return
        val left = _state.value.resendCooldownSec
        if (left > 0) {
            _state.update { it.copy(error = "刚发过一次，请再等 $left 秒") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                auth.resendSmsCode(c)
                markSmsSent()
                _state.update {
                    it.copy(
                        busy = false,
                        notice = "验证码已重新发送，可能要 1 分钟才到达；" +
                            "${RESEND_COOLDOWN_SEC} 秒内不能重新发送",
                    )
                }
            } catch (e: Throwable) {
                // 服务端拒绝（多半是频率限制）时**也要进冷却**：否则用户会继续连点。
                // 而且要在提示里把"还得等多久"说清楚，不能只说一句"失败了"——
                // 用户唯一的下一步就是等，却不知道等多久。
                markSmsSent()
                _state.update {
                    it.copy(
                        busy = false,
                        error = "${e.msg()}（约 ${RESEND_COOLDOWN_SEC} 秒后才能再发）",
                    )
                }
            }
        }
    }

    fun submitSms() {
        val c = challenge ?: return
        val code = _state.value.smsCode
        if (code.length < 4) {
            _state.update { it.copy(error = "请输入短信验证码") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                auth.submitSmsCode(pendingAccount, pendingPassword, c, code)
                finishLogin()
            } catch (e: Throwable) {
                // 验证码错了就把输入清掉，避免用户对着旧码反复点提交
                _state.update { it.copy(busy = false, error = e.msg(), smsCode = "") }
            }
        }
    }

    /**
     * 登录接口返回 Success 后，再确认令牌真的可用。
     *
     * 认证服务如果改了 idToken 的格式（不再是带 exp 的 JWT），[AuthRepository.persist]
     * 仍会成功，但主页面的 `isLoggedIn()` 会一直是 false —— 表现就是
     * "认证成功、闪回主页、仍显示未登录、没有任何提示"。这里停下来把令牌形态
     * （只含段数/长度/字段名，不含值）显示出来，不再静默。
     */
    private fun finishLogin() {
        if (auth.isLoggedIn()) {
            _state.update { it.copy(busy = false, phase = LoginPhase.Done) }
        } else {
            _state.update {
                it.copy(
                    busy = false,
                    phase = LoginPhase.Credentials,
                    error = "登录成功，但令牌无法使用（${auth.casTokenShape()}）。请把这句话反馈给开发者。",
                )
            }
        }
    }

    /**
     * 记下"刚刚发过一次验证码"：**先落盘，再起倒计时**。
     *
     * 落盘这一半是给"用户马上退出再进来"兜底的（见 init）；两半都做，
     * 内存里的倒计时负责每秒刷新按钮，盘上的时刻负责跨页面接着算。
     */
    private suspend fun markSmsSent() {
        runCatching { loginPrefs.setSmsSentAt(System.currentTimeMillis()) }
        startResendCooldown(RESEND_COOLDOWN_SEC)
    }

    /**
     * 起倒计时。
     *
     * @param from 从第几秒开始倒数（跨页面回来时传剩余秒数，而不是 60）
     */
    private fun startResendCooldown(from: Int) {
        cooldownJob?.cancel()
        if (from <= 0) {
            _state.update { it.copy(resendCooldownSec = 0) }
            return
        }
        cooldownJob = viewModelScope.launch {
            for (left in from downTo 1) {
                _state.update { it.copy(resendCooldownSec = left) }
                delay(1_000)
            }
            _state.update { it.copy(resendCooldownSec = 0) }
        }
    }

    /** 把各类异常翻译成用户看得懂的一句话（先过一遍短信专用的友好化） */
    private fun Throwable.msg(): String {
        LoginInput.friendlySmsError(message.orEmpty())?.let { return it }
        return toUserMessage()
    }

    private companion object {
        /** 学校侧约 1 分钟才允许重发，且短信本身也可能要 1 分钟才到 */
        const val RESEND_COOLDOWN_SEC = 60
    }
}
