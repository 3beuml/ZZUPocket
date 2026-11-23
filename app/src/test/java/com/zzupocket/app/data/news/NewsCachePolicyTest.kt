package com.zzupocket.app.data.news

import com.zzupocket.app.data.db.NewsArticleEntity
import com.zzupocket.app.domain.news.NewsArticle
import com.zzupocket.app.domain.news.NewsCategory
import com.zzupocket.app.domain.news.NewsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsCachePolicyTest {

    private fun article(
        id: String,
        title: String = "标题 $id",
        publishedAt: String = "2026-11-23",
        category: NewsCategory = NewsCategory.OTHER,
        keyPoints: List<String> = emptyList(),
    ) = NewsArticle(
        id = id,
        title = title,
        url = "https://www.zzu.edu.cn/info/1/$id.htm",
        publishedAt = publishedAt,
        section = NewsSection.NOTICE,
        publisher = "教务处",
        excerpt = "摘要 $id",
        body = "正文 $id",
        category = category,
        keyPoints = keyPoints,
    )

    @Test
    fun `没有缓存时所有文章都算新`() {
        val fresh = listOf(article("a"), article("b"))
        assertEquals(listOf("a", "b"), newArticleIds(emptyList(), fresh))
    }

    @Test
    fun `只返回当前列表没有的 id 且保持新列表顺序`() {
        val previous = listOf(article("a"), article("b"))
        val fresh = listOf(article("d"), article("b"), article("c"), article("a"))
        assertEquals(listOf("d", "c"), newArticleIds(previous, fresh))
    }

    @Test
    fun `文章被下架不算新新闻`() {
        val previous = listOf(article("a"), article("b"), article("c"))
        val fresh = listOf(article("a"), article("b"))
        assertTrue(newArticleIds(previous, fresh).isEmpty())
    }

    @Test
    fun `缓存映射保留要点正文和活动信息并可往返`() {
        val original = article(
            id = "roundtrip",
            category = NewsCategory.LECTURE,
            keyPoints = listOf("报名截止：11 月 30 日", "地点：主校区 \"报告厅\"，请提前 10 分钟入场"),
        ).copy(eventDate = "2026-11-30", eventPlace = "主校区报告厅")

        val entity = original.toNewsEntity(fetchedAt = 1_700_000_000_000L)
        val restored = entity.toNewsArticle()

        assertEquals(original.copy(matchScore = 0, matchReasons = emptyList()), restored)
        assertEquals(original.keyPoints, restored.keyPoints)
        assertEquals("2026-11-30", restored.eventDate)
        assertEquals("主校区报告厅", restored.eventPlace)
    }

    @Test
    fun `空要点映射为空串而不是空 JSON 数组`() {
        val entity = article("empty").toNewsEntity(fetchedAt = 1L)
        assertEquals("", entity.keyPoints)
        assertTrue(entity.toNewsArticle().keyPoints.isEmpty())
    }

    @Test
    fun `未知栏目和分类名回退默认值不让整张缓存作废`() {
        val entity = NewsArticleEntity(
            id = "legacy",
            title = "旧缓存",
            url = "https://www.zzu.edu.cn/info/1/legacy.htm",
            publishedAt = "2026-11-20",
            section = "REMOVED_SECTION",
            publisher = "",
            excerpt = "",
            body = "",
            category = "REMOVED_CATEGORY",
            keyPoints = "",
            eventDate = null,
            eventPlace = null,
            fetchedAt = 1L,
        )
        val restored = entity.toNewsArticle()
        assertEquals(NewsSection.NOTICE, restored.section)
        assertEquals(NewsCategory.OTHER, restored.category)
    }

    @Test
    fun `损坏的要点 JSON 回退为空列表`() {
        val entity = article("broken").toNewsEntity(fetchedAt = 1L).copy(keyPoints = "[not json")
        assertTrue(entity.toNewsArticle().keyPoints.isEmpty())
    }
}
