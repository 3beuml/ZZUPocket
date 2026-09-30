package com.zzupocket.app.core.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * 统一认证用到的两件密码学小事。
 *
 * 协议来源：ZZU.Py 的 CASClient（MIT）。
 * 密码加密方式固定为 RSA/PKCS1Padding + Base64 + `__RSA__` 前缀 —— 学校服务端就是这么校验的。
 */
object Crypto {

    private const val RSA_PREFIX = "__RSA__"
    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

    /** 解析 CAS 下发的 PEM 公钥（-----BEGIN PUBLIC KEY----- 那种） */
    fun parseRsaPublicKey(pem: ByteArray): RSAPublicKey {
        val text = String(pem, Charsets.UTF_8)
        val base64 = text
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("-----BEGIN RSA PUBLIC KEY-----", "")
            .replace("-----END RSA PUBLIC KEY-----", "")
            .replace(Regex("\\s"), "")
        val der = Base64.decode(base64, Base64.DEFAULT)
        val spec = X509EncodedKeySpec(der)
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }

    /** 加密并加上 `__RSA__` 前缀，得到登录/检测接口要的 username / password 参数 */
    fun encryptAndEncode(plain: String, publicKey: RSAPublicKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return RSA_PREFIX + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    /**
     * 从 JWT 里读 exp（毫秒）。解析失败返回 null。
     *
     * 只做 Base64 解码取 payload，**不验签** —— 我们只是想知道"还有多久过期"，
     * 用来决定要不要提前刷新，不用于任何安全判断。
     */
    fun jwtExpiresAtMillis(jwt: String): Long? {
        return try {
            val parts = jwt.split('.')
            if (parts.size < 2) return null
            val payload = String(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8,
            )
            val match = Regex("\"exp\"\\s*:\\s*(\\d+)").find(payload) ?: return null
            match.groupValues[1].toLong() * 1000L
        } catch (_: Exception) {
            null
        }
    }
}
