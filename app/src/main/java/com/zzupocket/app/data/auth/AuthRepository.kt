package com.zzupocket.app.data.auth

import com.zzupocket.app.core.AppError
import com.zzupocket.app.core.crypto.Crypto
import com.zzupocket.app.data.ecard.EcardClient

/**
 * 认证中枢：统一管理 CAS 与一卡通两套令牌的生命周期。
 *
 * 令牌关系：
 *   CAS userToken（约 1 个月）
 *        └─(host/open)→ tid ─(getToken)→ 一卡通 accessToken（45 分钟）
 *
 * ⚠️ **不持久化密码**：userToken 过期后需要用户重新登录一次。换来的好处是设备上
 * 不存在可直接复用的账号密码。首次登录时把本设备在统一认证安全中心设为可信设备后，
 * 重新登录不再需要短信验证码。
 */
class AuthRepository(
    private val store: TokenStore,
    private val cas: CasClient,
    private val ecard: EcardClient,
) {

    sealed interface LoginStep {
        data class Success(val account: String) : LoginStep
        data class NeedSms(val challenge: CasClient.MfaChallenge) : LoginStep
    }

    data class TokenStatus(
        val loggedIn: Boolean,
        val casExpiresAt: Long?,
        val lastLoginAt: Long,
        val ecardTokenAt: Long,
        val account: String?,
    )

    // ── CAS 令牌 ──────────────────────────────────────────────────────

    /** 返回可用的 userToken；即将过期或已过期时返回 null（需要重新登录） */
    fun currentCasToken(): String? {
        val token = store.casUserToken ?: return null
        val expiresAt = Crypto.jwtExpiresAtMillis(token) ?: return null
        val remaining = expiresAt - System.currentTimeMillis()
        return if (remaining > CasClient.TOKEN_SAFETY_WINDOW_MS) token else null
    }

    fun isLoggedIn(): Boolean = currentCasToken() != null

    fun casTokenExpiresAt(): Long? = store.casUserToken?.let { Crypto.jwtExpiresAtMillis(it) }

    /** 取可用的 userToken（教务等系统直接用这个当 Authorization） */
    fun requireCasToken(): String =
        currentCasToken() ?: throw AppError.ReLoginRequired()

    // ── 登录编排（账密 → 可能再要短信码 → 换令牌）─────────────────────

    suspend fun beginLogin(account: String, password: String): LoginStep {
        val detect = cas.detectMfa(account, password)
        val challenge = detect.challenge
        if (challenge != null) {
            // 立刻发一次短信，用户不用再点一次
            cas.sendSmsCode(challenge)
            return LoginStep.NeedSms(challenge)
        }
        // ⚠️ 即使不需要 MFA，也必须把 detect 返回的 state 传回去；
        // 传空字符串服务端会直接 400（exception.mfa.verify.error）。
        return persist(cas.login(account, password, mfaState = detect.state), account)
    }

    suspend fun resendSmsCode(challenge: CasClient.MfaChallenge) {
        cas.sendSmsCode(challenge)
    }

    suspend fun submitSmsCode(
        account: String,
        password: String,
        challenge: CasClient.MfaChallenge,
        code: String,
    ): LoginStep.Success {
        cas.verifySmsCode(challenge, code)
        // 同样要带上 state —— 它来自 /mfa/detect，短信校验通过后一并交给 passwordLogin
        val result = cas.login(account, password, mfaState = challenge.state)
        return persist(result, account)
    }

    private fun persist(result: CasClient.LoginResult, account: String): LoginStep.Success {
        store.casUserToken = result.userToken
        store.casRefreshToken = result.refreshToken
        store.lastLoginAt = System.currentTimeMillis()
        // CAS 令牌换了，一卡通的二级令牌一起作废
        store.ecardAccessToken = null
        store.ecardRefreshToken = null
        store.ecardTokenAt = 0L
        return LoginStep.Success(account)
    }

    fun logout() {
        store.clearAll()
    }

    // ── 一卡通令牌（二级）──────────────────────────────────────────────

    /**
     * 拿到可用的 accessToken。命中缓存就直接用，否则用 userToken 换 tid 再换令牌。
     * 一卡通官方周期是 45 分钟，这里按 40 分钟提前刷新。
     */
    suspend fun requireEcardAccessToken(): String {
        val cached = store.ecardAccessToken
        val age = System.currentTimeMillis() - store.ecardTokenAt
        if (!cached.isNullOrEmpty() && age in 0 until EcardClient.TOKEN_TTL_MS) return cached

        val userToken = requireCasToken()
        val tid = ecard.fetchTid(userToken)
        val tokens = ecard.fetchTokens(tid)
        val now = System.currentTimeMillis()
        store.ecardAccessToken = tokens.accessToken
        store.ecardRefreshToken = tokens.refreshToken
        store.ecardTokenAt = now
        return tokens.accessToken
    }

    fun status(account: String?): TokenStatus = TokenStatus(
        loggedIn = isLoggedIn(),
        casExpiresAt = casTokenExpiresAt(),
        lastLoginAt = store.lastLoginAt,
        ecardTokenAt = store.ecardTokenAt,
        account = account,
    )
}
