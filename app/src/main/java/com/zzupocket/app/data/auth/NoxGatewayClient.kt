package com.zzupocket.app.data.auth

import android.util.Log
import com.zzupocket.app.core.AppError
import com.zzupocket.app.core.net.HttpClients
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 学校统一身份验证网关（auth.zzu 门户）的自动认证。
 *
 * 背景：学校调整了教务系统的访问方式——教务请求在门户会话过期时会被 302 重定向
 * 到验证门户，与 app 版本无关，浏览器同样受影响。门户的信任是**设备/IP 级**：
 * 完成一次验证后，该设备的直连请求（无需携带门户会话）即被放行。
 *
 * 自动认证 = 在后台完成一次标准登录流程：
 *
 *  ① 打开门户地址（含应用标识与回跳地址）→ 建立门户会话
 *  ② 查询可用验证方式，取账号密码类（type=LDAP，兜底本地账号）
 *  ③ 取公钥
 *  ④ 密码 RSA/PKCS#1 v1.5 加密
 *  ⑤ 提交登录（requestinfo/terminalType 为门户要求的通用头）
 *  ⑥ 跟随登录响应的回跳地址——门户在这一步完成信任标记
 *
 * 成功后**直接重试原教务请求即可**——信任在服务端按设备标记，不依赖把门户
 * 会话转发给教务请求。
 *
 * ⚠️ 安全边界：**绝不自动重试登录**（防止触发门户的失败锁定策略），一次失败
 * 即停；图形验证码与设备绑定等分支降级为明确报错，交给用户手动完成。
 *
 * ⚠️ 门户属于学校外部系统，字段可能变化——路径/字段集中在 [Companion]，
 * 变了只动这里。
 */
class NoxGatewayClient(private val store: TokenStore) {

    /** 独立 client：跟重定向（portal 链需要）+ 内存 cookie jar（会话链依赖） */
    private val client: OkHttpClient = HttpClients.builder(minIntervalMs = 300)
        .followRedirects(true)
        .cookieJar(MemoryCookieJar())
        .build()

    /** 单飞：多个教务请求同时被拦时只跑一轮登录 */
    private val loginMutex = Mutex()

    /** 冷却：一轮失败后 [COOLDOWN_MS] 内不再自动重试（防打爆/防锁定） */
    @Volatile
    private var cooldownUntil = 0L

    /** 最近一次被拦时看到的门户地址（手动"立即认证"优先用它） */
    @Volatile
    private var lastPortalUrl: String? = null

    /** 是否具备自动认证的凭据（老用户升级后未重登前为 false → 提示要先重登一次） */
    fun hasStoredCredentials(): Boolean =
        !store.noxAccount.isNullOrBlank() && !store.noxPassword.isNullOrEmpty()

    /**
     * 教务当前是否被网关拦截（探测：裸 GET jwxt 接口，302=被拦，401/200=已放行）。
     * ⚠️ 网络调用必须包 IO：调用方（管理页按钮）在主线程，裸跑会抛
     * NetworkOnMainThreadException，被我 catch 后显示成"网络不可达"
     * （2026-11-13 真机实测踩坑）。
     */
    suspend fun probeJwxt(): GatewayProbe = withContext(Dispatchers.IO) {
        val probeClient = HttpClients.eas // followRedirects=false：要看 302 本身
        try {
            val request = Request.Builder()
                .url(PROBE_URL)
                .get()
                .build()
            probeClient.newCall(request).execute().use { r ->
                when {
                    r.code in 300..399 -> GatewayProbe.BLOCKED
                    r.isSuccessful -> GatewayProbe.OPEN
                    else -> GatewayProbe.OPEN // 401/403 = 网关放行、教务鉴权层拒绝（正常）
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "探测教务可达性失败（网络不通）", e)
            GatewayProbe.UNREACHABLE
        }
    }

    enum class GatewayProbe { OPEN, BLOCKED, UNREACHABLE }

    /** 手动"立即认证"：优先用最近被拦时看到的门户地址，兜底内置地址 */
    suspend fun authNow(): Boolean {
        val portalUrl = lastPortalUrl ?: DEFAULT_PORTAL_URL
        return restoreTrustInternal(portalUrl, ignoreCooldown = true)
    }

    /**
     * 教务请求被拦时的入口。[portalUrl] 是 302 Location 里的认证门户地址
     * （含 app_uuid/redirect_url，由调用方透传）。返回 true = 已恢复信任，
     * 调用方应重试原请求；false = 未能恢复（调用方抛错走界面提示）。
     */
    suspend fun restoreTrust(portalUrl: String): Boolean {
        lastPortalUrl = portalUrl
        return restoreTrustInternal(portalUrl, ignoreCooldown = false)
    }

    private suspend fun restoreTrustInternal(portalUrl: String, ignoreCooldown: Boolean): Boolean {
        val account = store.noxAccount
        val password = store.noxPassword
        if (account.isNullOrBlank() || password.isNullOrEmpty()) {
            Log.w(TAG, "网关自动认证缺凭据（用户尚未在 app 内重新登录过）")
            return false
        }
        if (!ignoreCooldown && System.currentTimeMillis() < cooldownUntil) {
            Log.d(TAG, "网关自动认证冷却中，跳过")
            return false
        }
        return loginMutex.withLock {
            // 双重检查：等锁期间别的请求可能已完成认证
            if (!ignoreCooldown && System.currentTimeMillis() < cooldownUntil) return@withLock false
            try {
                val ok = login(account, password, portalUrl)
                if (ok) {
                    store.noxAuthAt = System.currentTimeMillis()
                    Log.i(TAG, "网关自动认证成功")
                } else {
                    cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
                }
                ok
            } catch (e: Exception) {
                Log.w(TAG, "网关自动认证异常", e)
                cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
                false
            }
        }
    }

    /** 跑完整登录链；任何一步失败返回 false 并记日志（细节在 logcat）。
     *  ⚠️ 全链包 IO：管理页"立即认证"从主线程协程进来，裸跑会主线程网络异常 */
    private suspend fun login(account: String, password: String, portalUrl: String): Boolean =
        withContext(Dispatchers.IO) { loginInternal(account, password, portalUrl) }

    private suspend fun loginInternal(account: String, password: String, portalUrl: String): Boolean {
        // ① portal 链：建立网关会话 cookie（跟随 302 到 /authn/base/）。
        // ⚠️ nox 门户**时通时断**（2026-11-12 实测：同一设备 20 分钟内先通后超时），
        // 疑似负载均衡部分后端不可达——重试几次能撞上活的。
        val landed = (1..PORTAL_ATTEMPTS).firstNotNullOfOrNull { attempt ->
            val ok = httpGet(portalUrl)
            if (ok == null) {
                Log.w(TAG, "portal 链第 $attempt 次失败")
                Thread.sleep(PORTAL_RETRY_DELAY_MS)
                null
            } else {
                ok
            }
        }
        Log.i(TAG, "portal 链落地：${landed != null}")
        if (landed == null) return false

        // ② current/status：拿 LDAP instanceId
        val statusBody = httpPostJson(STATUS_URL, "{}")
            ?: run { Log.w(TAG, "current/status 无响应"); return false }
        val instanceId = JSONObject(statusBody)
            .optJSONObject("data")
            ?.optJSONArray("nextAction")
            ?.let { findInstanceId(it) }
            ?: run {
                Log.w(TAG, "current/status 里找不到 LDAP 实例：$statusBody")
                return false
            }
        if (com.zzupocket.app.BuildConfig.DEBUG) Log.i(TAG, "LDAP instanceId=$instanceId")

        // ③ cypher：RSA 公钥
        val pubKey = JSONObject(httpGetBody(CYPHER_URL) ?: return false)
            .optJSONObject("data")?.optString("pubKey").orEmpty()
        if (pubKey.isBlank()) { Log.w(TAG, "cypher 无公钥"); return false }

        // ④ RSA 加密密码（PKCS#1 v1.5，与页面 JSEncrypt 同格式）
        val credential = try {
            rsaEncrypt(pubKey, password)
        } catch (e: Exception) {
            Log.w(TAG, "密码加密失败", e); return false
        }

        // ⑤ login
        val payload = JSONObject().apply {
            put(
                "authnProperties",
                JSONArray().put(
                    JSONObject().apply {
                        put("principal", account)
                        put("credential", credential)
                        put("instanceId", instanceId)
                        put("extParams", JSONObject())
                    },
                ),
            )
            put("device", JSONObject())
            put("env", JSONObject())
        }
        val loginBody = httpPostJson(LOGIN_URL, payload.toString())
            ?: run { Log.w(TAG, "login 无响应"); return false }
        val loginJson = JSONObject(loginBody)
        val status = loginJson.optInt("status", -1)
        if (com.zzupocket.app.BuildConfig.DEBUG) Log.i(TAG, "login 响应：status=$status body=${loginBody.take(300)}")
        // 状态语义按实测校正：200/204 视为成功；其余（验证码/绑定/密码错误）一律失败
        if (status != 200 && status != 204) return false

        // ⑥ ⚠️ **关键收尾**：浏览器流程在 login 200 后会 `window.location.href =
        // data.toUrls.redirectUrl`——网关在这一跳给设备打"信任标记"。止步于 200
        // 的话信任并未建立（2026-11-13 真机实测：login 200 但教务仍被拦，重复
        // 认证时 portal 还会 404/401）。所以必须补上这一跳。
        val redirectUrl = loginJson.optJSONObject("data")
            ?.optJSONObject("toUrls")?.optString("redirectUrl")
        if (!redirectUrl.isNullOrBlank()) {
            val follow = httpGet(redirectUrl)
            Log.i(TAG, "login 后回跳 ${redirectUrl.take(120)} → ${if (follow != null) "已到达" else "未完成"}")
        } else {
            Log.i(TAG, "login 响应无 toUrls.redirectUrl（可能网关侧流程变化）")
        }
        return true
    }

    /** 从 current/status 的 nextAction 里找 LDAP（兜底 LOCAL_UP）的 instanceId */
    private fun findInstanceId(nextAction: JSONArray): String? {
        var localFallback: String? = null
        for (i in 0 until nextAction.length()) {
            val item = nextAction.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "LDAP" -> return item.optString("instanceId")
                "LOCAL_UP" -> localFallback = item.optString("instanceId")
            }
        }
        return localFallback
    }

    private fun httpGet(url: String): String? = try {
        client.newCall(Request.Builder().url(url.toHttpUrl()).get().build()).execute().use { r ->
            val body = r.body?.string()
            if (!r.isSuccessful) Log.w(TAG, "GET 非成功状态：HTTP ${r.code} $url")
            body?.takeIf { r.isSuccessful }
        }
    } catch (e: Exception) {
        Log.w(TAG, "GET 失败：$url", e); null
    }

    private fun httpGetBody(url: String): String? = httpGet(url)

    private fun httpPostJson(url: String, body: String): String? = try {
        val request = Request.Builder()
            .url(url.toHttpUrl())
            .header("requestinfo", JSONObject().put("lang", "zh-CN").put("seqNo", seqNo()).toString())
            .header("terminalType", "browser")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { r ->
            r.body?.string()?.takeIf { r.isSuccessful }
        }
    } catch (e: Exception) {
        Log.w(TAG, "POST 失败：$url", e); null
    }

    /** RSA/PKCS#1 v1.5 加密（公钥为 base64 DER SPKI，与页面 JSEncrypt 输入同格式） */
    private fun rsaEncrypt(pubKeyBase64: String, plain: String): String {
        val key: PublicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(android.util.Base64.decode(pubKeyBase64, android.util.Base64.DEFAULT)))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return android.util.Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), android.util.Base64.DEFAULT)
    }

    private fun seqNo(): String = buildString {
        repeat(32) { append('0' + (0..9).random()) }
    }

    private fun String.toRequestBody() =
        toRequestBodyJson()

    private fun String.toRequestBodyJson() =
        okhttp3.RequestBody.create("application/json; charset=utf-8".toMediaType(), this)

    /** 内存 cookie jar：只服务一次登录链的生命周期 */
    private class MemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store[url.host] = cookies
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
    }

    companion object {
        private const val TAG = "NoxGateway"

        const val HOST = "nox.zzu.edu.cn"

        /** 一轮失败后的冷却（学校可能有失败锁定策略，不自动重试） */
        const val COOLDOWN_MS = 2L * 60 * 1000

        private const val STATUS_URL = "https://$HOST/runtime/api/v3/am/authn/current/status"
        private const val LOGIN_URL = "https://$HOST/runtime/api/v3/am/authn/login"
        private const val CYPHER_URL = "https://$HOST/runtime/uiapi/v1/config/cypher"

        /** 探测教务是否被拦用的轻量接口 */
        private const val PROBE_URL = "https://jwxt.zzu.edu.cn/eams-micro-server/api/v1/room/place/campus"

        /**
         * 内置门户地址：jwxt 被拦时 302 到这里（app_uuid 各接口共用同一枚，
         * 2026-11-12 实测稳定）。手动"立即认证"在没有历史 302 时的兜底。
         */
        const val DEFAULT_PORTAL_URL =
            "https://$HOST/portal.php?app_uuid=6e1c257b-1bf8-40ca-b66a-6c7e9fea0acf" +
                "&qaxtrustsso=true&redirect_url=https%3A%2F%2Fjwxt.zzu.edu.cn%2F"

        /** portal 链尝试次数与间隔（nox 时通时断，多试几次撞活的） */
        const val PORTAL_ATTEMPTS = 3
        const val PORTAL_RETRY_DELAY_MS = 800L

        /** 判断一个 3xx Location 是否指向认证门户（教务请求被网关拦下的标志） */
        fun isGatewayRedirect(location: String?): Boolean =
            location != null && runCatching { location.toHttpUrl().host.endsWith(HOST) }.getOrDefault(false)

        /** 从拦截响应里提取门户地址（302 Location 原样，登录链的起点） */
        fun portalUrlOf(location: String?): String? = location?.takeIf { isGatewayRedirect(it) }
    }
}
