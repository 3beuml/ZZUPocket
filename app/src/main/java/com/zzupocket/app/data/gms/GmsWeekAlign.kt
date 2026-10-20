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
 *
 * ## ⚠️ 反推只是"第一次"用的，之后靠锚点
 * 反推是启发式，课程跨周重复时**多周同样说得通**（实测把第 7 周算成第 10 周）。
 * 所以现在只在**确有唯一解**时（[guess] 的 [Guess.unique]）才把它写成锚点
 * （第 1 周周一，存 `UiPrefs.gmsWeekOneEpochDay`）；之后一律用 [weekIndexAt]
 * 按日期推算，不再每天重新猜。用户也可以手动校正一周，同样写成锚点。
 */
object GmsWeekAlign {

    /**
     * 一次反推的结果。
     *
     * [unique] 为 false 表示"有多周同样说得通"（课程每周都一样时必然如此）。
     * 这时**不能**拿 [week] 当锚点 —— 0.31.5 正是在这一点上把第 7 周算成了第 10 周：
     * 老实现只数"命中几条"，课程跨周重复时靠后的一周命中数可能更高，整张表就整体错位。
     */
    data class Guess(val week: Int?, val unique: Boolean) {
        /** 只有唯一解才配被写进锚点 */
        fun anchoredWeekOrNull(): Int? = week?.takeIf { unique }
    }

    /** 一节课在匹配时用的键 */
    fun key(weekday: Int, period: Int, courseName: String): String =
        "$weekday|$period|${courseName.trim()}"

    /**
     * 反推当前教学周，**并判断是否唯一**。
     *
     * 判据分两档：
     * 1. 先找"整周课表完全一致"的周 —— 最硬的证据，且必须只有一周完全一致；
     * 2. 没有完全一致的（本周有调课/停课）才退一步，按"对称差最小"选，
     *    且必须严格优于第二名，否则视为无法判定。
     *
     * 一次都不命中的周直接排除（保持老实现"全不命中返回 null"的语义）。
     */
    fun guess(termEntries: List<GmsClient.WeekEntry>, currentWeekEntries: List<GradLesson>): Guess {
        if (termEntries.isEmpty() || currentWeekEntries.isEmpty()) return Guess(null, false)
        val today = currentWeekEntries.map { key(it.weekday, it.period, it.courseName) }.toSet()
        if (today.isEmpty()) return Guess(null, false)
        val byWeek: Map<Int, Set<String>> = termEntries
            .groupBy { it.weekIndex }
            .mapValues { (_, list) -> list.map { key(it.weekday, it.period, it.courseName) }.toSet() }

        val exact = byWeek.filterValues { it == today }.keys
        if (exact.size == 1) return Guess(exact.first(), unique = true)
        if (exact.size > 1) return Guess(null, unique = false)   // 每周课一模一样，无从区分

        val scored = byWeek.mapNotNull { (week, keys) ->
            val inter = keys.count { it in today }
            if (inter == 0) return@mapNotNull null
            Triple(week, (keys - today).size + (today - keys).size, inter)
        }.sortedWith(compareBy({ it.second }, { -it.third }, { it.first }))
        val best = scored.firstOrNull() ?: return Guess(null, false)
        val second = scored.getOrNull(1)
        return Guess(best.first, unique = second == null || second.second > best.second)
    }

    /**
     * 由「第 1 周周一」+ 今天推算当前教学周（1 起）。
     *
     * 锚点一旦确定就走这条路，不再依赖反推 —— 这是"周次不漂移"的关键。
     * 早于开学时会返回 0 或负数，调用方自己钳进合法区间。
     */
    fun weekIndexAt(weekOneMonday: LocalDate, today: LocalDate): Int {
        val days = java.time.temporal.ChronoUnit.DAYS.between(weekOneMonday, today)
        return Math.floorDiv(days, 7L).toInt() + 1
    }

    /**
     * 反推当前教学周。命中率最高且至少命中 1 节的那一周胜出；全不命中返回 null
     * （调用方据此退回"本周视图"或提示用户）。
     *
     * ⚠️ 只返回周次、**不保证唯一**（历史签名，多份单测依赖它）。
     * 要写锚点请用 [guess] 的 [Guess.anchoredWeekOrNull]。
     */
    fun currentWeekIndex(
        termEntries: List<GmsClient.WeekEntry>,
        currentWeekEntries: List<GradLesson>,
    ): Int? {
        guess(termEntries, currentWeekEntries).week?.let { return it }
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
