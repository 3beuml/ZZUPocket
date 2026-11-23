package com.zzupocket.app.domain.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsDigestExtractorTest {
    @Test
    fun `提取活动时间地点且不使用发布落款`() {
        val digest = NewsDigestExtractor.extract(
            """首页
            |关于举办学术讲座的通知
            |
            |发布日期：2026年11月13日
            |活动时间：2026年11月25日 14:30
            |地点：综合管理中心第一报告厅
            |报名方式：线上报名
            |下一篇""".trimMargin(),
        )
        assertEquals("2026-11-25", digest.eventDate)
        assertEquals("综合管理中心第一报告厅", digest.eventPlace)
        assertTrue(digest.keyPoints.any { it.contains("报名") })
        assertTrue(digest.keyPoints.size <= 4)
    }

    @Test
    fun `无年份日期输出月日`() {
        assertEquals("12-03", NewsDigestExtractor.extract("报名截止：12月3日").eventDate)
    }

    @Test
    fun `长文摘要和要点均有上限`() {
        val digest = NewsDigestExtractor.extract("长".repeat(500))
        assertEquals(180, digest.excerpt.length)
        assertTrue(digest.keyPoints.isEmpty())
    }

    @Test
    fun `空正文和只有网页噪音不编造信息`() {
        assertEquals(NewsDigest("", emptyList()), NewsDigestExtractor.extract(" \n首页\n下一篇\n"))
    }
}
