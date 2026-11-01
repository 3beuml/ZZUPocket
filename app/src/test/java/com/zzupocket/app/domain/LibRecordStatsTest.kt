package com.zzupocket.app.domain

import java.time.LocalDate
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 图书馆预约记录展示模型的单测。字段名与值形态以 2026-10-31 真机 dump 为准
 * （座位记录有 `beginTime/endTime/nameMerge/name/status_name/signInTime`，
 * 空间记录是下划线形态 `begin_time` 且带 `title`；响应里**没有** create_time）。
 */
class LibRecordStatsTest {

    /** 真机 dump 的第一条座位记录（2026-10-29，主校区图书馆三楼 007 座） */
    private val realSeatRecord = buildJsonObject {
        put("id", "6306382")
        put("status", "4")
        put("space", "3827")
        put("booker", "202400000001")
        put("oksign", "0")
        put("cancel", "0")
        put("beginTime", "2026-10-29 13:49:34")
        put("endTime", "2026-10-29 22:30:00")
        put("examTime", "2026-10-29 13:49:34")
        put("cancelTime", "")
        put("start", "13:49")
        put("end", "15:47")
        put("day", "10-29")
        put("signInTime", "2026-10-29 14:01:35")
        put("signOutTime", "15:47")
        put("nameMerge", "主校区图书馆-三楼-三楼西")
        put("ennameMerge", "Main Campus Library-3F-3F West")
        put("no", "007")
        put("name", "007")
        put("status_name", "已结束")
        put("status_en_name", "Ended")
    }

    @Test
    fun `座位记录标题是地点加座位号、日期按 beginTime 解析`() {
        val record = LibRecord.ofSeat(realSeatRecord, "普通座位")
        assertEquals("主校区图书馆-三楼-三楼西 · 007", record.title)
        assertEquals("普通座位", record.kind)
        assertEquals("已结束", record.statusName)
        assertEquals("2026-10-29 13:49:34 ~ 2026-10-29 22:30:00", record.range)
        assertEquals("2026-10-29 14:01:35", record.signInTime)
        assertEquals(LocalDate.of(2026, 10, 29), record.day)
    }

    @Test
    fun `空间记录用下划线字段并带事项标题`() {
        val raw = buildJsonObject {
            put("nameMerge", "中心馆-研讨间")
            put("title", "小组讨论")
            put("status_name", "使用中")
            put("begin_time", "2026-10-30 09:00:00")
            put("end_time", "2026-10-30 11:00:00")
        }
        val record = LibRecord.ofSpace(raw, "空间")
        assertEquals("中心馆-研讨间 · 小组讨论", record.title)
        assertEquals("使用中", record.statusName)
        assertEquals("2026-10-30 09:00:00 ~ 2026-10-30 11:00:00", record.range)
        assertNull(record.signInTime)
        assertEquals(LocalDate.of(2026, 10, 30), record.day)
    }

    @Test
    fun `热力图分档是次数口径`() {
        assertEquals(0, LibRecordStats.heatTier(0))
        assertEquals(1, LibRecordStats.heatTier(1))
        assertEquals(2, LibRecordStats.heatTier(2))
        assertEquals(3, LibRecordStats.heatTier(3))
        assertEquals(4, LibRecordStats.heatTier(4))
        assertEquals(4, LibRecordStats.heatTier(9))
    }

    @Test
    fun `按天聚合与连续天数`() {
        val records = listOf(
            record("2026-10-29"),
            record("2026-10-29"), // 同日两次
            record("2026-10-28"),
            record(null), // 日期解析不出 → 不进图
        )
        val counts = LibRecordStats.countsByDay(records)
        assertEquals(2, counts[LocalDate.of(2026, 10, 29)])
        assertEquals(1, counts[LocalDate.of(2026, 10, 28)])
        assertEquals(2, counts.size)
        // 从 10-29 往前数：29 有、28 有、27 没有
        assertEquals(2, LibRecordStats.streak(counts, LocalDate.of(2026, 10, 29)))
        // 今天没约 → 0
        assertEquals(0, LibRecordStats.streak(counts, LocalDate.of(2026, 10, 30)))
    }

    private fun record(day: String?): LibRecord = LibRecord(
        title = "t",
        kind = "普通座位",
        statusName = "已结束",
        range = null,
        signInTime = null,
        day = day?.let(LocalDate::parse),
        raw = buildJsonObject { put("x", "y") },
    )
}
