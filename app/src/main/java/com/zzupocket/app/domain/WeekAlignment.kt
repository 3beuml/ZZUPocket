package com.zzupocket.app.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 「课表该显示第几教学周」的判断。
 *
 * 抽成纯函数，是因为这里有两个很容易搞反、又在真机上真出过问题的点：
 *
 *  1. **同一天内**用户手动翻过的周不能被冲掉 —— 翻周是用户自己的选择，
 *     回到课表页就把它拽回本周会很难用；
 *  2. **隔了一天**（尤其是跨周）就必须重新对准当前周 —— 否则周一早上打开 App，
 *     看到的还是上周的课表。真机实测：今天页显示「第 4 周」、课表页却停在第 3 周
 *     （9/21 - 9/27），而课表页里的 weekIndex 只在"首次载入 / 手动刷新 / 换学期"
 *     时才会重算，`onResume` 什么也不做 —— 所以它一直停在旧周。
 */
object WeekAlignment {

    /**
     * 今天该按第几周显示。
     *
     * @param currentWeek 今天真正属于第几周；学期外（寒暑假/开学前）为 null
     * @param firstWeek 学期的第一周，[currentWeek] 为 null 时作为兜底
     */
    fun currentWeek(currentWeek: Int?, firstWeek: Int?): Int = currentWeek ?: firstWeek ?: 1

    /**
     * 要不要把显示周次重新对准当前周。
     *
     * @param resolvedOn 现在显示的这一周是按**哪一天**算出来的；null = 还没算过
     * @param today 今天
     */
    fun needsRealign(resolvedOn: LocalDate?, today: LocalDate): Boolean = resolvedOn != today

    /**
     * 重新对准之后该显示的周次（[needsRealign] 为 false 时原样返回 [storedWeek]）。
     *
     * 把"该不该重算"和"重算成什么"放在一起，调用方就不可能只做一半 ——
     * 这正是课表页当初的毛病：状态里只存了周次、没存它是按哪天算的，于是无从判断该不该重算。
     */
    fun realigned(
        resolvedOn: LocalDate?,
        today: LocalDate,
        storedWeek: Int,
        currentWeek: Int?,
        firstWeek: Int?,
    ): Int = if (needsRealign(resolvedOn, today)) currentWeek(currentWeek, firstWeek) else storedWeek

    /**
     * 某个**日期**属于第几教学周；落在学期周次之外（寒暑假、开学前）返回 null。
     *
     * 用途有两个，都是真机上会碰到的事：
     *  · 课表页判断"今天该显示第几周"；
     *  · 学校给的记录**只有日期、没有教学周号**时（临时补课就常这样）由日期反推，
     *    否则那条补课永远进不了周网格，只能在"今天"里按日期看到。
     *
     * 对齐到周首再算，这样学期起始日是周中某天也不会整体错一周。
     */
    fun indexOf(
        startDate: LocalDate?,
        weekStartOnSunday: Boolean,
        weekIndices: List<Int>,
        date: LocalDate,
    ): Int? {
        if (startDate == null) return null
        val days = ChronoUnit.DAYS.between(
            alignToWeekStart(startDate, weekStartOnSunday),
            alignToWeekStart(date, weekStartOnSunday),
        )
        if (days < 0) return null
        val idx = (days / 7).toInt() + 1
        return if (idx in weekIndices) idx else null
    }

    private fun alignToWeekStart(date: LocalDate, sundayStart: Boolean): LocalDate {
        val target = if (sundayStart) DayOfWeek.SUNDAY else DayOfWeek.MONDAY
        val diff = ((date.dayOfWeek.value - target.value) + 7) % 7
        return date.minusDays(diff.toLong())
    }
}
