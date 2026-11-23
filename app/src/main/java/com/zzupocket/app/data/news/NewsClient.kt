package com.zzupocket.app.data.news

import com.zzupocket.app.core.net.HttpClients
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 郑州大学公开网页的只读客户端；不携带、保存或注入任何登录会话。 */
class NewsClient internal constructor(
    private val client: OkHttpClient,
    private val parser: NewsParser,
) {
    /** 默认独立 client：沿用统一超时及每主机至少一秒的请求间隔。 */
    constructor() : this(
        HttpClients.builder(minIntervalMs = 1_000)
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
        NewsParser(),
    )

    /** 仅请求学校官网固定栏目，不跟随重定向到其他站点。 */
    internal suspend fun list(source: NewsSource) = withContext(Dispatchers.IO) {
        val html = get(source.url, source.section.label)
        when (source.kind) {
            NewsSource.Kind.NOTICE -> parser.noticeList(html, source.url)
            NewsSource.Kind.NEWS -> parser.newsList(html, source.url, source.section)
            NewsSource.Kind.ACADEMIC -> parser.academicList(html, source.url)
        }
    }

    /** 单篇详情。URL 必须来自已解析的校内 /info/ 链接；仅供仓库内部使用。 */
    internal suspend fun detail(url: String) = withContext(Dispatchers.IO) {
        parser.detail(get(url, "新闻详情"), url)
    }

    private fun get(url: String, label: String): String {
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
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
