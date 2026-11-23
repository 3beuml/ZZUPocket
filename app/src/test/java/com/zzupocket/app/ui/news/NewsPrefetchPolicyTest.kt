package com.zzupocket.app.ui.news

import com.zzupocket.app.domain.news.NewsArticle
import com.zzupocket.app.domain.news.NewsSection
import org.junit.Assert.assertEquals
import org.junit.Test

/** 预取策略的回归：只补最近两条、且只补列表没有摘要的。 */
class NewsPrefetchPolicyTest {

    @Test
    fun `按发布时间取最近两条无摘要资讯`() {
        val articles = listOf(
            article("old", "2026-11-01"),
            article("new", "2026-11-23"),
            article("has-excerpt", "2026-11-22", excerpt = "列表已有摘要"),
            article("middle", "2026-11-10"),
            article("another", "2026-11-20"),
        )

        val targets = prefetchTargets(articles, limit = 2)

        assertEquals(listOf("new", "another"), targets.map { it.id })
    }

    @Test
    fun `摘要为空才预取`() {
        val articles = listOf(
            article("has-excerpt", "2026-11-23", excerpt = "摘要"),
            article("blank", "2026-11-22"),
        )

        assertEquals(listOf("blank"), prefetchTargets(articles, limit = 2).map { it.id })
    }

    @Test
    fun `空列表不预取`() {
        assertEquals(emptyList<NewsArticle>(), prefetchTargets(emptyList(), limit = 2))
    }

    private fun article(id: String, publishedAt: String, excerpt: String = "") = NewsArticle(
        id = id,
        title = "标题-$id",
        url = "https://www.zzu.edu.cn/info/1442/$id.htm",
        publishedAt = publishedAt,
        section = NewsSection.NOTICE,
        excerpt = excerpt,
    )
}
