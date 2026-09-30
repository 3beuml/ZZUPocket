package com.zzupocket.app.domain

import java.time.LocalDate

/** 热力图的一列（一周 7 天）。null = 还没到的日子，占位但不画内容 */
data class HeatmapWeek(val days: List<LocalDate?>) {
    val firstDay: LocalDate? get() = days.firstOrNull { it != null }
}

/**
 * 打卡热力图的网格计算。
 *
 * 借鉴 goodtime 的排布：**一列一周、周一在上**，横着滑 53 周。
 * 未来的日子留 null 占位而不是少画几个格子 —— 少了格子最后一列会矮一截，
 * 看起来像渲染出错。
 */
object FocusHeatmap {

    const val DEFAULT_WEEKS = 53

    fun build(today: LocalDate, weeks: Int = DEFAULT_WEEKS): List<HeatmapWeek> {
        if (weeks <= 0) return emptyList()
        val lastWeekStart = FocusStats.weekStart(today)
        val firstWeekStart = lastWeekStart.minusWeeks((weeks - 1).toLong())
        return (0 until weeks).map { w ->
            val weekStart = firstWeekStart.plusWeeks(w.toLong())
            HeatmapWeek(
                days = (0..6).map { offset ->
                    val day = weekStart.plusDays(offset.toLong())
                    if (day.isAfter(today)) null else day
                },
            )
        }
    }

    /** 这一列要不要标月份：这一周里出现了某个月的 1 号（一个月只会命中一次） */
    fun monthLabel(week: HeatmapWeek): String? {
        val day = week.days.filterNotNull().firstOrNull { it.dayOfMonth == 1 } ?: return null
        return "${day.monthValue}月"
    }

    /**
     * 强度叠色系数（0..1）。
     * 0 档不叠色，只留底色 —— 所以"这天没学"和"这天学了一点"一眼能分开。
     */
    fun intensity(tier: Int): Float = when {
        tier <= 0 -> 0f
        tier == 1 -> 0.25f
        tier == 2 -> 0.45f
        tier == 3 -> 0.7f
        else -> 1f
    }

    /** 连续打卡天数（从今天往前数，今天没学就返回 0） */
    fun streak(secondsByDay: Map<LocalDate, Int>, today: LocalDate): Int {
        var day = today
        var n = 0
        while ((secondsByDay[day] ?: 0) > 0) {
            n++
            day = day.minusDays(1)
        }
        return n
    }
}
