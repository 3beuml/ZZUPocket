package com.zzupocket.app.ui.today

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 时间轴构建规则的回归测试：空档怎么切、排序、边界。 */
class TodayTimelineTest {

    private fun course(start: Int, end: Int, name: String = "课") =
        TimelineEntry(TimelineKind.COURSE, start, end, title = name)

    private fun focus(start: Int, end: Int, tag: String = "自习") =
        TimelineEntry(TimelineKind.FOCUS, start, end, title = tag)

    @Test
    fun `没有课也没有记录时是空轴`() {
        assertTrue(TodayTimeline.build(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `两节课之间够长就产生空档`() {
        val out = TodayTimeline.build(
            courses = listOf(course(600, 645), course(665, 710)), // 10:00-10:45, 11:05-11:50
            focuses = emptyList(),
        )
        val gaps = out.filter { it.kind == TimelineKind.GAP }
        assertEquals(1, gaps.size)
        assertEquals(645, gaps[0].startMin)
        assertEquals(665, gaps[0].endMin) // 20 分钟
    }

    @Test
    fun `课间太短不产生空档`() {
        val out = TodayTimeline.build(
            courses = listOf(course(600, 645), course(655, 700)), // 课间只有 10 分钟
            focuses = emptyList(),
        )
        assertTrue(out.none { it.kind == TimelineKind.GAP })
        assertEquals(2, out.size)
    }

    @Test
    fun `一节课都没有时不画空档`() {
        val out = TodayTimeline.build(
            courses = emptyList(),
            focuses = listOf(focus(540, 565)),
        )
        assertTrue(out.none { it.kind == TimelineKind.GAP })
        assertEquals(1, out.size)
    }

    @Test
    fun `只有一节课时没有任何空档`() {
        val out = TodayTimeline.build(courses = listOf(course(840, 940)), focuses = emptyList())
        assertTrue(out.none { it.kind == TimelineKind.GAP })
        assertEquals(1, out.size)
    }

    @Test
    fun `只上课不专注时轴上只有课和课间空档`() {
        val out = TodayTimeline.build(
            courses = listOf(course(600, 645), course(785, 830)), // 10:00-10:45, 13:05-13:50
            focuses = emptyList(),
        )
        assertEquals(listOf(TimelineKind.COURSE, TimelineKind.GAP, TimelineKind.COURSE), out.map { it.kind })
        assertEquals(645, out[1].startMin)
        assertEquals(785, out[1].endMin)
    }

    @Test
    fun `空档会统计其中已专注的时长`() {
        val out = TodayTimeline.build(
            courses = listOf(course(600, 645), course(785, 830)), // 10:00-10:45, 13:05-13:50
            focuses = listOf(focus(660, 685), focus(700, 725)),   // 11:00-11:25, 11:40-12:05
        )
        val gap = out.first { it.kind == TimelineKind.GAP && it.startMin == 645 }
        assertEquals(50 * 60, gap.usedSeconds) // 两段各 25 分钟
    }

    @Test
    fun `按时间排序且同一分钟先课后空档再专注`() {
        val out = TodayTimeline.build(
            courses = listOf(course(600, 645), course(700, 745)),
            focuses = listOf(focus(600, 625)),
        )
        assertEquals(600, out[0].startMin)
        assertEquals(TimelineKind.COURSE, out[0].kind)
        assertEquals(TimelineKind.FOCUS, out[1].kind)
        assertTrue(out.zipWithNext().all { (a, b) -> a.startMin <= b.startMin })
    }

    @Test
    fun `重叠的课不会算出负长度的空档`() {
        val out = TodayTimeline.build(
            courses = listOf(course(600, 690), course(640, 730)), // 调课撞在一起
            focuses = emptyList(),
        )
        assertTrue(out.none { it.kind == TimelineKind.GAP })
        assertEquals(2, out.size)
    }

    @Test
    fun `covers 是左闭右开`() {
        val c = course(600, 645)
        assertTrue(c.covers(600))
        assertTrue(c.covers(644))
        assertTrue(!c.covers(645))
    }

    @Test
    fun `时间格式化`() {
        assertEquals("08:00", TodayTimeline.clock(480))
        assertEquals("22:05", TodayTimeline.clock(1325))
        assertEquals("00:00", TodayTimeline.clock(1440))
        assertEquals("45 分钟", TodayTimeline.duration(45))
        assertEquals("1 小时", TodayTimeline.duration(60))
        assertEquals("1 小时 20 分", TodayTimeline.duration(80))
        assertEquals("2 小时 5 分", TodayTimeline.duration(125))
    }

    @Test
    fun `解析 HH mm 文本`() {
        assertEquals(480, TodayTimeline.minuteOfDay("08:00"))
        assertEquals(485, TodayTimeline.minuteOfDay("8:05"))
        assertEquals(null, TodayTimeline.minuteOfDay(null))
        assertEquals(null, TodayTimeline.minuteOfDay(""))
        assertEquals(null, TodayTimeline.minuteOfDay("abc"))
        assertEquals(null, TodayTimeline.minuteOfDay("25:00"))
    }
}
