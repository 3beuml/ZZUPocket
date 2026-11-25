package com.zzupocket.app.data.news

import com.zzupocket.app.core.net.HttpClients
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/** 详情请求的来源：用户主动点开时不能被后台预取挤在限速队列里。 */
enum class NewsRequestPriority { USER, BACKGROUND }

/** 郑州大学公开网页的只读客户端；不携带、保存或注入任何登录会话。 */
class NewsClient internal constructor(
    private val backgroundClient: OkHttpClient,
    private val interactiveClient: OkHttpClient,
    private val parser: NewsParser,
) {
    /**
     * 两条通道：
     * - 后台（栏目列表 + 预取）：每主机至少 1 秒，维持对学校站点的礼貌间隔；
     * - 用户（点开详情）：独立 client，150ms 间隔，不排在列表刷新或预取后面。
     *
     * 两条通道可能同时请求，最多 2 个并发，不会形成集中抓取。
     */
    constructor() : this(
        backgroundClient = HttpClients.builder(minIntervalMs = 1_000)
            .dns(SCHOOL_DNS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
        interactiveClient = HttpClients.builder(minIntervalMs = 150)
            .dns(SCHOOL_DNS)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(15, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build(),
        parser = NewsParser(),
    )

    /** 仅请求学校官网固定栏目，不跟随重定向到其他站点。 */
    internal suspend fun list(source: NewsSource) = withContext(Dispatchers.IO) {
        val html = get(backgroundClient, source.url, source.section.label)
        when (source.kind) {
            NewsSource.Kind.NOTICE -> parser.noticeList(html, source.url)
            NewsSource.Kind.NEWS -> parser.newsList(html, source.url, source.section)
            NewsSource.Kind.ACADEMIC -> parser.academicList(html, source.url)
        }
    }

    /** 单篇详情。URL 必须来自已解析的校内 /info/ 链接；仅供仓库内部使用。 */
    internal suspend fun detail(
        url: String,
        priority: NewsRequestPriority = NewsRequestPriority.USER,
    ) = withContext(Dispatchers.IO) {
        val client = if (priority == NewsRequestPriority.USER) interactiveClient else backgroundClient
        parser.detail(get(client, url, "新闻详情"), url)
    }

    private fun get(client: OkHttpClient, url: String, label: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        if (request.url.host !in ALLOWED_HOSTS) {
            throw IOException("拒绝访问非学校新闻站点：${request.url.host}")
        }
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw IOException("无法连接${label}页面，请检查网络后重试", e)
        }
        response.use {
            if (it.code !in 200..299) {
                throw IOException("${label}页面加载失败（HTTP ${it.code}）")
            }
            val body = it.body ?: throw IOException("${label}页面返回了空响应")
            return body.string().also { text ->
                if (text.isBlank()) throw IOException("${label}页面内容为空")
            }
        }
    }

    private companion object {
        val ALLOWED_HOSTS = setOf("www.zzu.edu.cn", "news.zzu.edu.cn")

        /**
         * 学校站点同时有 A / AAAA 记录；部分移动网络的 IPv6 路由不通，
         * OkHttp 会先在 IPv6 上耗完 connectTimeout 再回退，表现为“点开很久”。
         * 有 IPv4 时只走 IPv4，只有 IPv6 记录时才用系统结果。
         */
        val SCHOOL_DNS = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(hostname)
                return addresses.filterIsInstance<Inet4Address>().ifEmpty { addresses }
            }
        }

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
