package com.zzupocket.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomOccupancyGridTest {

    /** 郑大的作息：第 1 节 08:00-08:45，第 2 节 08:55-09:40，第 3 节 10:10-10:55 */
    private val units = listOf(800 to 845, 855 to 940, 1010 to 1055)

    @Test
    fun `完全重合的占用会涂满对应格子`() {
        val grid = RoomOccupancyGrid.grid(listOf(RoomOccupation(800, 845)), units)
        assertEquals(listOf(true, false, false), grid)
    }

    @Test
    fun `跨两节的课会同时涂两格`() {
        // 一门 08:00-09:40 的连堂课
        val grid = RoomOccupancyGrid.grid(listOf(RoomOccupation(800, 940)), units)
        assertEquals(listOf(true, true, false), grid)
    }

    @Test
    fun `首尾相接不算占用`() {
        // 08:45-08:55 只是课间，不该把第 1、2 节都算占用
        val grid = RoomOccupancyGrid.grid(listOf(RoomOccupation(845, 855)), units)
        assertEquals(listOf(false, false, false), grid)
        assertFalse(RoomOccupancyGrid.overlaps(845, 855, 800, 845))
        assertFalse(RoomOccupancyGrid.overlaps(845, 855, 855, 940))
    }

    @Test
    fun `只在节次中间占一部分也算占用`() {
        // 10:20-10:40 落在第 3 节里
        val grid = RoomOccupancyGrid.grid(listOf(RoomOccupation(1020, 1040)), units)
        assertEquals(listOf(false, false, true), grid)
    }

    @Test
    fun `多段占用叠加`() {
        val grid = RoomOccupancyGrid.grid(
            listOf(RoomOccupation(800, 845), RoomOccupation(1010, 1055)),
            units,
        )
        assertEquals(listOf(true, false, true), grid)
    }

    @Test
    fun `没有占用就是全空`() {
        val grid = RoomOccupancyGrid.grid(emptyList(), units)
        assertEquals(listOf(false, false, false), grid)
        assertTrue(grid.none { it })
    }

    @Test
    fun `占用早于第一节课不影响后面的节次`() {
        // 07:00-07:30 的占用（比如早自习），第 1 节 08:00 开始，不重叠
        val grid = RoomOccupancyGrid.grid(listOf(RoomOccupation(700, 730)), units)
        assertEquals(listOf(false, false, false), grid)
    }

    @Test
    fun `教室号排序按数值而不是字符串`() {
        val codes = listOf("北1_1001", "北1_101", "北1_102", "北1_99")
        val sorted = codes.sortedWith(RoomOccupancyGrid.byCode)
        // 99 < 101 < 102 < 1001（字符串排序会把 1001 排在 101 前面，那是错的）
        assertEquals(listOf("北1_99", "北1_101", "北1_102", "北1_1001"), sorted)
    }

    @Test
    fun `按楼分组并按字典顺序排列`() {
        fun row(code: String, buildingId: Long, building: String) = RoomRow(
            id = code.hashCode().toLong(),
            code = code,
            buildingId = buildingId,
            buildingName = building,
            campusName = "主校区",
            occupied = listOf(false, true, false),
        )
        val rooms = listOf(
            row("南3_101", 3, "南3"),
            row("北2_102", 2, "北2"),
            row("北2_101", 2, "北2"),
            row("北1_101", 1, "北1"),
        )
        // 字典顺序：北1(1) → 北2(2) → 南3(3)
        val grouped = RoomOccupancyGrid.groupByBuilding(rooms, listOf(1, 2, 3))
        assertEquals(listOf("北1", "北2", "南3"), grouped.map { it.first })
        assertEquals(listOf("北2_101", "北2_102"), grouped[1].second.map { it.code })
    }

    @Test
    fun `全天空闲与占用数量的判断`() {
        val free = RoomRow(1, "北1_101", 1, "北1", "主校区", listOf(false, false, false))
        val busy = RoomRow(2, "北1_102", 1, "北1", "主校区", listOf(true, false, true))
        assertTrue(free.freeAllDay)
        assertFalse(busy.freeAllDay)
        assertEquals(2, busy.occupiedCount)
    }

    @Test
    fun `北1到北5 南1到南5 置顶 其余保持原顺序`() {
        val names = listOf("理科楼", "北3", "南1", "文科楼", "北1", "南5", "材料馆", "北2")
        val sorted = RoomBuildingOrder.sorted(names) { it }
        assertEquals(
            listOf("北1", "北2", "北3", "南1", "南5", "理科楼", "文科楼", "材料馆"),
            sorted,
        )
    }

    @Test
    fun `没有置顶楼栋时保持原顺序`() {
        val names = listOf("文科楼", "理科楼", "材料馆")
        assertEquals(names, RoomBuildingOrder.sorted(names) { it })
    }

    @Test
    fun `房间号取名字里最后一段数字`() {
        assertEquals("101", RoomNames.numberOf("北1_101", "北1_101"))
        assertEquals("101", RoomNames.numberOf("北1-101", "北1-101"))
        assertEquals("101", RoomNames.numberOf("101", "101"))
        assertEquals("1101", RoomNames.numberOf("南10_1101", "南10_1101"))
    }

    @Test
    fun `code 是内部编码时不会显示那串编码`() {
        // 实测：有些教室的 code 是 12 位内部编码，而 nameZh 是人类可读名
        assertEquals("101", RoomNames.numberOf("北1_101", "101000000001"))
        assertEquals("101", RoomNames.numberOf("北1_101", ""))
    }

    @Test
    fun `nameZh 取不出时才退回 code`() {
        assertEquals("305", RoomNames.numberOf("", "A-305"))
        assertEquals("未知教室", RoomNames.numberOf("未知教室", ""))
        assertEquals("", RoomNames.numberOf("", ""))
    }
}
