package com.zzupocket.app.domain.news

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsMatcherTest {
    private val today = LocalDate.of(2026, 11, 23)

    @Test
    fun `标题兴趣命中优先于正文并给出中文理由`() {
        val titleHit = NewsMatcher.match("AI 人工智能讲座", "", "2026-11-20", NewsCategory.LECTURE, profile = NewsProfile(listOf("人工智能")), today = today)
        val bodyHit = NewsMatcher.match("学术报告", "介绍人工智能", "2026-11-20", NewsCategory.LECTURE, profile = NewsProfile(listOf("人工智能")), today = today)
        assertTrue(titleHit.matchScore > bodyHit.matchScore)
        assertTrue(titleHit.matchReasons.contains("命中兴趣：人工智能（标题）"))
        assertTrue(bodyHit.matchReasons.contains("命中兴趣：人工智能"))
    }

    @Test
    fun `AI 同义词和大小写空白可容错`() {
        val result = NewsMatcher.match("  ai   前沿", "", "2026-11-23", NewsCategory.LECTURE, profile = NewsProfile(listOf("人工智能")), today = today)
        assertTrue(result.matchReasons.any { it.startsWith("命中兴趣") })
    }

    @Test
    fun `无兴趣画像仍有分类和时效基础分`() {
        val result = NewsMatcher.match("校园通知", "", "2026-10-30", NewsCategory.ACTIVITY, profile = NewsProfile(emptyList()), today = today)
        assertEquals(47, result.matchScore)
        assertTrue(result.matchReasons.contains("近 30 天发布"))
        assertTrue(result.matchReasons.contains("属于活动"))
    }

    @Test
    fun `日期以注入的今天为准且非法日期不加分`() {
        val recent = NewsMatcher.match("通知", "", "2026-11-20", NewsCategory.OTHER, profile = NewsProfile(emptyList()), today = today)
        val invalid = NewsMatcher.match("通知", "", "不是日期", NewsCategory.OTHER, profile = NewsProfile(emptyList()), today = today)
        assertTrue(recent.matchReasons.contains("近 7 天发布"))
        assertTrue(invalid.matchScore < recent.matchScore)
    }
}
