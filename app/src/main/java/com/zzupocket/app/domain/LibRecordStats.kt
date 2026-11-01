package com.zzupocket.app.domain

import com.zzupocket.app.data.lib.LibClient
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject

/**
 * 图书馆预约记录的**展示模型**（从原始 JsonObject 提取，字段以 2026-10-31 真机 dump 为准）：
 *
 *  - 座位记录：`beginTime/endTime`（完整时间）、`day`（预格式化 MM-dd，别用它做聚合——没年份）、
 *    `nameMerge`（地点，如"主校区图书馆-三楼-三楼西"）、`name`/`no`（座位号）、
 *    `status`/`status_name`（"已结束"等）、`signInTime`/`signOutTime`、`timelist[]`（签到时间线）、
 *    `oksign`/`cancel`。
 *  - 空间记录：`begin_time/end_time`（下划线）、`title`（预约事项）、`member_name`、`teams[]`。
 *  - 热力图聚合日期：真实响应里**没有** create_time，落在 [LibClient.firstReservationDay]
 *    的 beginTime 候补上（2026-10-31 实测解析出正确日期）。
 */
data class LibRecord(
    /** 展示标题：座位 = 地点 + 座位号；空间 = 地点 + 预约事项 */
    val title: String,
    /** 座位类型 / "空间" */
    val kind: String,
    /** 状态原文（status_name，如"已结束"/"使用中"/"违约"） */
    val statusName: String,
    /** 展示用时段文本（begin ~ end） */
    val range: String?,
    /** 签到时间（座位记录；空 = 未签到） */
    val signInTime: String?,
    /** 热力图聚合日（解析不出 = null，不进图） */
    val day: LocalDate?,
    /** 原始条目（详情展开/未来扩展用） */
    val raw: JsonObject,
) {
    companion object {
        /** 从座位记录条目建模型（type 标注类型：普通/研习/考研） */
        fun ofSeat(raw: JsonObject, kindLabel: String): LibRecord {
            val venue = text(raw, "nameMerge").orEmpty()
            val seat = text(raw, "name") ?: text(raw, "no")
            return LibRecord(
                title = listOf(venue, seat.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                kind = kindLabel,
                statusName = text(raw, "status_name").orEmpty(),
                range = LibClient.rangeText(raw),
                signInTime = text(raw, "signInTime")?.takeIf { it.isNotBlank() },
                day = LibClient.firstReservationDay(raw),
                raw = raw,
            )
        }

        /** 从空间（研讨间）记录条目建模型 */
        fun ofSpace(raw: JsonObject, kindLabel: String): LibRecord {
            val venue = text(raw, "nameMerge").orEmpty()
            val topic = text(raw, "title").orEmpty()
            return LibRecord(
                title = listOf(venue, topic).filter { it.isNotBlank() }.joinToString(" · "),
                kind = kindLabel,
                statusName = text(raw, "status_name").orEmpty(),
                range = LibClient.rangeText(raw),
                signInTime = null,
                day = LibClient.firstReservationDay(raw),
                raw = raw,
            )
        }

        private fun text(raw: JsonObject, key: String): String? =
            (raw[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }
}

/**
 * 记录的纯统计：全是函数、不碰 Android，可以直接单测。
 */
object LibRecordStats {

    /**
     * 热力图分档（预约次数）：0 次 / 1 次 / 2 次 / 3 次 / 4+ 次。
     * 与番茄钟的"秒数分档"同形状（0..4），直接喂 [FocusHeatmap]。
     */
    fun heatTier(count: Int): Int = when {
        count <= 0 -> 0
        count == 1 -> 1
        count == 2 -> 2
        count == 3 -> 3
        else -> 4
    }

    /** 按天聚合预约次数（day 为 null 的丢弃 —— 解析不出的日期不该画进图） */
    fun countsByDay(records: List<LibRecord>): Map<LocalDate, Int> =
        records.mapNotNull { it.day }.groupingBy { it }.eachCount()

    /** 连续有预约的天数（从今天往前数；今天没约就返回 0，与番茄钟口径一致） */
    fun streak(counts: Map<LocalDate, Int>, today: LocalDate): Int {
        var day = today
        var n = 0
        while ((counts[day] ?: 0) > 0) {
            n++
            day = day.minusDays(1)
        }
        return n
    }
}
