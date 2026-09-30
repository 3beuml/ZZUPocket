package com.zzupocket.app.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusStatsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private var nextId = 1L

    private fun focus(
        seconds: Int,
        completed: Boolean = true,
        tagId: Long? = null,
        tagName: String = "",
        startedAt: Long = at(2025, 3, 10, 20),
    ) = FocusRecord(
        id = nextId++,
        tagId = tagId,
        tagName = tagName,
        phase = Phase.FOCUS,
        startedAt = startedAt,
        endedAt = startedAt + seconds * 1000L,
        actualSeconds = seconds,
        completed = completed,
    )

    private fun breakRecord(seconds: Int, startedAt: Long = at(2025, 3, 10, 20)) = FocusRecord(
        id = nextId++,
        tagId = null,
        tagName = "",
        phase = Phase.SHORT_BREAK,
        startedAt = startedAt,
        endedAt = startedAt + seconds * 1000L,
        actualSeconds = seconds,
        completed = true,
    )

    // ── 一天从凌晨 4 点算起 ──────────────────────────────────────────────

    @Test
    fun `凌晨 4 点之前算前一天`() {
        assertEquals(
            LocalDate.of(2025, 3, 10),
            FocusStats.dayKey(at(2025, 3, 11, 1, 30), zone),
        )
    }

    @Test
    fun `凌晨 4 点整开始算新的一天`() {
        assertEquals(
            LocalDate.of(2025, 3, 11),
            FocusStats.dayKey(at(2025, 3, 11, 4, 0), zone),
        )
        assertEquals(
            LocalDate.of(2025, 3, 10),
            FocusStats.dayKey(at(2025, 3, 11, 3, 59), zone),
        )
    }

    @Test
    fun `一天的起点是 4 点`() {
        val start = FocusStats.dayStart(LocalDate.of(2025, 3, 10), zone)
        assertEquals(at(2025, 3, 10, 4), start)
        assertEquals(at(2025, 3, 11, 4), FocusStats.dayEnd(LocalDate.of(2025, 3, 10), zone))
    }

    // ── 汇总口径 ────────────────────────────────────────────────────────

    @Test
    fun `时长含中断场次但打卡只算走完的`() {
        val records = listOf(
            focus(seconds = 25 * 60, completed = true),
            focus(seconds = 10 * 60, completed = false),
            focus(seconds = 25 * 60, completed = true),
        )
        val totals = FocusStats.totals(records)
        assertEquals(60 * 60, totals.seconds)
        assertEquals(2, totals.sessions)
    }

    @Test
    fun `休息不计入统计`() {
        val records = listOf(focus(seconds = 60), breakRecord(seconds = 300))
        val totals = FocusStats.totals(records)
        assertEquals(60, totals.seconds)
        assertEquals(1, totals.sessions)
    }

    @Test
    fun `按标签汇总按时长降序`() {
        val records = listOf(
            focus(seconds = 600, tagId = 1, tagName = "高数"),
            focus(seconds = 1800, tagId = 2, tagName = "英语"),
            focus(seconds = 300, tagId = 2, tagName = "英语"),
            focus(seconds = 120),
        )
        val byTag = FocusStats.byTag(records)
        assertEquals(listOf("英语", "高数", FocusStats.UNTAGGED), byTag.map { it.tagName })
        assertEquals(2100, byTag[0].totals.seconds)
        assertNull(byTag[2].tagId)
    }

    // ── 热力图分档 ──────────────────────────────────────────────────────

    @Test
    fun `热力图五档边界`() {
        assertEquals(0, FocusStats.heatTier(0))
        assertEquals(1, FocusStats.heatTier(29 * 60 + 59))
        assertEquals(2, FocusStats.heatTier(30 * 60))
        assertEquals(2, FocusStats.heatTier(59 * 60 + 59))
        assertEquals(3, FocusStats.heatTier(60 * 60))
        assertEquals(3, FocusStats.heatTier(119 * 60 + 59))
        assertEquals(4, FocusStats.heatTier(120 * 60))
        assertEquals(4, FocusStats.heatTier(600 * 60))
    }

    // ── 区间汇总 ────────────────────────────────────────────────────────

    @Test
    fun `今天只算今天那一份`() {
        val records = listOf(
            focus(seconds = 600, startedAt = at(2025, 3, 10, 20)),
            focus(seconds = 900, startedAt = at(2025, 3, 9, 20)),
            focus(seconds = 300, startedAt = at(2025, 3, 10, 2)), // 凌晨 2 点 → 属于 3 月 9 日
        )
        val summary = FocusStats.summarize(records, now = at(2025, 3, 10, 22), zone = zone)
        assertEquals(600, summary.today.seconds)
        assertEquals(1800, summary.allTime.seconds)
    }

    @Test
    fun `周汇总从周一算起`() {
        // 2025-03-10 是周一
        val records = listOf(
            focus(seconds = 600, startedAt = at(2025, 3, 10, 9)),
            focus(seconds = 900, startedAt = at(2025, 3, 16, 22)), // 周日，仍属本周
            focus(seconds = 1200, startedAt = at(2025, 3, 17, 9)), // 下周一
        )
        val summary = FocusStats.summarize(records, now = at(2025, 3, 16, 23), zone = zone)
        assertEquals(1500, summary.week.seconds)
        assertEquals(1500, summary.month.seconds)
        assertEquals(2700, summary.allTime.seconds)
    }

    @Test
    fun `跨年记录不会被算进今年`() {
        val records = listOf(
            focus(seconds = 600, startedAt = at(2024, 12, 31, 20)),
            focus(seconds = 900, startedAt = at(2025, 1, 1, 20)),
        )
        val summary = FocusStats.summarize(records, now = at(2025, 6, 1, 12), zone = zone)
        assertEquals(900, summary.year.seconds)
        assertEquals(1500, summary.allTime.seconds)
    }

    @Test
    fun `每天的秒数按凌晨四点归并`() {
        val records = listOf(
            focus(seconds = 600, startedAt = at(2025, 3, 10, 20)),
            focus(seconds = 300, startedAt = at(2025, 3, 11, 1)), // 仍算 3 月 10 日
        )
        val byDay = FocusStats.secondsByDay(records, zone)
        assertEquals(900, byDay[LocalDate.of(2025, 3, 10)])
        assertEquals(null, byDay[LocalDate.of(2025, 3, 11)])
    }

    @Test
    fun `时长文案`() {
        assertEquals("—", FocusStats.humanDuration(0))
        assertEquals("不到 1 分钟", FocusStats.humanDuration(30))
        assertEquals("25 分钟", FocusStats.humanDuration(25 * 60))
        assertEquals("1 小时", FocusStats.humanDuration(3600))
        assertEquals("1 小时 20 分", FocusStats.humanDuration(80 * 60))
    }
}
