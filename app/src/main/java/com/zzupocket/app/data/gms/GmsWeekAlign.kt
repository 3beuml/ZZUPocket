package com.zzupocket.app.data.gms

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 研究生课表的**教学周对齐**。
 *
 * ## 问题
 * 研究生教务（gmsstu）只给「周次区间 + 星期 + 节次」，**不给日期、也不给学期起止日期**
 * （见 `docs/gms-schedule-api.md`）。而我们的课表页是"按教学周排的网格"，必须知道
 * "第 1 周是哪天"才能把课排到日历上。
 *
 * ## 办法：用服务端自己的「本周课表」反推
 * 它有一个接口返回**当前这一周**的课（`bzkb_load`，服务端按今天算好）。把"本周的课"
 * 与"整学期每一周的课"比对，命中率最高的那一周就是当前周 —— 于是：
 *
 * ```
 * 第 1 周的周一 = 本周的周一 − (当前周 − 1) × 7 天
 * ```
 *
 * 这样既不用让用户手填开学日期，也不需要去求本科教务要校历（研究生账号被它拒绝）。
 *
 * 匹配键取 `星期|节次|课程名`：课程名是唯一能跨两次请求对上的稳定字段（课程号在
 * 本周接口里不一定给）。
 */
object GmsWeekAlign {

    /** 一节课在匹配时用的键 */
    fun key(weekday: Int, period: Int, courseName: String): String =
        "$weekday|$period|${courseName.trim()}"

    /**
     * 反推当前教学周。命中率最高且至少命中 1 节的那一周胜出；全不命中返回 null
     * （调用方据此退回"本周视图"或提示用户）。
     *
     * @param termEntries 整学期课表（已按 [GmsClient.expandWeeks] 展开成每周一条）
     * @param currentWeekEntries 服务端返回的"本周"课表（已压平成 [GradLesson]）
     */
    fun currentWeekIndex(
        termEntries: List<GmsClient.WeekEntry>,
        currentWeekEntries: List<GradLesson>,
    ): Int? {
        if (termEntries.isEmpty() || currentWeekEntries.isEmpty()) return null
        val todayKeys = currentWeekEntries
            .map { key(it.weekday, it.period, it.courseName) }
            .toSet()
        if (todayKeys.isEmpty()) return null
        return termEntries
            .groupBy { it.weekIndex }
            .mapValues { (_, list) -> list.map { key(it.weekday, it.period, it.courseName) }.toSet() }
            .map { (week, keys) -> week to keys.count { it in todayKeys } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * 由"今天是第几周"反推第 1 周的周一。
     *
     * @param today 今天
     * @param currentWeek 当前教学周（1 起）
     */
    fun weekOneMonday(today: LocalDate, currentWeek: Int): LocalDate {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return monday.minusWeeks((currentWeek - 1).coerceAtLeast(0).toLong())
    }
}
