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
     *  - [1..9]（5..9 等短连续区间同）  → "1-9周"
     *  - [1,3,5,7,9,11,13,15]         → "1-15周（单周）"
     *  - [1,3,...,15] + [16]          → "1-15周（单周） + 第16周"   ← 单周课偶尔多上一周
     *  - [2,4,6,8]                    → "2-8周（双周）"
     *  - [1,4,7]                      → "1, 4, 7周"
     */
    fun compressWeeks(weeks: List<Int>): String {
        val w = weeks.filter { it > 0 }.distinct().sorted()
        if (w.isEmpty()) return "—"

        // 整段连续（如 5..9）直接按区间输出 —— 否则短连续区间会被下面的单双周规则
        // 误判成「单周 + 零星补周」：5-9 周 → "5-9周（单周） + 6, 8周"（issue #5 实测）
        if (w.zipWithNext().all { (a, b) -> b - a == 1 }) return rangeText(w) + "周"

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
     * 课名太长时建议的字号缩减量（sp）。
     *
     * 课表格子窄（列宽 ~5 个汉字），研究生课名常是班级名（如「专业英语人工智能班」），
     * 固定行数放不下就省略成"…"（issue #6）。与其放开行数把格子撑爆，不如**长名自动小一号**：
     * 行数不变、每行能放的字符更多。中文按 1 字符计、其它按 0.5 计（英文名天然更窄）。
     */
    fun fontAdjust(text: String): Int {
        var w = 0f
        for (ch in text) w += if (ch.code > 0x2E7F) 1f else 0.5f
        return when {
            w >= 18f -> -2
            w >= 13f -> -1
            else -> 0
        }
    }

    /**
     * 规整上课地点文本。
     *
     * 学校返回的教室名**自带楼栋前缀**（`南2_206`、`中3302`、`物理馆X101`），
     * 而教务给的楼栋名又是 `南2`、`中3`、`物理馆`，两段拼起来就重复了一遍。这里统一收拾：
     *
     *  - 下划线换成空格（`南2_206` → `南2 206`）
     *  - **合并相邻重复的片段**（`南2 南2 206` → `南2 206`）
     *  - **剥掉被带进教室名的楼名前缀**（`中3 中3302` → `中3 3302`、
     *    `物理馆 物理馆X101` → `物理馆 X101`；楼名数字结尾也剥，靠「教室号无前导零」
     *    区分真前缀与房间号，见函数内注释）
     *  - **剥掉房号自带的楼名首字缩写**（`物理馆 物505` → `物理馆 505`、`材料馆 材317`
     *    → `材料馆 317`——教务的房间号有时写成「楼名首字 + 纯数字」，拼接后像重复了半个楼名）
     *  - 压缩多余空格、去掉首尾空白；幂等，入库与显示时可重复调用
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
        tokens.forEachIndexed { idx, token ->
            val last = out.lastOrNull()
            when {
                // 只合并「相邻」重复：`南2 南2 206` 里第二个「南2」要去掉，
                // 而正常的 `南2 206`（楼栋 + 房号）不受影响
                last == token -> Unit
                // 房号自带楼名首字缩写、且和房号之间有空格：`物理馆 物 505` 的中间那只「物」
                // （教务给它自己写了「楼名简写 + 空格 + 房号」）。后面紧跟纯数字房号，直接丢弃这只字
                last != null && last.length >= 2 && token.length == 1 &&
                    token == last.first().toString() &&
                    tokens.getOrNull(idx + 1)?.all(Char::isDigit) == true -> Unit
                // 前缀剥离：教室名 = 楼名 + 房号黏在一起（`中3 中3302`、`物理馆 物理馆X101`）。
                // 楼名以数字结尾也剥（中1/中3/南2 这类）——真实教室号没有前导零，
                // 剥出来以 0 开头的纯数字（`南2 南209` 剥出 `09`）说明是房间号本身，保留原词。
                last != null && token.length > last.length && token.startsWith(last) -> {
                    val rest = token.substring(last.length)
                    out += if (rest.first() == '0' && rest.all(Char::isDigit)) token else rest
                }
                // 房号自带楼名首字缩写、黏成一个词：`物理馆 物505`、`材料馆 材317`。
                // 整体前缀（上一条）不成立时才走到这里，所以不会把 `中3302` 这类正常前缀剥坏；
                // 余部必须是「不以 0 开头的纯数字」（`南2 南209` 是被整体前缀规则拦截的，轮不到这里）。
                last != null && last.length >= 2 && !token.startsWith(last) && token.length > 1 &&
                    token.first() == last.first() &&
                    token.drop(1).all(Char::isDigit) && !token.drop(1).startsWith("0") -> {
                    out += token.drop(1)
                }
                else -> out += token
            }
        }
        return out.joinToString(" ").ifBlank { null }
    }

    /**
     * 超长地点的「保头保尾」缩写：楼名/单位在前、房号在后，中间用省略号。
     * 例：`信息工程学院网络实验室302` → `信息工程学院…302`。
     *
     * 普通截断从尾部切，恰好把最重要的房号切掉（issue #6 的「信息工程学院 …」）；
     * 这里优先保留开头的楼名/单位与结尾的房号（尾部连续的数字/字母段，最多 [tailKeep] 个），
     * 中间按预算截断。不超预算则原样返回。
     */
    fun abbreviatePlace(text: String?, maxWeighted: Int = 14, tailKeep: Int = 5): String? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        var weight = 0f
        for (ch in t) weight += if (ch.code > 0x2E7F) 1f else 0.5f
        if (weight <= maxWeighted) return t
        // 尾部房号段：连续的数字/字母（X101、302、4-206 中的字母数字部分）
        val tail = Regex("[0-9A-Za-z-]{1,${tailKeep}}$").find(t)?.value.orEmpty()
        val tailW = tail.sumOf { if (it.code > 0x2E7F) 1.0 else 0.5 }.toFloat()
        val headBudget = ((maxWeighted - tailW - 1f).coerceAtLeast(2f)) // 1f 留给省略号
        val head = StringBuilder()
        var used = 0f
        for (ch in t) {
            val w = if (ch.code > 0x2E7F) 1f else 0.5f
            if (used + w > headBudget) break
            head.append(ch)
            used += w
        }
        return head.toString().trim() + "…" + tail
    }
}
