package com.zzupocket.app.data.news

import com.zzupocket.app.domain.news.NewsArticle
import com.zzupocket.app.domain.news.NewsSection
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 学校 Visual SiteBuilder 模板解析。
 *
 * 官网栏目、新闻网和学术预告使用不同模板，类名及正文容器可能随校方改版而变化。
 * 列表只采用实际存在的字段；列表未给出的发布日期保持空字符串，不用活动日期冒充。
 */
class NewsParser internal constructor() {

    internal data class Detail(
        val title: String,
        val publisher: String,
        val excerpt: String,
        val body: String,
        val publishedAt: String,
        val eventDate: String? = null,
        val eventPlace: String? = null,
    )

    /** 通知公告的真实结构为 ul.notice-box 下独立的标题、日期节点。 */
    fun noticeList(html: String, pageUrl: String): List<NewsArticle> {
        val document = Jsoup.parse(html, pageUrl)
        return document.select("ul.notice-box li.notice-item").mapNotNull { item ->
            val link = item.selectFirst("a.notice-link[href]") ?: return@mapNotNull null
            article(
                link = link,
                title = item.selectFirst(".notice-link-text")?.text(),
                publishedAt = item.selectFirst(".notice-link-time")?.text().dateOrEmpty(),
                section = NewsSection.NOTICE,
            )
        }
    }

    /**
     * 新闻网实测列表为 div.new-item > h3 a[title] 与 .new-date；li a + span 是旧式兼容模板。
     * 只读 .new-item/.content，避免把页头、推荐阅读及页脚同文链接混入栏目。
     */
    fun newsList(html: String, pageUrl: String, section: NewsSection): List<NewsArticle> {
        val document = Jsoup.parse(html, pageUrl)
        val current = document.select(".new-item").mapNotNull { item ->
            article(
                link = item.selectFirst(".new-center h3 a[title][href]")
                    ?: item.selectFirst("h3 a[title][href]") ?: return@mapNotNull null,
                title = null,
                publishedAt = item.selectFirst(".new-date")?.text().dateOrEmpty(),
                section = section,
                excerpt = item.selectFirst(".new-center p a")?.text().cleanExcerpt().clip(180),
            )
        }
        if (current.isNotEmpty()) return current
        return document.select(".content li").mapNotNull { item ->
            val link = item.selectFirst("a[title][href]") ?: return@mapNotNull null
            article(
                link = link,
                title = null,
                publishedAt = item.selectFirst("span")?.text().dateOrEmpty(),
                section = section,
            )
        }
    }

    /**
     * 学术预告实测使用 li.xsyg-item 和 .xsyg-item-info-title，而非预测的首页类名。
     * 列表没有发布日期；只把明确标为活动时间的日期放入 eventDate。
     */
    fun academicList(html: String, pageUrl: String): List<NewsArticle> {
        val document = Jsoup.parse(html, pageUrl)
        return document.select("li.xsyg-item, li:has(a.index-tab-academic)").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val eventDate = item.selectFirst(".xsyg-item-time")?.text().labelValue("活动时间").dateOrNull()
            article(
                link = link,
                title = item.selectFirst(".xsyg-item-info-title, .index-tab-academic-title")?.text(),
                publishedAt = "",
                section = NewsSection.ACADEMIC,
                eventDate = eventDate,
                eventPlace = item.selectFirst(".xsyg-item-address")?.text().labelValue("活动地点"),
            )
        }
    }

    internal fun detail(html: String, pageUrl: String): Detail {
        val document = Jsoup.parse(html, pageUrl)
        // 两种实测详情模板标题类相同；h1/h2 是新闻站可能采用的兼容回退。
        val title = document.selectFirst(".notice-detail-title, h1.article-title, h2.article-title")
            ?.text().clean()
        val info = document.select(".notice-detail-info-text, .article-info, .info")
        val publisher = info.firstNotNullOfOrNull { node ->
            node.text().labelValue("信息来源").takeIf(String::isNotBlank)
        }.orEmpty()
        val publishedAt = info.firstNotNullOfOrNull { node ->
            node.text().labelValue("发布时间").dateOrNull()
        }.orEmpty()
        // 正文限定在 v_news_content，避免把侧边推荐和附件导航带入。
        val content = document.selectFirst("#vsb_content_4 .v_news_content")
            ?: document.selectFirst(".v_news_content")
            ?: document.selectFirst("#vsb_content, .notice-detail-content, .article-content")
        val body = content?.let(::readableText).orEmpty()
        // Jsoup 的属性选择器不支持 CSS 的 `i` 大小写标志；官网固定使用小写 name。
        val description = document.selectFirst("meta[name=description]")?.attr("content").cleanExcerpt()
        // 通知公告的 meta description 常是「郑州大学, Zhengzhou University,郑大, ZZU」这类
        // SEO 关键词，或只有「主办单位：…」「报告时间：…」一行；清洗后为空/不像摘要时
        // 退回正文第一段，列表才不会满屏都是校名或单一机构名。
        val bodyFallback = body.lineSequence()
            .map { it.trim() }
            .firstOrNull {
                it.length >= 20 && !(it.length < 60 && (it.endsWith('：') || it.endsWith(':')))
            }
            ?: body.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
        val excerpt = (if (description.looksLikeLabelSummary()) bodyFallback else description.ifBlank { bodyFallback })
            .orEmpty()
            .cleanExcerpt()
            .clip(180)
        return Detail(
            title = title.orEmpty(),
            publisher = publisher,
            excerpt = excerpt,
            body = body,
            publishedAt = publishedAt,
            eventDate = document.selectFirst(".xsyg-detail-time")?.text().dateOrNull(),
            eventPlace = document.selectFirst(".xsyg-detail-address")?.text().clean()
                .takeIf { it.isNotBlank() },
        )
    }

    private fun article(
        link: Element,
        title: String?,
        publishedAt: String,
        section: NewsSection,
        excerpt: String = "",
        eventDate: String? = null,
        eventPlace: String? = null,
    ): NewsArticle? {
        val url = link.absUrl("href").ifBlank { link.attr("href") }
        val match = INFO_URL.matchEntire(url) ?: return null
        val actualTitle = title.clean().ifBlank { link.attr("title").clean().ifBlank { link.text().clean() } }
        if (actualTitle.isBlank()) return null
        return NewsArticle(
            id = "${match.groupValues[2]}/${match.groupValues[3]}",
            title = actualTitle,
            url = "https://www.zzu.edu.cn${match.groupValues[1]}",
            publishedAt = publishedAt,
            section = section,
            excerpt = excerpt,
            eventDate = eventDate,
            eventPlace = eventPlace,
        )
    }

    /** 将块级标签转换成换行，保留原文段落；图片只删除而不生成替代内容。 */
    private fun readableText(content: Element): String {
        val copy = content.clone()
        copy.select("script, style, img, video").remove()
        copy.select("br").forEach { it.appendText("\n") }
        copy.select("p, div, h1, h2, h3, h4, li, tr").forEach { it.appendText("\n") }
        return copy.wholeText().replace("\u00a0", " ")
            .lines().map { it.trim() }
            .joinToString("\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun String?.clean() = orEmpty().replace(Regex("\\s+"), " ").trim()

    /**
     * 官网列表/详情摘要清洗。
     *
     * 通知公告的 meta description 实测是「郑州大学, Zhengzhou University,郑大, ZZU」
     * 这类 SEO 关键词；纯关键词返回空（交给正文第一段兜底），带正文的前缀则去掉关键词列表。
     * 真正的正文摘要（如「11月20日，郑州大学…」）没有关键词分隔符，不会被误删。
     */
    private fun String?.cleanExcerpt(): String {
        val text = clean()
        if (text.isBlank()) return ""
        if (KEYWORD_ONLY.matches(text)) return ""
        val stripped = LEADING_KEYWORDS.replace(text, "")
            .trim()
            .trimStart('，', ',', '、', '；', ';', '：', ':')
            .trim()
        if (stripped.isBlank()) return ""
        val keywordCount = SITE_KEYWORD.findAll(text).count()
        if (keywordCount >= 2 && stripped.length < 8) return ""
        return stripped
    }

    /** 「主办单位：…」「报告时间：…」这种单行字段不是文章摘要，应退回正文第一段。 */
    private fun String.looksLikeLabelSummary(): Boolean {
        if (isBlank() || length > 80) return false
        val colon = indexOfAny(charArrayOf('：', ':'))
        return colon in 1..12
    }

    /**
     * 取「标签：值」中的值。
     *
     * ⚠️ 不能链式调用 `substringAfter("标签：", "").substringAfter("标签:", "")`：
     * 第一次已经取到值后，第二次找不到半角冒号会返回默认的 ""，把结果清空。
     * 学校页面实测用全角冒号，但兼容模板里也有半角。
     */
    private fun String?.labelValue(label: String): String {
        val text = clean()
        val fullWidth = "$label："
        val halfWidth = "$label:"
        return when {
            text.contains(fullWidth) -> text.substringAfter(fullWidth).trim()
            text.contains(halfWidth) -> text.substringAfter(halfWidth).trim()
            else -> ""
        }
    }

    private fun String?.dateOrNull(): String? = orEmpty().let { text ->
        Regex("(20\\d{2})[-年/.](\\d{1,2})[-月/.](\\d{1,2})").find(text)?.let {
            "%s-%02d-%02d".format(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
    }

    private fun String?.dateOrEmpty() = dateOrNull().orEmpty()

    private fun String.clip(limit: Int) = if (length <= limit) this else take(limit) + "…"

    private companion object {
        val INFO_URL = Regex("https?://www\\.zzu\\.edu\\.cn(/info/(\\d+)/(\\d+)\\.htm)$")
        val LEADING_KEYWORDS = Regex(
            "^(?:(?:郑州大学|Zhengzhou\\s*University|郑大|ZZU)\\s*[,，、;；]\\s*)+" +
                "(?:郑州大学|Zhengzhou\\s*University|郑大|ZZU)?\\s*",
            RegexOption.IGNORE_CASE,
        )
        val KEYWORD_ONLY = Regex(
            "^(?:\\s*(?:郑州大学|Zhengzhou\\s*University|郑大|ZZU)\\s*[,，、;；]?\\s*)+$",
            RegexOption.IGNORE_CASE,
        )
        val SITE_KEYWORD = Regex(
            "郑州大学|Zhengzhou\\s*University|郑大|ZZU",
            RegexOption.IGNORE_CASE,
        )
    }
}
