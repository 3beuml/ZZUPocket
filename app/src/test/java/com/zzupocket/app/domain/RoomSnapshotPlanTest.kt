package com.zzupocket.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空教室快照分批抓取计划的纯逻辑（2026-11-24 拍板：核心楼优先、完整才落库）。
 */
class RoomSnapshotPlanTest {

    // ── batches：置顶核心楼合为第一批，其余分块 ─────────────────────────

    @Test
    fun `空输入返回空`() {
        assertTrue(RoomSnapshotPlan.batches(emptyList()).isEmpty())
    }

    @Test
    fun `置顶核心楼合为第一批且按北南顺序`() {
        val batches = RoomSnapshotPlan.batches(
            listOf(
                101L to "综合楼", // 非置顶
                7L to "南3",
                1L to "北1",
                5L to "北5",
                102L to "实验楼",
            ),
        )
        assertEquals(2, batches.size)
        // 置顶项按 PINNED 顺序（北1、北5、南3），与筛选置顶同源
        assertEquals(listOf(1L, 5L, 7L), batches[0])
        // 其余保持字典原相对顺序
        assertEquals(listOf(101L, 102L), batches[1])
    }

    @Test
    fun `其余楼栋按REST_BATCH_SIZE分块`() {
        val rest = (1..65).map { (1000L + it) to "楼$it" }
        val batches = RoomSnapshotPlan.batches(rest)
        assertEquals(3, batches.size)
        assertTrue(batches[0].size <= RoomSnapshotPlan.REST_BATCH_SIZE)
        assertTrue(batches[1].size <= RoomSnapshotPlan.REST_BATCH_SIZE)
        assertEquals(5, batches[2].size)
    }

    @Test
    fun `全部置顶只有一批`() {
        val pinned = listOf(1L to "北1", 10L to "南5")
        val batches = RoomSnapshotPlan.batches(pinned)
        assertEquals(1, batches.size)
        assertEquals(listOf(1L, 10L), batches[0])
    }

    @Test
    fun `全部非置顶没有核心批`() {
        val batches = RoomSnapshotPlan.batches(listOf(9L to "物理楼", 8L to "化学楼"))
        assertEquals(1, batches.size)
        assertEquals(listOf(9L, 8L), batches[0])
    }

    // ── RoomDayResult：完整性判定 ─────────────────────────────────────

    @Test
    fun `全部落库或跳过才算完整`() {
        assertTrue(RoomDayResult(totalBatches = 3, savedBatches = 2, skippedBatches = 1).complete)
        assertTrue(RoomDayResult(totalBatches = 3, savedBatches = 0, skippedBatches = 3).complete)
        assertFalse(RoomDayResult(totalBatches = 3, savedBatches = 1, skippedBatches = 1).complete)
        assertFalse(RoomDayResult(totalBatches = 0, savedBatches = 0, skippedBatches = 0).complete)
    }

    // ── 预取续跑：连续不完整才停 ─────────────────────────────────────

    @Test
    fun `未达连续阈值继续`() {
        assertTrue(shouldContinuePrefetch(0))
        assertTrue(shouldContinuePrefetch(1))
    }

    @Test
    fun `连续两天不完整就停`() {
        assertFalse(shouldContinuePrefetch(2))
        assertFalse(shouldContinuePrefetch(3))
    }
}
