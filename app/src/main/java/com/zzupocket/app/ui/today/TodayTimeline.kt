package com.zzupocket.app.ui.today

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** 时间轴上一段的类型 */
enum class TimelineKind { COURSE, FOCUS, GAP }

/**
 * 时间轴上的一段。时间统一换算成"当天第几分钟"（0..1440），便于排序与渲染。
 *
 * 这里是**纯数据 + 纯计算**，不依赖 Android/Room，方便单测（见 TodayTimelineTest）。
 */
data class TimelineEntry(
    val kind: TimelineKind,
    /** 当天第几分钟 */
    val startMin: Int,
    val endMin: Int,
    val title: String = "",
    val subtitle: String = "",
    val colorArgb: Long? = null,
    /** COURSE：今天已经在这门课上专注的秒数 */
    val focusSeconds: Int = 0,
    /** GAP：这段空档里已经专注掉的秒数 */
    val usedSeconds: Int = 0,
) {
    val minutes: Int get() = (endMin - startMin).coerceAtLeast(0)

    /** 某一分钟是否落在这段里（左闭右开） */
    fun covers(minute: Int): Boolean = minute >= startMin && minute < endMin
}

/**
 * 「今天」页时间轴的构建规则。
 *
 * 一根轴回答一个问题：**我这一天长什么样**。轴上只放三类东西——
 * 上课（来自课表）、空档（课与课之间剩下的时间）、已完成的专注（来自番茄钟）。
 * 经典番茄钟 App 没有课表，也就没有"空档"这个概念，这是校园场景独有的东西。
 */
object TodayTimeline {

    /** 短于这个长度的空档不值得单独占一行 */
    const val MIN_GAP_MIN = 15

    /**
     * 把课程与专注记录合成一根时间轴。
     *
     * - **空档只画在课与课之间**。早八前、末节课后不画空档：
     *   那两段时间由卡片顶部的提示负责——"下一节 10:00《…》· 40 分钟后，够一个番茄"、
     *   "今天的课已上完"。否则一天没课时会画出一条 14 小时的"空档"，纯噪音。
     * - 一节课都没有时不产生空档（这时由界面提示"今天没课"）。
     */
    fun build(
        courses: List<TimelineEntry>,
        focuses: List<TimelineEntry>,
        minGapMin: Int = MIN_GAP_MIN,
    ): List<TimelineEntry> {
        val cs = courses.filter { it.endMin > it.startMin }.sortedBy { it.startMin }
        val fs = focuses.filter { it.endMin > it.startMin }.sortedBy { it.startMin }
        if (cs.isEmpty() && fs.isEmpty()) return emptyList()

        // 相邻两节课之间剩下的时间就是一个可用的专注窗口
        val gaps = ArrayList<TimelineEntry>()
        cs.zipWithNext().forEach { (prev, next) ->
            if (next.startMin - prev.endMin >= minGapMin) {
                gaps += gapEntry(prev.endMin, next.startMin, fs)
            }
        }

        return (cs + gaps + fs).sortedWith(
            compareBy({ it.startMin }, { kindRank(it.kind) }),
        )
    }

    private fun gapEntry(startMin: Int, endMin: Int, focuses: List<TimelineEntry>): TimelineEntry =
        TimelineEntry(
            kind = TimelineKind.GAP,
            startMin = startMin,
            endMin = endMin,
            title = "空档",
            // 只统计"开始于这段空档内"的专注，避免跨段重复计数
            usedSeconds = focuses.filter { it.startMin in startMin until endMin }
                .sumOf { it.minutes * 60 },
        )

    private fun kindRank(kind: TimelineKind): Int = when (kind) {
        TimelineKind.COURSE -> 0
        TimelineKind.GAP -> 1
        TimelineKind.FOCUS -> 2
    }

    /** 480 → "08:00" */
    fun clock(minute: Int): String {
        val m = ((minute % 1440) + 1440) % 1440
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    /** 80 → "1 小时 20 分"；45 → "45 分钟" */
    fun duration(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        val h = m / 60
        val r = m % 60
        return when {
            h == 0 -> "$r 分钟"
            r == 0 -> "$h 小时"
            else -> "$h 小时 $r 分"
        }
    }

    /** "08:00" / "8:00" → 480；解析不了返回 null */
    fun minuteOfDay(text: String?): Int? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        val parts = t.split(':')
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].take(2).toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /** 时间戳 → 当天第几分钟（按本地时区） */
    fun minuteOfEpoch(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
        return t.hour * 60 + t.minute
    }
}
