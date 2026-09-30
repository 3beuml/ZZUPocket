package com.zzupocket.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 用于统计的专注记录（与数据库实体解耦，方便单测） */
data class FocusRecord(
    val id: Long,
    val tagId: Long?,
    val tagName: String,
    val phase: Phase,
    val startedAt: Long,
    val endedAt: Long,
    val actualSeconds: Int,
    val completed: Boolean,
    val contextRef: String = "",
    val note: String = "",
)

/**
 * 一组时长 + 番茄数。
 *
 * 两个口径刻意分开：
 *  - [seconds] 累加**所有**专注段（包括中途放弃的）—— 用户确实坐在那里学了那么久；
 *  - [sessions] 只数**完整走完**的番茄 —— 那才是"打卡"。
 */
data class FocusTotals(val seconds: Int = 0, val sessions: Int = 0) {

    val minutes: Int get() = seconds / 60

    operator fun plus(other: FocusTotals) =
        FocusTotals(seconds + other.seconds, sessions + other.sessions)

    companion object {
        val ZERO = FocusTotals()
    }
}

data class TagTotal(
    val tagId: Long?,
    val tagName: String,
    val totals: FocusTotals,
)

data class FocusSummary(
    val today: FocusTotals = FocusTotals.ZERO,
    val week: FocusTotals = FocusTotals.ZERO,
    val month: FocusTotals = FocusTotals.ZERO,
    val year: FocusTotals = FocusTotals.ZERO,
    val allTime: FocusTotals = FocusTotals.ZERO,
    val perTag: List<TagTotal> = emptyList(),
)

/**
 * 番茄钟统计。全是纯函数，不碰数据库也不碰 Android —— 可以直接单测。
 */
object FocusStats {

    /**
     * 一天的起点是**凌晨 4 点**。
     *
     * 学到凌晨 1 点属于"前一天晚上"，不该算进第二天。
     * 4 点这个界和热力图、场次序号是同一个口径。
     */
    const val DAY_START_HOUR = 4

    fun zone(): ZoneId = ZoneId.systemDefault()

    /** 某一时刻属于哪一天（按 4 点分界） */
    fun dayKey(epochMs: Long, zone: ZoneId = zone()): LocalDate {
        val zdt = Instant.ofEpochMilli(epochMs).atZone(zone)
        return if (zdt.hour < DAY_START_HOUR) zdt.toLocalDate().minusDays(1) else zdt.toLocalDate()
    }

    /** 某一天（按 4 点分界）的起始毫秒 */
    fun dayStart(day: LocalDate, zone: ZoneId = zone()): Long =
        day.atTime(DAY_START_HOUR, 0).atZone(zone).toInstant().toEpochMilli()

    fun dayEnd(day: LocalDate, zone: ZoneId = zone()): Long = dayStart(day.plusDays(1), zone)

    /** 周一为一周的开始 */
    fun weekStart(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun monthStart(day: LocalDate): LocalDate = day.withDayOfMonth(1)

    fun yearStart(day: LocalDate): LocalDate = day.withDayOfYear(1)

    fun inRange(records: List<FocusRecord>, fromMs: Long, toMs: Long): List<FocusRecord> =
        records.filter { it.startedAt >= fromMs && it.startedAt < toMs }

    /** 专注段才算；休息不计入任何统计 */
    fun focusRecords(records: List<FocusRecord>): List<FocusRecord> =
        records.filter { it.phase == Phase.FOCUS }

    fun totals(records: List<FocusRecord>): FocusTotals {
        var seconds = 0
        var sessions = 0
        focusRecords(records).forEach {
            seconds += it.actualSeconds.coerceAtLeast(0)
            if (it.completed) sessions++
        }
        return FocusTotals(seconds, sessions)
    }

    fun totalsBetween(records: List<FocusRecord>, fromMs: Long, toMs: Long): FocusTotals =
        totals(inRange(records, fromMs, toMs))

    /**
     * 按标签汇总，时长从多到少；空标签归到"未分类"。
     *
     * 刻意**按名字聚合而不是按 tagId**：从课表直接开番茄钟时只知道课名、
     * 还没建标签，这种记录也会落到某个名字下；按 id 聚合会把它们
     * 全塞进"未分类"，然后被第一条有名字的记录带跑。
     */
    fun byTag(records: List<FocusRecord>): List<TagTotal> =
        focusRecords(records)
            .groupBy { it.tagName.trim().ifEmpty { UNTAGGED } }
            .map { (name, group) ->
                TagTotal(
                    tagId = group.firstNotNullOfOrNull { it.tagId },
                    tagName = name,
                    totals = totals(group),
                )
            }
            .filter { it.totals.seconds > 0 || it.totals.sessions > 0 }
            .sortedWith(compareByDescending<TagTotal> { it.totals.seconds }.thenBy { it.tagName })

    const val UNTAGGED = "未分类"

    /** 每天的专注秒数（key 是按 4 点分界的那一天） */
    fun secondsByDay(
        records: List<FocusRecord>,
        zone: ZoneId = zone(),
    ): Map<LocalDate, Int> {
        val out = HashMap<LocalDate, Int>()
        focusRecords(records).forEach { r ->
            val key = dayKey(r.startedAt, zone)
            out[key] = (out[key] ?: 0) + r.actualSeconds.coerceAtLeast(0)
        }
        return out
    }

    /**
     * 热力图强度分档：0 / 不到 30 分钟 / 30–60 / 60–120 / 2 小时以上。
     * 返回 0..4，0 表示当天没有记录。
     */
    fun heatTier(seconds: Int): Int = when {
        seconds <= 0 -> 0
        seconds < 30 * 60 -> 1
        seconds < 60 * 60 -> 2
        seconds < 120 * 60 -> 3
        else -> 4
    }

    fun summarize(
        records: List<FocusRecord>,
        now: Long,
        zone: ZoneId = zone(),
    ): FocusSummary {
        val today = dayKey(now, zone)
        return FocusSummary(
            today = totalsBetween(records, dayStart(today, zone), dayEnd(today, zone)),
            week = totalsBetween(records, dayStart(weekStart(today), zone), dayEnd(today, zone)),
            month = totalsBetween(records, dayStart(monthStart(today), zone), dayEnd(today, zone)),
            year = totalsBetween(records, dayStart(yearStart(today), zone), dayEnd(today, zone)),
            allTime = totals(records),
            perTag = byTag(records),
        )
    }

    /** 人类可读的时长："1 小时 20 分" / "25 分钟" / "—" */
    fun humanDuration(seconds: Int): String {
        if (seconds <= 0) return "—"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 && m > 0 -> "$h 小时 $m 分"
            h > 0 -> "$h 小时"
            m > 0 -> "$m 分钟"
            else -> "不到 1 分钟"
        }
    }
}
