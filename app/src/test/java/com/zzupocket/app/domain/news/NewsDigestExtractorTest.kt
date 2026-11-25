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
    fun `纯标题行不作为要点`() {
        val digest = NewsDigestExtractor.extract(
            """（一）参赛对象及方式
            |（一）作品提交时间
            |二、活动时间：
            |报名方式：线上报名
            |时间：8:30-9:30
            |活动时间：2026年11月15日
            """.trimMargin(),
        )
        assertTrue(digest.keyPoints.none { it.contains("参赛对象及方式") })
        assertTrue(digest.keyPoints.none { it.contains("作品提交时间") })
        assertTrue(digest.keyPoints.none { it.contains("二、活动时间") })
        assertTrue(digest.keyPoints.any { it.contains("线上报名") })
        assertTrue(digest.keyPoints.any { it.contains("8:30-9:30") })
    }

    @Test
    fun `目录行和长流程段落不抢要点`() {
        val digest = NewsDigestExtractor.extract(
            """一、活动时间
            |报告时间：10月17日（周六）14:00
            |二、活动地点
            |主校区综合管理中心第二报告厅
            |三、参与对象
            |全体在校师生
            |发布活动通知、启动报名，以高校为单位进行初选
            |八、投稿方式
            |参赛师生填写报名表，将作品打包发送至邮箱
            |联系电话：0371-67781031
            """.trimMargin(),
        )
        assertTrue(digest.keyPoints.any { it.contains("10月17日") })
        assertTrue(digest.keyPoints.any { it.contains("第二报告厅") })
        assertTrue(digest.keyPoints.any { it.contains("全体在校师生") })
        assertTrue(digest.keyPoints.any { it.contains("0371-67781031") })
        assertTrue(digest.keyPoints.none { it == "三、参与对象" })
        assertTrue(digest.keyPoints.none { it.contains("发布活动通知") })
    }

    @Test
    fun `活动地点标题和地点冒号行都能提取地点`() {
        assertEquals(
            "主校区综合管理中心第二报告厅",
            NewsDigestExtractor.extract("二、活动地点\n主校区综合管理中心第二报告厅").eventPlace,
        )
        assertEquals(
            "郑州大学主校区理科园共享平台",
            NewsDigestExtractor.extract("地点：郑州大学主校区理科园共享平台").eventPlace,
        )
    }

    @Test
    fun `联系方式标题下的人名电话提取为电话`() {
        val digest = NewsDigestExtractor.extract(
            """十、联系方式
            |陈老师：18510120524
            |沈老师：18501132847
            """.trimMargin(),
        )
        assertTrue(digest.keyPoints.any { it == "电话：18510120524" })
        assertTrue(digest.keyPoints.none { it.contains("陈老师") })
    }

    @Test
    fun `活动介绍里的普通举行日期不抢要点`() {
        val digest = NewsDigestExtractor.extract(
            """活动将于2026年11月14日在主校区举行。
            |一、活动时间
            |11月14日8:30-17:30
            """.trimMargin(),
        )
        assertTrue(digest.keyPoints.none { it.contains("活动将于") })
        assertTrue(digest.keyPoints.any { it.contains("8:30-17:30") })
    }

    @Test
    fun `月份范围不当活动日期`() {
        assertNull(NewsDigestExtractor.extract("2026年10-12月、2027年1-6月").eventDate)
    }

    @Test
    fun `机构名不当活动地点`() {
        assertNull(NewsDigestExtractor.extract("郑州大学大学生就业创业指导服务中心").eventPlace)
    }

    @Test
    fun `邮箱值只保留地址`() {
        assertEquals(
            listOf("邮箱：zzusysx@163.com"),
            NewsDigestExtractor.extract("投稿邮箱：发送至指定邮箱zzusysx@163.com").keyPoints,
        )
    }

    @Test
    fun `对象只取第一句`() {
        assertEquals(
            listOf("对象：郑州大学全体在校师生及校友"),
            NewsDigestExtractor.extract("三、参赛对象\n郑州大学全体在校师生及校友。教师、学生分赛道。").keyPoints,
        )
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
