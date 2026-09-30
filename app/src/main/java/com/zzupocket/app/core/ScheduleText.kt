package com.zzupocket.app.core

import java.time.LocalDate
import java.util.Locale

/**
 * 课表相关的纯展示逻辑。刻意做成纯函数，方便单元测试 ——
 * 周次压缩是最容易写错、又最难从界面反推的地方。
 */
object ScheduleText {

    val WEEKDAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun weekdayName(weekday: Int): String =
        WEEKDAY_NAMES.getOrElse(weekday - 1) { "第${weekday}天" }

    /**
     * 某一教学周的**周一**。
     *
     * 学期起始日不一定正好是周一（教务给的经常是周中某天），所以先把它对齐到所在周的周一，
     * 再按 `week - 1` 往后推。对齐口径与 `ScheduleRepository.resolveCurrentWeek` 一致（都从周一起算），
     * 这样"第 N 周"和它下面的日期不会差一天。
     *
     * @param startDate 学期起始日；null（没选学期 / 还没同步）时返回 null —— 调用方据此不显示日期
     * @param week 教学周（从 1 起）
     */
    fun mondayOf(startDate: LocalDate?, week: Int): LocalDate? {
        if (startDate == null || week < 1) return null
        val anchor = startDate.minusDays((startDate.dayOfWeek.value - 1).toLong())
        return anchor.plusWeeks((week - 1).toLong())
    }

    /**
     * 星期表头下面的日期，形如 `10-01`。
     *
     * 刻意带月份并补零：只写「1」在跨月那一周会让人以为顺序乱了（…29 30 1 2…），
     * 而「10-01」一眼能看出是新月份（用户要求"用完整的"）。列宽约 51dp，9sp 下约 23dp，放得下。
     */
    fun dayLabel(date: LocalDate): String =
        String.format(Locale.US, "%02d-%02d", date.monthValue, date.dayOfMonth)

    /**
     * 把教学周集合压成人类可读的文本。
     *
     *  - [1,2,3,4,6..16]              → "1-4, 6-16周"
     *  - [1,3,5,7,9,11,13,15]         → "1-15周（单周）"
     *  - [1,3,...,15] + [16]          → "1-15周（单周） + 第16周"   ← 单周课偶尔多上一周
     *  - [2,4,6,8]                    → "2-8周（双周）"
     *  - [1,4,7]                      → "1, 4, 7周"
     */
    fun compressWeeks(weeks: List<Int>): String {
        val w = weeks.filter { it > 0 }.distinct().sorted()
        if (w.isEmpty()) return "—"

        val odds = w.filter { it % 2 == 1 }
        val evens = w.filter { it % 2 == 0 }

        // 先尝试「单/双周 + 零星补周」这种最常见的形态
        for ((major, other, label) in listOf(
            Triple(odds, evens, "单周"),
            Triple(evens, odds, "双周"),
        )) {
            if (major.size >= 3 && isContiguousStep2(major) && other.size <= 3) {
                val main = "${major.first()}-${major.last()}周（$label）"
                if (other.isEmpty()) return main
                val extra = if (other.size == 1) "第${other.first()}周" else rangeText(other) + "周"
                return "$main + $extra"
            }
        }

        return rangeText(w) + "周"
    }

    /** 是否构成步长为 2 的连续序列（如 1,3,5,7） */
    private fun isContiguousStep2(w: List<Int>): Boolean =
        w.zipWithNext().all { (a, b) -> b - a == 2 }

    /** 压缩连续区间，返回不带"周"字的文本，如 "1-4, 6-16" */
    private fun rangeText(w: List<Int>): String {
        val parts = mutableListOf<String>()
        var start = w.first()
        var prev = w.first()
        for (i in 1 until w.size) {
            val cur = w[i]
            if (cur == prev + 1) {
                prev = cur
                continue
            }
            parts += if (start == prev) "$start" else "$start-$prev"
            start = cur
            prev = cur
        }
        parts += if (start == prev) "$start" else "$start-$prev"
        return parts.joinToString(", ")
    }

    /**
     * 同一门课的多个时段，压成一行。
     * 例：(周二,3,4) + (周四,5,6) → "周二 3-4节 · 周四 5-6节"
     * 单节时显示 "周二 3节"。
     */
    fun timeSlots(slots: List<Triple<Int, Int, Int>>): String {
        if (slots.isEmpty()) return "—"
        return slots.distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString(" · ") { (weekday, start, end) ->
                val unitText = if (start == end) "${start}节" else "$start-${end}节"
                "${weekdayName(weekday)} $unitText"
            }
    }

    /** 把 "2026-09-22T08:00:00+08:00" 之类的串取成 "08:00"；失败返回 null */
    fun timeOfDay(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("T(\\d{2}):(\\d{2})").find(raw) ?: return null
        return "${m.groupValues[1]}:${m.groupValues[2]}"
    }

    /**
     * 规整上课地点文本。
     *
     * 学校返回的教室名**自带楼栋前缀**（`南2_206`），而教务给的楼栋名又是 `南2`，
     * 两段拼起来就成了 `南2 南2_206`——看着像重复了两遍。这里统一收拾成 `南2 206`：
     *
     *  - 下划线换成空格（`南2_206` → `南2 206`）
     *  - **合并相邻重复的片段**（`南2 南2 206` → `南2 206`）
     *  - 压缩多余空格、去掉首尾空白
     *
     * 幂等：`南2 206` 再规整一次还是 `南2 206`。所以入库时与显示时都能放心调用
     * ——库里已有的旧串（`南2 南2_206`）会在下次显示时当场被修好，不用重新同步课表。
     */
    fun tidyPlace(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        val tokens = t.replace('_', ' ')
            .split(' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val out = mutableListOf<String>()
        tokens.forEach { token ->
            // 只合并「相邻」重复：`南2 南2 206` 里第二个「南2」要去掉，
            // 而正常的 `南2 206`（楼栋 + 房号）不受影响
            if (out.lastOrNull() != token) out += token
        }
        return out.joinToString(" ").ifBlank { null }
    }
}
