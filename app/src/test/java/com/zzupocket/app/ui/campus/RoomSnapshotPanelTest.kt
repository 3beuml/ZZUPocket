package com.zzupocket.app.ui.campus

import com.zzupocket.app.data.db.RoomSnapshotSummary
import com.zzupocket.app.data.repo.ScheduleRepository
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空教室"本地保存"面板的状态文案与 UI 浏览落库条件（纯函数）。
 * 背景：2026-11-24 用户实测——残缺快照（30 间）冒充"已保存"，校外筛选时表格空白。
 */
class RoomSnapshotPanelTest {

    private fun summary(date: String, rooms: Int, fetchedAt: Long = 0L) = RoomSnapshotSummary(
        date = date,
        campusId = 2L,
        campusName = "主校区",
        rooms = rooms,
        buildings = 5,
        fetchedAt = fetchedAt,
    )

    // ── snapshotSummaryFor：该日期取哪一组快照展示 ─────────────────────

    @Test
    fun `同日多校区取间数最多的一组`() {
        val snapshots = listOf(
            summary("2026-11-24", rooms = 30, fetchedAt = 1L),
            summary("2026-11-24", rooms = 520, fetchedAt = 2L),
            summary("2026-11-25", rooms = 480, fetchedAt = 3L),
        )
        assertEquals(520, snapshotSummaryFor(snapshots, LocalDate.of(2026, 11, 24))?.rooms)
        assertNull(snapshotSummaryFor(snapshots, LocalDate.of(2026, 11, 26)))
    }

    // ── snapshotSavedLabel：完整/部分要分开说 ─────────────────────────

    /** 用"构造本地时区正午"的方式取时间戳，断言与机器时区无关 */
    private val noonOfNov24: Long =
        LocalDate.of(2026, 11, 24).atTime(12, 0).atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()

    @Test
    fun `完整快照只标抓取日期`() {
        assertEquals("已保存 · 11-24 抓", snapshotSavedLabel(520, noonOfNov24))
        assertEquals(
            "已保存 · 11-24 抓",
            snapshotSavedLabel(ScheduleRepository.SNAPSHOT_COMPLETE_MIN_ROOMS, noonOfNov24),
        )
    }

    @Test
    fun `残缺快照标注间数与日期`() {
        assertEquals("已保存部分 · 30间 · 11-24 抓", snapshotSavedLabel(30, noonOfNov24))
        assertEquals("已保存部分 · 99间 · 11-24 抓", snapshotSavedLabel(99, noonOfNov24))
    }

    @Test
    fun `无抓取时间时不带日期`() {
        assertEquals("已保存", snapshotSavedLabel(520, 0L))
        assertEquals("已保存部分 · 30间", snapshotSavedLabel(30, 0L))
    }

    // ── UI 浏览路径落库条件：完整才落库 ───────────────────────────────

    @Test
    fun `无筛选翻到最后一页才落库`() {
        assertTrue(shouldSaveSnapshotOnBrowse(buildingFilterEmpty = true, page = 3, totalPages = 3))
        assertTrue(shouldSaveSnapshotOnBrowse(buildingFilterEmpty = true, page = 5, totalPages = 4))
    }

    @Test
    fun `没翻完或有筛选都不落库`() {
        assertFalse(shouldSaveSnapshotOnBrowse(buildingFilterEmpty = true, page = 2, totalPages = 3))
        assertFalse(shouldSaveSnapshotOnBrowse(buildingFilterEmpty = false, page = 3, totalPages = 3))
        assertFalse(shouldSaveSnapshotOnBrowse(buildingFilterEmpty = true, page = 1, totalPages = 0))
    }
}
