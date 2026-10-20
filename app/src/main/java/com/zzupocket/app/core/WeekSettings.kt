package com.zzupocket.app.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 教学周的「每周起始日」。
 *
 * 国内校历绝大多数是**周一起算**，但部分学校/课表工具按周日算，所以做成可选。
 * 它只影响两件事：一周从哪天开始（周次边界）、某周某星期几对应哪一天；
 * 课表网格的列顺序仍是「一…日」（数据里 weekday 1..7 就是 一..日）。
 */
enum class WeekStartDay(val isoDay: Int, val label: String) {
    MONDAY(1, "周一"),
    SUNDAY(7, "周日"),
}

/** 周次参数是从哪来的 —— 界面要据此标注「来自教务校历 / 用户设置 / 自动推断」 */
enum class WeekSource {
    /** 本科教务给的学期起始日 */
    CALENDAR,

    /** 用户手动设置或校正过 */
    USER,

    /** 从课表数据推断出来的默认值（**未经确认**，不可信） */
    INFERRED,
}

/**
 * 一个学期的周次参数。
 *
 * ⚠️ 为什么要有这份东西：研究生系统**不返回当前第几周**，0.31.5/0.31.6 只能拿
 * 「本周课表」反推整学期的周次，结果把第 7 周算成了第 8 周（甚至第 10 周）。
 * 现在改成：周次参数是**显式的一等设置**（开学日期 / 当前周次 / 总周次 / 每周起始日），
 * 登录时自动填一次，填错了用户能改，**绝不把推断结果当事实**。
 *
 * [confirmed] = false 时，界面必须显示「待确认」，并且不能拿它当唯一真相。
 */
data class WeekSettings(
    /** 第 1 周的起始日（按 [startDay] 取那天；周一起算时就是第 1 周周一） */
    val weekOneStart: LocalDate? = null,
    val startDay: WeekStartDay = WeekStartDay.MONDAY,
    /** 本学期总周次；null = 由课表数据里的最大周决定 */
    val totalWeeks: Int? = null,
    val confirmed: Boolean = false,
    val source: WeekSource = WeekSource.INFERRED,
)

/**
 * 教学周的纯换算。
 *
 * 与 [com.zzupocket.app.data.gms.GmsWeekAlign] 的分工：
 * - [GmsWeekAlign] 负责"从数据里**猜**当前周"（只在没有设置时兜底，且结果不可信）；
 * - 这里负责"有了基准之后的一切换算"（周次 ↔ 日期），这是唯一可信的路径。
 */
object WeekMath {

    /** 今天所在这一周的第一天（按 [startDay]） */
    fun startOfWeek(date: LocalDate, startDay: WeekStartDay): LocalDate =
        if (startDay == WeekStartDay.MONDAY) {
            date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        } else {
            date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        }

    /**
     * 由「今天是第几周」反推第 1 周的起始日。
     *
     * 用户把「当前周次」改成 N 时，就是靠它反推开学日期（改周次 = 改基准）。
     */
    fun weekOneStart(today: LocalDate, currentWeek: Int, startDay: WeekStartDay): LocalDate =
        startOfWeek(today, startDay).minusWeeks((currentWeek - 1).coerceAtLeast(0).toLong())

    /**
     * 今天属于第几周（1 起）。开学前会返回 0 或负数，调用方自己钳进合法区间。
     */
    fun weekIndexAt(weekOneStart: LocalDate, today: LocalDate): Int {
        val days = ChronoUnit.DAYS.between(weekOneStart, today)
        return Math.floorDiv(days, 7L).toInt() + 1
    }

    /** 某周某星期几的日期（[weekday] 1=周一 … 7=周日） */
    fun dateOf(
        weekOneStart: LocalDate,
        week: Int,
        weekday: Int,
        startDay: WeekStartDay,
    ): LocalDate = weekOneStart
        .plusWeeks((week - 1).toLong())
        .plusDays(offsetOf(weekday, startDay).toLong())

    /** 星期几在本周内的偏移天数（周一..周日，按 [startDay] 起算） */
    fun offsetOf(weekday: Int, startDay: WeekStartDay): Int {
        val w = weekday.coerceIn(1, 7)
        return Math.floorMod(w - startDay.isoDay, 7)
    }
}
