package com.zzupocket.app.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusHeatmapTest {

    @Test
    fun `网格列数等于周数`() {
        val grid = FocusHeatmap.build(LocalDate.of(2025, 3, 12), weeks = 53)
        assertEquals(53, grid.size)
        grid.forEach { assertEquals(7, it.days.size) }
    }

    @Test
    fun `最后一列就是今天所在的那一周`() {
        // 2025-03-12 是周三
        val today = LocalDate.of(2025, 3, 12)
        val grid = FocusHeatmap.build(today, weeks = 53)
        val last = grid.last()
        assertEquals(LocalDate.of(2025, 3, 10), last.firstDay) // 周一
        assertEquals(LocalDate.of(2025, 3, 12), last.days[2])
    }

    @Test
    fun `未来的日子留空占位而不是少一个格子`() {
        val today = LocalDate.of(2025, 3, 12) // 周三
        val last = FocusHeatmap.build(today, weeks = 53).last()
        assertEquals(7, last.days.size)
        assertEquals(today, last.days[2])
        assertNull(last.days[3])
        assertNull(last.days[6])
    }

    @Test
    fun `往前正好 52 周`() {
        val today = LocalDate.of(2025, 3, 12)
        val grid = FocusHeatmap.build(today, weeks = 53)
        assertEquals(LocalDate.of(2024, 3, 11), grid.first().firstDay)
    }

    @Test
    fun `月份标签只在含 1 号的那一列出现`() {
        val grid = FocusHeatmap.build(LocalDate.of(2025, 3, 12), weeks = 6)
        val labels = grid.map { FocusHeatmap.monthLabel(it) }
        // 六列分别是 2/3、2/10、2/17、2/24、3/3、3/10；3 月 1 日是周六，落在 2/24 那一列
        assertEquals(listOf(null, null, null, "3月", null, null), labels)
    }

    @Test
    fun `强度五档逐级变深`() {
        assertEquals(0f, FocusHeatmap.intensity(0), 0.001f)
        val values = (1..4).map { FocusHeatmap.intensity(it) }
        assertEquals(values.sorted(), values)
        assertEquals(1f, values.last(), 0.001f)
    }

    @Test
    fun `连续打卡天数`() {
        val today = LocalDate.of(2025, 3, 12)
        val data = mapOf(
            today to 600,
            today.minusDays(1) to 600,
            today.minusDays(2) to 600,
            today.minusDays(4) to 600, // 断了一天
        )
        assertEquals(3, FocusHeatmap.streak(data, today))
        assertEquals(0, FocusHeatmap.streak(emptyMap(), today))
    }
}
