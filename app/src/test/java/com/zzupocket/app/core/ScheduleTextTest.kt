package com.zzupocket.app.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleTextTest {

    // ── 星期表头下面那行日期 ────────────────────────────────────────────────
    // 这一组钉的是课表页表头的日期：第 N 周 → 那一周每天的日期。
    // 算错一天的后果很直接：星期三下面写着星期二的日期。

    @Test
    fun `起始日正好是周一时_第 N 周的周一`() {
        val start = LocalDate.of(2026, 9, 7)   // 周一
        assertEquals(start, ScheduleText.mondayOf(start, 1))
        assertEquals(LocalDate.of(2026, 9, 28), ScheduleText.mondayOf(start, 4))
    }

    @Test
    fun `起始日在周中时_先对齐到那一周的周一`() {
        // 教务给的起始日常常是周中某天（这里给周三），第 1 周仍应从周一算起
        val start = LocalDate.of(2026, 9, 9)   // 周三
        assertEquals(LocalDate.of(2026, 9, 7), ScheduleText.mondayOf(start, 1))
        assertEquals(LocalDate.of(2026, 9, 14), ScheduleText.mondayOf(start, 2))
    }

    @Test
    fun `日期用完整的月-日并补零`() {
        assertEquals("09-30", ScheduleText.dayLabel(LocalDate.of(2026, 9, 30)))
        assertEquals("10-01", ScheduleText.dayLabel(LocalDate.of(2026, 10, 1)))
        assertEquals("01-04", ScheduleText.dayLabel(LocalDate.of(2027, 1, 4)))
    }

    @Test
    fun `跨月与跨年那一周的日期是连续的`() {
        val start = LocalDate.of(2026, 9, 7)
        // 第 4 周：9/28 起，跨到 10 月
        val week4 = ScheduleText.mondayOf(start, 4)!!
        assertEquals(
            listOf("09-28", "09-29", "09-30", "10-01", "10-02", "10-03", "10-04"),
            (0..6).map { ScheduleText.dayLabel(week4.plusDays(it.toLong())) },
        )
        // 跨年：第 17 周从 12/28 起
        val week17 = ScheduleText.mondayOf(start, 17)!!
        assertEquals(
            listOf("12-28", "12-29", "12-30", "12-31", "01-01", "01-02", "01-03"),
            (0..6).map { ScheduleText.dayLabel(week17.plusDays(it.toLong())) },
        )
    }

    @Test
    fun `没有起始日或周次非法时不显示日期`() {
        assertNull(ScheduleText.mondayOf(null, 3))
        assertNull(ScheduleText.mondayOf(LocalDate.of(2026, 9, 7), 0))
        assertNull(ScheduleText.mondayOf(LocalDate.of(2026, 9, 7), -1))
    }

    @Test
    fun `连续区间压缩`() {
        assertEquals("1-4, 6-16周", ScheduleText.compressWeeks((1..4) + (6..16)))
        assertEquals("1-16周", ScheduleText.compressWeeks((1..16).toList()))
        assertEquals("1, 4, 7周", ScheduleText.compressWeeks(listOf(1, 4, 7)))
        assertEquals("5周", ScheduleText.compressWeeks(listOf(5)))
    }

    @Test
    fun `全单周与全双周用文字表示`() {
        // 逐周列出来会变成 "1, 3, 5, 7, 9, 11, 13, 15周"，很难读
        assertEquals("1-15周（单周）", ScheduleText.compressWeeks(listOf(1, 3, 5, 7, 9, 11, 13, 15)))
        assertEquals("2-16周（双周）", ScheduleText.compressWeeks(listOf(2, 4, 6, 8, 10, 12, 14, 16)))
    }

    @Test
    fun `单周课偶尔多上一周`() {
        // 真实数据里出现过：1,3,5,...,15 再加上第 16 周
        // 以前会输出 "1, 3, 5, 7, 9, 11, 13, 15-16周"，很难读
        assertEquals(
            "1-15周（单周） + 第16周",
            ScheduleText.compressWeeks(listOf(1, 3, 5, 7, 9, 11, 13, 15, 16)),
        )
        assertEquals(
            "1-15周（单周） + 2, 16周",
            ScheduleText.compressWeeks(listOf(1, 2, 3, 5, 7, 9, 11, 13, 15, 16)),
        )
    }

    @Test
    fun `补周太多时不再套用单双周`() {
        // 偶数周有 4 个，已经不能算"零星补周"了，退回逐个区间
        assertEquals(
            "1-8周",
            ScheduleText.compressWeeks(listOf(1, 2, 3, 4, 5, 6, 7, 8)),
        )
    }

    @Test
    fun `偶数个但非全同奇偶时不套用单双周`() {
        // 1..8 全都有，不应该被当成单周或双周
        assertEquals("1-8周", ScheduleText.compressWeeks((1..8).toList()))
        // 只有两三个奇数周时也不加（否则 "1-5周（单周）" 反而绕）
        assertEquals("1, 3周", ScheduleText.compressWeeks(listOf(1, 3)))
    }

    @Test
    fun `空集合不崩`() {
        assertEquals("—", ScheduleText.compressWeeks(emptyList()))
    }

    @Test
    fun `时段文本`() {
        assertEquals(
            "周二 3-4节 · 周四 5-6节",
            ScheduleText.timeSlots(listOf(Triple(2, 3, 4), Triple(4, 5, 6))),
        )
        assertEquals("周一 1节", ScheduleText.timeSlots(listOf(Triple(1, 1, 1))))
    }

    @Test
    fun `从 ISO 时间串里取时刻`() {
        assertEquals("08:00", ScheduleText.timeOfDay("2026-09-22T08:00:00+08:00"))
        assertEquals(null, ScheduleText.timeOfDay(null))
        assertEquals(null, ScheduleText.timeOfDay("不是时间"))
    }
}
