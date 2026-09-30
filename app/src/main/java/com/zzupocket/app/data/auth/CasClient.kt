package com.zzupocket.app.data.auth

import com.zzupocket.app.core.AppError
import com.zzupocket.app.core.crypto.Crypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/**
 * 郑州大学统一认证（App 端 JWT 流程）。
 *
 * 协议逐条对齐 ZZU.Py 的 `CASClient`（MIT），没有一处是靠猜的：
 *
 *  1. GET  /token/jwt/publicKey                        → PEM 公钥
 *  2. POST /token/mfa/detect?username&password&deviceId → {state, need, mfaTypeSecurePhone}
 *     （需要 MFA 时）
 *  3. GET  /token/mfa/initByType/securephone?state      → {gid, attestServerUrl}
 *  4. POST {attest}/api/guard/securephone/send          → 发短信
 *  5. POST {attest}/api/guard/securephone/valid         → 校验，data.status 必须为 2
 *  6. POST /token/password/passwordLogin?username&password&appId&osType&geo&deviceId&clientId&mfaState
 *                                                       → {idToken, refreshToken}
 *
 * ⚠️ 两个易错点：
 *  - 第 2、6 步的参数在 **query string 上**，不是 JSON body（POST 一个空 body）。
 *  - 登录相关请求的 UA 必须是 `SWSuperApp/1.1.1()`，只有取公钥那一步用 `okhttp/3.12.1`。
 */
class CasClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val deviceId: String,
) {

    @Serializable
    private data class Envelope<T>(val code: Int = -1, val message: String? = null, val data: T? = null)

    @Serializable
    private data class LoginData(val idToken: String, val refreshToken: String)

    @Serializable
    private data class MfaDetectData(
        val state: String = "",
        val need: Boolean = false,
        val mfaTypeSecurePhone: Boolean = false,
    )

    @Serializable
    private data class MfaInitData(val gid: String = "", val attestServerUrl: String? = null)

    @Serializable
    private data class MfaVerifyData(val status: Int = -1, val result: String? = null)

    data class LoginResult(val userToken: String, val refreshToken: String)

    data class MfaChallenge(
        val state: String,
        val gid: String,
        val attestBaseUrl: String,
    )

    /**
     * /mfa/detect 的结果。
     *
     * ⚠️ **`state` 必须始终保留并传给 passwordLogin**，即使 `required == false`。
     * 传空字符串会让服务端返回 400 `exception.mfa.verify.error`
     * （已在电脑端对这个接口实测确认）。这是最容易漏的一处。
     */
    data class MfaDetectResult(
        val state: String,
        val required: Boolean,
        val challenge: MfaChallenge?,
    )

    private var publicKey: RSAPublicKey? = null

    private suspend fun ensurePublicKey(): RSAPublicKey = publicKey ?: withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(PUBLIC_KEY_URL)
            .header("User-Agent", UA_PUBLIC_KEY)
            .get()
            .build()
        val pem = execute(request)
        val key = try {
            Crypto.parseRsaPublicKey(pem.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            throw AppError.Parse("认证服务公钥格式无效", e)
        }
        publicKey = key
        key
    }

    /**
     * 第一步：检测是否需要 MFA，并拿到 state。
     *
     * 返回的 `state` 无论是否要求 MFA 都必须保留，登录时要原样传回去。
     */
    suspend fun detectMfa(account: String, password: String): MfaDetectResult {
        val key = ensurePublicKey()
        val url = MFA_DETECT_URL.toHttpUrl().newBuilder()
            .addQueryParameter("username", Crypto.encryptAndEncode(account, key))
            .addQueryParameter("password", Crypto.encryptAndEncode(password, key))
            .addQueryParameter("deviceId", deviceId)
            .build()

        val body = execute(
            Request.Builder().url(url).header("User-Agent", UA_APP)
                .post("".toRequestBody(null)).build(),
        )
        val env = try {
            json.decodeFromString<Envelope<MfaDetectData>>(body)
        } catch (e: Exception) {
            throw AppError.Parse("MFA 检测响应无法解析", e)
        }
        if (env.code != 0) {
            if (env.code == 1) throw AppError.BadCredentials(env.message ?: "学号或密码错误")
            throw AppError.Server(env.code, env.message ?: "MFA 检测失败")
        }
        val data = env.data ?: throw AppError.Parse("MFA 检测响应缺少 data")
        if (!data.need) {
            // 不需要 MFA —— 但 state 仍要留着给 login 用
            return MfaDetectResult(state = data.state, required = false, challenge = null)
        }
        if (!data.mfaTypeSecurePhone) {
            throw AppError.Server(0, "该账号不支持手机号 MFA，无法在此 App 登录")
        }
        // 初始化手机号 MFA，拿 gid
        val initUrl = MFA_INIT_URL.toHttpUrl().newBuilder()
            .addQueryParameter("state", data.state)
            .build()
        val initBody = execute(
            Request.Builder().url(initUrl).header("User-Agent", UA_APP).get().build(),
        )
        val initEnv = try {
            json.decodeFromString<Envelope<MfaInitData>>(initBody)
        } catch (e: Exception) {
            throw AppError.Parse("手机号 MFA 初始化响应无法解析", e)
        }
        val init = initEnv.data
        if (initEnv.code != 0 || init == null || init.gid.isEmpty()) {
            throw AppError.Server(initEnv.code, initEnv.message ?: "手机号 MFA 初始化失败")
        }
        return MfaDetectResult(
            state = data.state,
            required = true,
            challenge = MfaChallenge(
                state = data.state,
                gid = init.gid,
                attestBaseUrl = init.attestServerUrl?.takeIf { it.isNotBlank() } ?: ATTEST_BASE,
            ),
        )
    }

    /** 发送短信验证码 */
    suspend fun sendSmsCode(challenge: MfaChallenge) {
        val body = execute(
            Request.Builder()
                .url(attestUrl(challenge, "api/guard/securephone/send"))
                .header("User-Agent", UA_APP)
                .post(json.encodeToString(SendSmsBody.serializer(), SendSmsBody(gid = challenge.gid))
                    .toRequestBody(JSON_MEDIA))
                .build(),
        )
        val env = try {
            json.decodeFromString<Envelope<MfaVerifyData>>(body)
        } catch (e: Exception) {
            throw AppError.Parse("短信发送响应无法解析", e)
        }
        if (env.code != 0) throw AppError.Server(env.code, env.message ?: "短信验证码发送失败")
    }

    /** 校验短信验证码 */
    suspend fun verifySmsCode(challenge: MfaChallenge, code: String) {
        val payload = json.encodeToString(
            VerifySmsBody.serializer(),
            VerifySmsBody(gid = challenge.gid, code = code),
        )
        val body = execute(
            Request.Builder()
                .url(attestUrl(challenge, "api/guard/securephone/valid"))
                .header("User-Agent", UA_APP)
                .post(payload.toRequestBody(JSON_MEDIA))
                .build(),
        )
        val env = try {
            json.decodeFromString<Envelope<MfaVerifyData>>(body)
        } catch (e: Exception) {
            throw AppError.Parse("验证码校验响应无法解析", e)
        }
        if (env.code != 0) throw AppError.Server(env.code, env.message ?: "验证码校验失败")
        if (env.data?.status != 2) throw AppError.Server(0, "验证码错误或已过期")
    }

    /** 最后一步：账密登录，拿 idToken / refreshToken */
    suspend fun login(
        account: String,
        password: String,
        mfaState: String,
    ): LoginResult {
        val key = ensurePublicKey()
        val url = LOGIN_URL.toHttpUrl().newBuilder()
            .addQueryParameter("username", Crypto.encryptAndEncode(account, key))
            .addQueryParameter("password", Crypto.encryptAndEncode(password, key))
            .addQueryParameter("appId", APP_ID)
            .addQueryParameter("osType", OS_TYPE)
            .addQueryParameter("geo", "")
            .addQueryParameter("deviceId", deviceId)
            .addQueryParameter("clientId", "")
            .addQueryParameter("mfaState", mfaState)
            .build()

        val body = execute(
            Request.Builder().url(url).header("User-Agent", UA_APP)
                .post("".toRequestBody(null)).build(),
        )
        val env = try {
            json.decodeFromString<Envelope<LoginData>>(body)
        } catch (e: Exception) {
            throw AppError.Parse("登录响应无法解析", e)
        }
        val data = env.data
        if (env.code != 0 || data == null) {
            val msg = env.message ?: "未知错误"
            if (env.code != 0 && msg.contains("密码")) throw AppError.BadCredentials(msg)
            throw AppError.Server(env.code, msg)
        }
        return LoginResult(userToken = data.idToken, refreshToken = data.refreshToken)
    }

    private fun attestUrl(challenge: MfaChallenge, path: String): String =
        challenge.attestBaseUrl.trimEnd('/') + "/" + path.trimStart('/')

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw AppError.Network("无法连接认证服务器，请检查网络", e)
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (it.isSuccessful) return@use text

            // 服务端的 message 字段通常直接说明原因（例如 exception.mfa.verify.error），
            // 一定要带出来 —— 否则界面上只剩一个 HTTP 状态码，无法定位。
            val serverMsg = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
            val detail = buildString {
                append("HTTP ${it.code}")
                if (!serverMsg.isNullOrBlank()) append("：$serverMsg")
                append("（${request.url.encodedPath}）")
            }
            if (it.code == 401 || it.code == 403) throw AppError.BadCredentials(detail)
            throw AppError.Server(it.code, detail)
        }
    }

    @Serializable
    private data class SendSmsBody(val gid: String)

    @Serializable
    private data class VerifySmsBody(val gid: String, val code: String)

    companion object {
        const val APP_VERSION = "SWSuperApp/1.1.1"
        const val APP_ID = "com.supwisdom.zzu"
        const val OS_TYPE = "android"

        private val UA_APP = "$APP_VERSION()"
        private const val UA_PUBLIC_KEY = "okhttp/3.12.1"

        private const val BASE = "https://cas.s.zzu.edu.cn"
        private const val PUBLIC_KEY_URL = "$BASE/token/jwt/publicKey"
        private const val LOGIN_URL = "$BASE/token/password/passwordLogin"
        private const val MFA_DETECT_URL = "$BASE/token/mfa/detect"
        private const val MFA_INIT_URL = "$BASE/token/mfa/initByType/securephone"
        private const val ATTEST_BASE = "$BASE/attest/api/guard"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** userToken 剩余不足这个时间就认为需要重新登录（对齐 zzupy 的 15 分钟提前量） */
        val TOKEN_SAFETY_WINDOW_MS: Long = TimeUnit.MINUTES.toMillis(15)
    }
}
