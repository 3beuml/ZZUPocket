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
}
