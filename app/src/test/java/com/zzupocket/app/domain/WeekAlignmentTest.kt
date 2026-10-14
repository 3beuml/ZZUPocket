package com.zzupocket.app.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「课表该显示第几周」的回归测试。
 *
 * 钉住的是真机上出过的问题：今天页显示第 4 周，课表页却停在第 3 周（9/21 - 9/27）——
 * 课表页的周次只在"首次载入 / 手动刷新 / 换学期"时才算一次，`onResume` 什么也不做，
 * 所以隔天（尤其跨周）打开还在旧周上。
 */
class WeekAlignmentTest {

    private val monday = LocalDate.of(2026, 9, 28)   // 第 4 周周一
    private val wednesday = LocalDate.of(2026, 9, 30)

    @Test
    fun `第一次载入按当前周`() {
        assertEquals(4, WeekAlignment.currentWeek(currentWeek = 4, firstWeek = 1))
    }

    @Test
    fun `学期外退回第一周`() {
        // 寒暑假/开学前 resolveCurrentWeek 返回 null，不能显示成第 0 周
        assertEquals(1, WeekAlignment.currentWeek(currentWeek = null, firstWeek = 1))
        assertEquals(3, WeekAlignment.currentWeek(currentWeek = null, firstWeek = 3))
    }

    @Test
    fun `连周次列表都没有时退回第一周`() {
        assertEquals(1, WeekAlignment.currentWeek(currentWeek = null, firstWeek = null))
    }

    @Test
    fun `同一天内不重算_用户手动翻的周要保留`() {
        assertFalse(WeekAlignment.needsRealign(resolvedOn = wednesday, today = wednesday))
        // 用户在周三翻到第 1 周看老课，切走再切回来仍然是第 1 周
        assertEquals(
            1,
            WeekAlignment.realigned(
                resolvedOn = wednesday,
                today = wednesday,
                storedWeek = 1,
                currentWeek = 4,
                firstWeek = 1,
            ),
        )
    }

    @Test
    fun `隔天就要重新对准当前周`() {
        val thursday = wednesday.plusDays(1)
        assertTrue(WeekAlignment.needsRealign(resolvedOn = wednesday, today = thursday))
        // 周三手动翻到第 1 周，周四再打开 → 回到当前周（第 4 周）
        assertEquals(
            4,
            WeekAlignment.realigned(
                resolvedOn = wednesday,
                today = thursday,
                storedWeek = 1,
                currentWeek = 4,
                firstWeek = 1,
            ),
        )
    }

    @Test
    fun `跨周后回到新的当前周`() {
        // 周日（第 4 周）→ 周一（第 5 周）
        val sunday = LocalDate.of(2026, 10, 4)
        val nextMonday = LocalDate.of(2026, 10, 5)
        assertEquals(
            5,
            WeekAlignment.realigned(
                resolvedOn = sunday,
                today = nextMonday,
                storedWeek = 4,
                currentWeek = 5,
                firstWeek = 1,
            ),
        )
    }

    @Test
    fun `还没算过周次时按当前周`() {
        assertEquals(
            4,
            WeekAlignment.realigned(
                resolvedOn = null,
                today = wednesday,
                storedWeek = 1,     // 状态里的默认值
                currentWeek = 4,
                firstWeek = 1,
            ),
        )
    }

    @Test
    fun `改成按周一算的那天也一样_只比日期不比时刻`() {
        assertFalse(WeekAlignment.needsRealign(resolvedOn = monday, today = monday))
        assertTrue(WeekAlignment.needsRealign(resolvedOn = monday, today = monday.plusDays(1)))
    }

    // ── 日期 → 教学周（补课靠它进网格）──────────────────────────────────

    private val weeks = (1..18).toList()

    @Test
    fun `按日期反推教学周`() {
        val start = LocalDate.of(2026, 9, 7)   // 周一，第 1 周
        fun idx(d: LocalDate) = WeekAlignment.indexOf(start, false, weeks, d)

        assertEquals(1, idx(start))
        assertEquals(1, idx(start.plusDays(6)))     // 第 1 周周日
        assertEquals(2, idx(start.plusDays(7)))     // 第 2 周周一
        // 补课最常落的周末：第 3 周周六 / 周日
        assertEquals(3, idx(start.plusDays(14 + 5)))
        assertEquals(3, idx(start.plusDays(14 + 6)))
        assertEquals(4, idx(monday))
    }

    @Test
    fun `学期开始之前返回 null`() {
        val start = LocalDate.of(2026, 9, 7)
        assertEquals(null, WeekAlignment.indexOf(start, false, weeks, start.minusDays(1)))
    }

    @Test
    fun `超出学期周次返回 null`() {
        val start = LocalDate.of(2026, 9, 7)
        // 第 19 周（学期只有 18 周）—— 假期补课就属于这种，别硬塞进第 18 周
        assertEquals(null, WeekAlignment.indexOf(start, false, weeks, start.plusWeeks(18)))
    }

    @Test
    fun `起始日是周中时对齐到周首算_不会整体错一周`() {
        // 学校给的 startDate 落在周三，同样按那周的周一算第 1 周
        val startWed = LocalDate.of(2026, 9, 9)
        assertEquals(1, WeekAlignment.indexOf(startWed, false, weeks, LocalDate.of(2026, 9, 13)))
        assertEquals(2, WeekAlignment.indexOf(startWed, false, weeks, LocalDate.of(2026, 9, 14)))
    }

    @Test
    fun `按周日开学时以周日为周首`() {
        val start = LocalDate.of(2026, 9, 6)   // 周日
        assertEquals(1, WeekAlignment.indexOf(start, true, weeks, LocalDate.of(2026, 9, 12)))
        assertEquals(2, WeekAlignment.indexOf(start, true, weeks, LocalDate.of(2026, 9, 13)))
    }

    @Test
    fun `没有起始日或没有周次列表时返回 null`() {
        assertEquals(null, WeekAlignment.indexOf(null, false, weeks, monday))
        assertEquals(null, WeekAlignment.indexOf(LocalDate.of(2026, 9, 7), false, emptyList(), monday))
    }
}
