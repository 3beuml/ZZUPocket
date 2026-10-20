package com.zzupocket.app.data.gms

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 教学周反推的单测。
 *
 * 这条逻辑决定"研究生课表能不能落到日历上"：研究生系统不给学期起止日期，只能用
 * 「整学期课表」与「本周课表」比对出当前周，再倒推第 1 周的周一。错一周，整张课表就整体错位。
 */
class GmsWeekAlignTest {

    private fun entry(week: Int, weekday: Int, period: Int, name: String) =
        GmsClient.WeekEntry(week, weekday, period, name, null, null, null, null)

    private fun lesson(weekday: Int, period: Int, name: String) = GradLesson(weekday, period, name)

    @Test
    fun `本周课表与第 7 周匹配时判为第 7 周`() {
        val term = listOf(
            entry(6, 1, 1, "高等数学"),
            entry(7, 1, 1, "高等数学"),
            entry(7, 2, 3, "数值分析"),
            entry(8, 1, 1, "高等数学"),
        )
        val today = listOf(lesson(1, 1, "高等数学"), lesson(2, 3, "数值分析"))
        assertEquals(7, GmsWeekAlign.currentWeekIndex(term, today))
    }

    @Test
    fun `同一门课多周都命中时取命中数最多的那周`() {
        val term = listOf(
            // 第 3 周只有 1 门对得上
            entry(3, 1, 1, "高等数学"),
            // 第 4 周 2 门都对得上
            entry(4, 1, 1, "高等数学"),
            entry(4, 2, 3, "数值分析"),
        )
        val today = listOf(lesson(1, 1, "高等数学"), lesson(2, 3, "数值分析"))
        assertEquals(4, GmsWeekAlign.currentWeekIndex(term, today))
    }

    @Test
    fun `全不命中或空数据时返回 null（调用方据此降级，不硬猜）`() {
        val term = listOf(entry(7, 1, 1, "高等数学"))
        assertNull(GmsWeekAlign.currentWeekIndex(term, listOf(lesson(5, 9, "体育"))))
        assertNull(GmsWeekAlign.currentWeekIndex(term, emptyList()))
        assertNull(GmsWeekAlign.currentWeekIndex(emptyList(), listOf(lesson(1, 1, "高等数学"))))
    }

    @Test
    fun `周次算第 1 周的周一`() {
        // 2026-10-17 是周六；本周周一 = 2026-10-12
        val saturday = LocalDate.of(2026, 10, 17)
        assertEquals(LocalDate.of(2026, 10, 12), GmsWeekAlign.weekOneMonday(saturday, currentWeek = 1))
        assertEquals(LocalDate.of(2026, 10, 5), GmsWeekAlign.weekOneMonday(saturday, currentWeek = 2))
        assertEquals(LocalDate.of(2026, 9, 7), GmsWeekAlign.weekOneMonday(saturday, currentWeek = 6))
        // 周日的周一也在同一周
        assertEquals(LocalDate.of(2026, 10, 12), GmsWeekAlign.weekOneMonday(LocalDate.of(2026, 10, 18), currentWeek = 1))
        // 周次非法时不越界（至少回到本周周一）
        assertEquals(LocalDate.of(2026, 10, 12), GmsWeekAlign.weekOneMonday(saturday, currentWeek = 0))
    }

    @Test
    fun `匹配键对课程名两侧空白不敏感`() {
        assertEquals(GmsWeekAlign.key(1, 2, "高等数学"), GmsWeekAlign.key(1, 2, "  高等数学 "))
    }

    // ── 以下为 2026-10-20 的回归：第 7 周被显示成第 10 周 ──────────────────

    @Test
    fun `整周课表唯一一致时才判为唯一解`() {
        val term = listOf(
            entry(6, 1, 1, "高等数学"),
            entry(7, 1, 1, "高等数学"),
            entry(7, 2, 3, "数值分析"),
            entry(10, 1, 1, "高等数学"),
            entry(10, 2, 3, "数值分析"),
            entry(10, 3, 5, "机器学习"),
        )
        val today = listOf(lesson(1, 1, "高等数学"), lesson(2, 3, "数值分析"))
        val guess = GmsWeekAlign.guess(term, today)
        // 第 7 周与第 10 周都"包含"这节课，但只有第 7 周是整周完全一致
        assertEquals(7, guess.week)
        assertEquals(true, guess.unique)
        assertEquals(7, guess.anchoredWeekOrNull())
    }

    @Test
    fun `每周课表完全相同时判为无法区分_不产出锚点`() {
        // 一周只有一门每周都上的课 —— 光看课程根本分不出是哪一周
        val term = (1..19).map { entry(it, 1, 1, "高等数学") }
        val today = listOf(lesson(1, 1, "高等数学"))
        val guess = GmsWeekAlign.guess(term, today)
        assertNull(guess.week)
        assertEquals(false, guess.unique)
        assertNull(guess.anchoredWeekOrNull())
    }

    @Test
    fun `锚点按日期推算当前周_跨周不漂移`() {
        // 2026-09-07 是第 1 周周一；调查里"今天是 2026-10-19" = 开学后第 7 周
        val weekOne = LocalDate.of(2026, 9, 7)
        assertEquals(1, GmsWeekAlign.weekIndexAt(weekOne, LocalDate.of(2026, 9, 7)))
        assertEquals(1, GmsWeekAlign.weekIndexAt(weekOne, LocalDate.of(2026, 9, 13)))
        assertEquals(2, GmsWeekAlign.weekIndexAt(weekOne, LocalDate.of(2026, 9, 14)))
        assertEquals(7, GmsWeekAlign.weekIndexAt(weekOne, LocalDate.of(2026, 10, 19)))
        assertEquals(7, GmsWeekAlign.weekIndexAt(weekOne, LocalDate.of(2026, 10, 25)))
        assertEquals(8, GmsWeekAlign.weekIndexAt(weekOne, LocalDate.of(2026, 10, 26)))
    }

    @Test
    fun `手动校正后由校正周次倒推第 1 周周一再算回来要一致`() {
        val today = LocalDate.of(2026, 10, 19)
        for (week in 1..19) {
            val monday = GmsWeekAlign.weekOneMonday(today, week)
            assertEquals(week, GmsWeekAlign.weekIndexAt(monday, today))
        }
    }
}
