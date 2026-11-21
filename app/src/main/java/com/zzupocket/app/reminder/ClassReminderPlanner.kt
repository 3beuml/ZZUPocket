package com.zzupocket.app.reminder

import com.zzupocket.app.ui.today.TimelineEntry
import com.zzupocket.app.ui.today.TimelineKind
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「下一节课」的提醒计划结果。
 *
 * 纯数据，不含 Android 依赖，方便单测。时间统一用 epoch 毫秒（本地时区换算），
 * 闹钟直接用它，不在触发时再算一次 —— 避免跨天/跨周时算到别的课上去。
 */
data class ClassReminderPlan(
    /** 去重键：课程唯一键 + 开始时间戳，同一节课反复排程只会提醒一次 */
    val key: String,
    val title: String,
    /** 地点时间副标题，如 "08:00 · 南2 206" */
    val subtitle: String,
    /** 课程开始时刻（epoch ms） */
    val startAt: Long,
    /** 闹钟触发时刻 = startAt - leadMinutes（epoch ms） */
    val notifyAt: Long,
    /** 未解决冲突的课 */
    val isConflict: Boolean,
)

/** 把「某天第几分钟」换算成 epoch 毫秒（本地时区） */
internal fun epochOf(date: LocalDate, minuteOfDay: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
    date.atStartOfDay(zone).plusMinutes(minuteOfDay.toLong()).toInstant().toEpochMilli()

/**
 * 上课提醒的纯计算层：从今天起向后扫描若干天，找第一节"还没开始"的课。
 *
 * 为什么独立出来：跨天、跨周末、同一天多节课、冲突占位、提前量边界这些情况
 * 全部可以在 JVM 单测里覆盖，不依赖 AlarmManager / 通知 / Activity。
 *
 * 口径与首页时间轴一致：调用方传入每一天的 [TimelineEntry]（已按 displayBlocks
 * 处理过冲突与按周选择），这里只做「挑下一节 + 算触发时刻」。
 */
object ClassReminderPlanner {

    /** 最多向后找几天：覆盖跨周末（周五晚上找下周一）与长假，再多没有意义 */
    const val DEFAULT_SCAN_DAYS = 14

    /**
     * @param today 今天
     * @param nowMillis 当前时刻（epoch ms）
     * @param leadMinutes 提前分钟数（1..60）
     * @param scanDays 向后扫描天数
     * @param entriesOn 取某天的课程时间轴；返回空表表示那天没课
     * @param excludeKey 要排除的去重键（刚提醒过的那节，避免重复通知同一节课）
     * @return 第一节开始时刻严格晚于 nowMillis 的课；找不到返回 null
     */
    fun planNext(
        today: LocalDate,
        nowMillis: Long,
        leadMinutes: Int,
        scanDays: Int = DEFAULT_SCAN_DAYS,
        entriesOn: (LocalDate) -> List<TimelineEntry>,
        excludeKey: String? = null,
    ): ClassReminderPlan? {
        val zone = ZoneId.systemDefault()
        val leadMs = leadMinutes.coerceIn(1, 60) * 60_000L

        for (offset in 0 until scanDays) {
            val date = today.plusDays(offset.toLong())
            val candidates = entriesOn(date)
                .filter { it.kind == TimelineKind.COURSE && it.endMin > it.startMin }
                .sortedBy { it.startMin }

            for (course in candidates) {
                val startAt = epochOf(date, course.startMin, zone)
                // 只找还没开始的课。正在上的课（已开始未结束）跳过去找下一节：
                // "上课前提醒"对已经坐在教室的人没有意义。
                if (startAt <= nowMillis) continue

                // 冲突占位课按课程名集合做唯一键，避免与同名普通课混为一谈；
                // 普通课用 日期+开始分钟+课名，保证重新排程时仍认得是同一节。
                val idPart = if (course.hasUnresolvedConflict) {
                    "conflict:" + course.conflictCourseNames.sorted().joinToString("/")
                } else {
                    course.title
                }
                val key = "$startAt|$idPart"
                if (key == excludeKey) continue

                return ClassReminderPlan(
                    key = key,
                    title = course.title,
                    subtitle = course.subtitle,
                    startAt = startAt,
                    notifyAt = startAt - leadMs,
                    isConflict = course.hasUnresolvedConflict,
                )
            }
        }
        return null
    }
}
