package com.zzupocket.app.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 教学周换算的回归测试。
 *
 * 背景（2026-10-20）：研究生课表把「第 7 周」显示成「第 8 周」，原因是周次靠数据反推
 * 且推断结果被固化。现在周次参数是显式设置，换算全部走这里，所以这一层必须钉死：
 * 尤其「9/7 是第 1 周周一」这个基准（用户与教务一致确认过）。
 */
class WeekMathTest {

    private val weekOne = LocalDate.of(2026, 9, 7)   // 第 1 周周一

    @Test
    fun `周一起算_周次按日期推进`() {
        assertEquals(1, WeekMath.weekIndexAt(weekOne, LocalDate.of(2026, 9, 7)))
        assertEquals(1, WeekMath.weekIndexAt(weekOne, LocalDate.of(2026, 9, 13)))  // 周日仍属第 1 周
        assertEquals(2, WeekMath.weekIndexAt(weekOne, LocalDate.of(2026, 9, 14)))
        assertEquals(7, WeekMath.weekIndexAt(weekOne, LocalDate.of(2026, 10, 19)))
        assertEquals(7, WeekMath.weekIndexAt(weekOne, LocalDate.of(2026, 10, 25)))  // 第 7 周周日
        assertEquals(8, WeekMath.weekIndexAt(weekOne, LocalDate.of(2026, 10, 26)))
    }

    @Test
    fun `开学前返回小于 1 的周次_调用方自行钳制`() {
        assertEquals(0, WeekMath.weekIndexAt(weekOne, LocalDate.of(2026, 9, 6)))
        // 8/31 正好比第 1 周周一早 7 天，落在"第 0 周"
        assertEquals(0, WeekMath.weekIndexAt(weekOne, LocalDate.of(2026, 8, 31)))
        assertEquals(-1, WeekMath.weekIndexAt(weekOne, LocalDate.of(2026, 8, 24)))
    }

    @Test
    fun `改当前周次等价于反推开学日期`() {
        val today = LocalDate.of(2026, 10, 19)
        for (week in 1..19) {
            val start = WeekMath.weekOneStart(today, week, WeekStartDay.MONDAY)
            assertEquals(week, WeekMath.weekIndexAt(start, today))
        }
        // 第 7 周 = 9/7，正是教务校历的基准
        assertEquals(weekOne, WeekMath.weekOneStart(today, 7, WeekStartDay.MONDAY))
    }

    @Test
    fun `某周某天的日期_周一起算`() {
        // 第 7 周：10/19(一) … 10/25(日)
        assertEquals(LocalDate.of(2026, 10, 19), WeekMath.dateOf(weekOne, 7, 1, WeekStartDay.MONDAY))
        assertEquals(LocalDate.of(2026, 10, 25), WeekMath.dateOf(weekOne, 7, 7, WeekStartDay.MONDAY))
        // 第 1 周：9/7(一) … 9/13(日)
        assertEquals(LocalDate.of(2026, 9, 7), WeekMath.dateOf(weekOne, 1, 1, WeekStartDay.MONDAY))
        assertEquals(LocalDate.of(2026, 9, 13), WeekMath.dateOf(weekOne, 1, 7, WeekStartDay.MONDAY))
    }

    @Test
    fun `周日起算时一周的边界与列日期都对`() {
        // 若按周日起算，第 1 周从 9/6(日) 开始
        assertEquals(LocalDate.of(2026, 9, 6), WeekMath.startOfWeek(LocalDate.of(2026, 9, 7), WeekStartDay.SUNDAY))
        val start = WeekMath.weekOneStart(LocalDate.of(2026, 10, 19), 7, WeekStartDay.SUNDAY)
        assertEquals(LocalDate.of(2026, 9, 6), start)
        // 周日的偏移是 0（它是一周第一天），周一是 1
        assertEquals(0, WeekMath.offsetOf(7, WeekStartDay.SUNDAY))
        assertEquals(1, WeekMath.offsetOf(1, WeekStartDay.SUNDAY))
        assertEquals(LocalDate.of(2026, 9, 6), WeekMath.dateOf(start, 1, 7, WeekStartDay.SUNDAY))
        assertEquals(LocalDate.of(2026, 9, 7), WeekMath.dateOf(start, 1, 1, WeekStartDay.SUNDAY))
    }

    @Test
    fun `周一起算的偏移量`() {
        assertEquals(0, WeekMath.offsetOf(1, WeekStartDay.MONDAY))
        assertEquals(6, WeekMath.offsetOf(7, WeekStartDay.MONDAY))
        // 越界的 weekday 不越界
        assertEquals(0, WeekMath.offsetOf(0, WeekStartDay.MONDAY))
        assertEquals(6, WeekMath.offsetOf(9, WeekStartDay.MONDAY))
    }
}
