package com.zzupocket.app.core.net

import com.zzupocket.app.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * OkHttp 客户端工厂。
 *
 * 关键点：**每个上游系统一个独立 client**。教务与图书馆都依赖 cookie / 令牌，
 * 共用一个 client 会让两套会话互相污染。
 *
 * 全部使用 followRedirects = false 的变体由调用方按需选择：
 * 一卡通拿 tid 必须自己读 Location 头。
 */
object HttpClients {

    private const val UA_DEFAULT = "okhttp/3.12.1"

    /** 请求头复刻：学校服务对 UA / Accept 敏感，缺了可能被拒或被当成爬虫 */
    private class HeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val builder = request.newBuilder()
            // 只补默认值，不覆盖调用方显式设置的 UA
            // （统一认证的登录/MFA 请求要用 SWSuperApp/1.1.1()，取公钥要用 okhttp/3.12.1）
            if (request.header("User-Agent") == null) {
                builder.header("User-Agent", UA_DEFAULT)
            }
            if (request.header("Accept") == null) {
                builder.header("Accept", "application/json, text/plain, */*")
            }
            return chain.proceed(builder.build())
        }
    }

    /**
     * 每主机最小请求间隔。
     *
     * 有实证支撑：旧项目 1 分钟轮询 15 分钟，16 条读数完全相同 —— 高频轮询既无意义
     * 又增加被风控识别的风险。这里从架构上兜住下限。
     */
    private class RateLimitInterceptor(private val minIntervalMs: Long) : Interceptor {
        private val lastCallAt = ConcurrentHashMap<String, Long>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val host = chain.request().url.host
            synchronized(lastCallAt) {
                val now = System.currentTimeMillis()
                val last = lastCallAt[host] ?: 0L
                val wait = minIntervalMs - (now - last)
                if (wait > 0) Thread.sleep(wait)
                lastCallAt[host] = System.currentTimeMillis()
            }
            return chain.proceed(chain.request())
        }
    }

    /** 只打印方法 + URL（**不含请求头与响应体**，避免把 token 写进日志） */
    private class SafeLogInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = try {
                chain.proceed(request)
            } catch (e: Exception) {
                android.util.Log.w("ZZUPocket", "${request.method} ${request.url.encodedPath} 失败: ${e.javaClass.simpleName}")
                throw e
            }
            android.util.Log.d(
                "ZZUPocket",
                "${request.method} ${request.url.encodedPath} → ${response.code} (${response.body?.contentLength() ?: -1}B)",
            )
            return response
        }
    }

    private fun builder(minIntervalMs: Long) = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(HeaderInterceptor())
        .addInterceptor(RateLimitInterceptor(minIntervalMs))
        .apply { if (BuildConfig.DEBUG) addInterceptor(SafeLogInterceptor()) }

    /** CAS 统一认证：不需要 cookie 持久化，不需要跟随重定向 */
    val cas: OkHttpClient by lazy {
        builder(minIntervalMs = 300).followRedirects(false).build()
    }

    /** 一卡通：日常查询（跟随重定向无所谓，但统一关掉更可控） */
    val ecard: OkHttpClient by lazy {
        builder(minIntervalMs = 300).followRedirects(false).build()
    }

    /** 教务 EAS：同样是纯 JSON API，只需要 Authorization 头 */
    val eas: OkHttpClient by lazy {
        builder(minIntervalMs = 300).followRedirects(false).build()
    }
}
