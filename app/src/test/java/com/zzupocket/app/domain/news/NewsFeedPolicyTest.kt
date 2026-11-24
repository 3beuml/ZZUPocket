package com.zzupocket.app.domain.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsFeedPolicyTest {
    @Test
    fun `默认按时间排序且不隐藏资讯`() {
        val older = other.copy(id = "older", publishedAt = "2026-11-22")
        val newest = career.copy(publishedAt = "2026-11-24")
        val result = NewsFeedPolicy.build(listOf(older, competition, newest), NewsFeedFilter())
        assertEquals(listOf(newest.id, competition.id, older.id), result.map(NewsArticle::id))
    }

    @Test
    fun `按时间排序不受已选主题匹配分影响`() {
        val newestOther = other.copy(id = "newest", publishedAt = "2026-11-24")
        val oldestCompetition = competition.copy(id = "oldest", publishedAt = "2026-11-21")
        val result = NewsFeedPolicy.build(
            listOf(oldestCompetition, newestOther, career),
            NewsFeedFilter(setOf(NewsTopic.COMPETITION), sortMode = NewsSortMode.DATE),
        )
        assertEquals(listOf(newestOther.id, career.id, oldestCompetition.id), result.map(NewsArticle::id))
    }

    @Test
    fun `关注优先把匹配主题排在更晚的其他资讯之前`() {
        val newestOther = other.copy(id = "newest", publishedAt = "2026-11-24")
        val oldestCompetition = competition.copy(id = "oldest", publishedAt = "2026-11-21")
        val result = NewsFeedPolicy.build(
            listOf(newestOther, oldestCompetition),
            NewsFeedFilter(setOf(NewsTopic.COMPETITION), sortMode = NewsSortMode.RELEVANCE),
        )
        assertEquals(listOf(oldestCompetition.id, newestOther.id), result.map(NewsArticle::id))
        assertTrue(result.first().matchedTopics.contains(NewsTopic.COMPETITION))
    }

    @Test
    fun `严格模式按多主题并集过滤且结果不重复`() {
        val filter = NewsFeedFilter(setOf(NewsTopic.COMPETITION, NewsTopic.AI), onlyFollowed = true)
        val aiCompetition = competition.copy(title = "人工智能创新大赛")
        val result = NewsFeedPolicy.build(listOf(competition, aiCompetition, career, other), filter)
        assertEquals(setOf(competition.id, aiCompetition.id), result.map(NewsArticle::id).toSet())
        assertEquals(2, result.size)
    }

    @Test
    fun `严格模式空主题显示全部`() {
        val result = NewsFeedPolicy.build(listOf(competition, other), NewsFeedFilter(emptySet(), onlyFollowed = true))
        assertEquals(2, result.size)
    }

    @Test
    fun `分类主题复用完整分类关键词`() {
        val challengeCup = other.copy(id = "cup", title = "挑战杯校内选拔赛")
        val doubleChoice = other.copy(id = "job", title = "春季双选会")
        val result = NewsFeedPolicy.build(
            listOf(challengeCup, doubleChoice, other),
            NewsFeedFilter(setOf(NewsTopic.COMPETITION, NewsTopic.CAREER), onlyFollowed = true),
        )
        assertEquals(setOf("cup", "job"), result.map(NewsArticle::id).toSet())
    }

    @Test
    fun `列表摘要参与分类和主题匹配`() {
        val summaryOnly = other.copy(id = "summary", excerpt = "本次挑战杯报名安排如下")
        val result = NewsFeedPolicy.build(
            listOf(summaryOnly),
            NewsFeedFilter(setOf(NewsTopic.COMPETITION), onlyFollowed = true),
        )
        assertEquals(NewsCategory.COMPETITION, result.single().category)
        assertTrue(result.single().matchedTopics.contains(NewsTopic.COMPETITION))
    }

    @Test
    fun `人工智能跨分类命中且不误收英文子串`() {
        val aiLecture = other.copy(id = "ai", title = "AI 前沿讲座")
        val training = other.copy(id = "training", title = "English training and chair information")
        val result = NewsFeedPolicy.build(
            listOf(aiLecture, training),
            NewsFeedFilter(setOf(NewsTopic.AI), onlyFollowed = true),
        )
        assertEquals(listOf("ai"), result.map(NewsArticle::id))
        assertFalse(result.any { "training" in it.title })
    }

    @Test
    fun `旧兴趣映射到统一主题且清空不复活默认值`() {
        assertEquals(
            setOf(NewsTopic.COMPETITION, NewsTopic.POSTGRADUATE, NewsTopic.LECTURE, NewsTopic.CAREER),
            NewsTopic.fromLegacyInterests(listOf("学科竞赛", "保研", "学术讲座", "就业实习")),
        )
        assertTrue(NewsTopic.fromLegacyInterests(emptyList()).isEmpty())
    }

    private fun article(id: String, title: String, category: NewsCategory = NewsCategory.OTHER) = NewsArticle(
        id = id,
        title = title,
        url = "https://www.zzu.edu.cn/$id",
        publishedAt = "2026-11-23",
        section = NewsSection.NOTICE,
        category = category,
    )

    private val competition = article("competition", "大学生程序设计竞赛", NewsCategory.COMPETITION)
    private val career = article("career", "校园招聘会", NewsCategory.CAREER)
    private val other = article("other", "校园道路维护通告")
}
