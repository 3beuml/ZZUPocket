package com.zzupocket.app.ui.widget

import com.zzupocket.app.core.ScheduleText
import com.zzupocket.app.domain.Period
import com.zzupocket.app.domain.Session
import java.time.LocalDate
import java.util.Locale

/**
 * 小组件口径的一节课。
 *
 * 时间统一换算成「当天第几分钟」（0..1439）：排序、"是不是还没下课"、算还剩多久
 * 都只是整数比较，不用反复解析 "08:00"。
 */
data class WidgetCourse(
    val code: String,
    val name: String,
    val place: String,
    val startMin: Int,
    val endMin: Int,
    /** 起始大节（1..10）——周课表组件按它对齐节次行；0 = 未知（旧调用点） */
    val startUnit: Int = 0,
    /** 结束大节；0 = 未知 */
    val endUnit: Int = 0,
) {
    /** "10:00" */
    val startText: String get() = WidgetSchedule.clock(startMin)

    /** "11:35" */
    val endText: String get() = WidgetSchedule.clock(endMin)

    /** "10:00-11:35" —— 与拾光课程表完全一致：**没有空格**的连字符写法 */
    val rangeText: String get() = "$startText-$endText"
}

/**
 * 课表组件的状态。
 *
 * 与拾光课程表一致：正在上课的那节算"还没下课的课"，所以不区分
 * "正在上课/下一节课" 两种状态 —— 边界就是 `end_time > now`。
 */
enum class WidgetCourseState {
    /** 库里没有课表数据（还没登录同步过） */
    NOT_SYNCED,

    /** 有课表数据，但今天不在教学周内（假期） */
    VACATION,

    /** 有还没下课的课 */
    HAS_COURSE,

    /** 今天排了课，但都上完了 */
    FINISHED_TODAY,

    /** 今天本来就排了空 */
    NO_COURSE_TODAY,
}

/**
 * 课表里的一行（4×2 与 2×2 共用同一套行样式）。
 *
 * 一个课程块 = 左侧课程色条 + **课名**（大）+ 下一行 **地点（左）／时间段（右）**（小字、弱色）。
 * 老师不显示 —— 用户明确要求（App 内"格子里不显示老师"的约定延续到组件）。
 */
data class WidgetCourseRow(
    val course: WidgetCourse,
    /** 地点，可能为空 */
    val place: String,
    /** `10:00-11:35` */
    val timeRange: String,
)

/**
 * 「4×2 课表」里的一列（今天 / 明天）。
 *
 * 与拾光课程表 `DoubleDaysNativeRenderer` 一致：一列要么**列出一串课**（[rows]），
 * 要么整列居中一句 [emptyText]（「无课程」）；列头是「今天 9.29 周二」这种**带日期**的写法，
 * 右上角还有「剩余 N 节」/「共有 N 节」。
 */
data class WidgetDayPlan(
    /** 「今天 9.29 周二」 */
    val header: String,
    /** 「剩余 2 节」/「共有 3 节」；空串表示不显示 */
    val footer: String,
    val rows: List<WidgetCourseRow>,
    /** rows 为空时整列显示的那句话：「无课程」 */
    val emptyText: String = "",
)

/** 「4×2 课表」的全部内容：右上角周次 + 左右两列 */
data class WidgetTwoDayPlan(
    /** 「第 5 周」；空串表示不显示 */
    val week: String,
    val today: WidgetDayPlan,
    val tomorrow: WidgetDayPlan,
    /**
     * 整块盖住内容的标题（「假期中」/「还没有课表数据」）；非空 = 走整块状态，两列都不画。
     *
     * 字段名不叫 vacation 了：假期和"还没同步过课表"是两件事，但呈现方式一样
     * （都是"这一整块现在没有课表可看"），所以共用这一个整块状态。
     */
    val overlayTitle: String = "",
    val overlayMessage: String = "",
) {
    val hasOverlay: Boolean get() = overlayTitle.isNotBlank()
}

/**
 * 「2×2」组件的内容 —— 只回答一个问题：**我下一节是什么**。
 *
 * ```
 *   今天 · 第 4 周
 *  ▌大学物理                 ← 行样式（4×2 同款）：色条 + 课名 + 地点/时间段
 *    10:00 - 11:35
 *    南3 101
 *  ▌程序设计基础             ← 第二行 = 下一节（字号小一号）
 *    南3 202        14:00
 *
 * 演化史：中间那版做成了"星期表头 + 一行一节的列表"，真机上被否掉 —— 今天上完、
 * 明天又没课时整个内容区只剩一句状态看着像坏了，而且列表版把 4×2 的
 * 「明天的课看右边」文案带进了 2×2（2×2 没有右边）。随后回退成
 * "一节课大字 + 圆气泡 + 一行预告"；**2026-11-04 第二轮**：用户要求两节课都用
 * 4×2 的「色条 + 文字」行样式、圆气泡删除（剩几节的信息 4×2 页脚已有）——
 * 就是现在的两行形态。⚠️ "别改成整周多行列表"仍成立：这里只排到下一节为止。
 */
data class WidgetNextClassPlan(
    /** 「今天 · 第 4 周」/「明日预告 · 第 4 周」；状态态下为空 */
    val header: String,
    /** 这节课以及之后还有几节（含本节），拾光口径；圆气泡已下线，字段留作数据 */
    val remaining: Int,
    /** 本节（或下一节），完整信息——渲染层用 [WidgetSchedule.toRow] 排成 4×2 同款行 */
    val firstCourse: WidgetCourse? = null,
    /** 下一节（没有则 null）；2×2 的第二行 */
    val nextCourse: WidgetCourse? = null,
    /** 非空 = 整块状态（无课程 / 假期中 / 还没有课表数据） */
    val statusTitle: String = "",
    val statusMessage: String = "",
    /** 假期要整块盖住内容，所以单独一个标记（不靠文案去猜） */
    val isVacation: Boolean = false,
) {
    val isStatus: Boolean get() = statusTitle.isNotBlank()
    val hasCourse: Boolean get() = firstCourse != null
}

/**
 * 课表小组件的**全部判断逻辑**，刻意做成纯函数（不碰 Android、不碰 Room）。
 *
 * ## 口径（沿用拾光课程表 Apache-2.0 的取数规则）
 *  1. 「还没下课的课」= `end_time > now`（正在上的那节算在内，左闭右开）；
 *  2. 4×2 左列今天 = 今天里**还没下课**的，按开始时间升序 → footer「剩余 N 节」；
 *  3. 4×2 右列明天 = 明天**全部**（不看钟点）→ footer「共有 N 节」；
 *  4. 某列没有课 → 整列居中「无课程」；假期 / 还没同步 → 整块盖住（见 [twoDayPlan]）；
 *  5. 2×2 = **两行 4×2 同款行样式（本节 + 下一节）**（[nextClassPlan]）：今天还有课就用今天
 *     还没下课的头两节；今天没了但明天有 → 明天的头两节（表头「明日预告」）；今明都没有 → 「无课程」。
 *
 * 列头日期是「今天 9.29 周二」这种写法（拾光用 `M.dd`），只用在 4×2 的两列上。
 */
object WidgetSchedule {

    /** 480 → "08:00" */
    fun clock(minute: Int): String {
        val m = ((minute % 1440) + 1440) % 1440
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    /** 周一..周日（[weekday] 用 ISO：1=周一） */
    fun weekdayName(weekday: Int): String =
        ScheduleText.WEEKDAY_NAMES.getOrElse(weekday - 1) { "第${weekday}天" }

    /** 「今天 9.29 周二」这种列头（拾光用 `M.dd`，不补零） */
    fun dateHeader(prefix: String, date: LocalDate): String =
        "$prefix ${date.monthValue}.${date.dayOfMonth} ${weekdayName(date.dayOfWeek.value)}"

    /** 「第 5 周」；周次未知时空串 */
    fun weekLabel(week: Int?): String = if (week != null && week > 0) "第 $week 周" else ""

    /**
     * 把教务的一条上课记录变成小组件的一节课。
     *
     * 节次时间优先取作息表（`/room/system/course-units`），取不到才退回记录自带的 startTime；
     * 都取不到就没法排版，返回 null（这一节不显示，而不是显示成 00:00）。
     */
    fun toCourse(session: Session, periods: List<Period>): WidgetCourse? {
        val startPeriod = periods.firstOrNull { it.indexNo == session.startUnit }
        val endPeriod = periods.firstOrNull { it.indexNo == session.endUnit }
        val start = minuteOf(startPeriod?.startText ?: session.startTime) ?: return null
        val end = minuteOf(endPeriod?.endText ?: session.endTime) ?: (start + 45)
        return WidgetCourse(
            code = session.courseCode,
            name = session.courseName,
            // 教务给的 "南2 南2_206" 收拾成 "南2 206"（与 App 课表格子同一套口径）
            place = ScheduleText.tidyPlace(session.roomText ?: session.campusText).orEmpty(),
            startMin = start,
            endMin = end,
            startUnit = session.startUnit,
            endUnit = session.endUnit,
        )
    }

    /** "08:00" / "8:00" → 480；解析不了返回 null */
    fun minuteOf(text: String?): Int? {
        val t = text?.trim().orEmpty()
        val parts = t.split(':')
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].take(2).toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /** 这节课还没下课（左闭右开：下课那一分钟算"已下课"） */
    fun notEnded(course: WidgetCourse, nowMin: Int): Boolean = nowMin < course.endMin

    /** 今天还没下课的课，按时间升序 */
    fun remainingToday(today: List<WidgetCourse>, nowMin: Int): List<WidgetCourse> =
        today.filter { notEnded(it, nowMin) }.sortedBy { it.startMin }

    /** 一节课 → 一行（地点与时间段都在里面） */
    fun toRow(course: WidgetCourse): WidgetCourseRow = WidgetCourseRow(
        course = course,
        place = course.place,
        timeRange = course.rangeText,
    )

    /**
     * 状态判定。
     *
     * @param hasSchedule 库里有没有课表数据 —— 决定"还没同步"与"今天没课"的区别，
     *                    这两件事对用户的意思完全不同
     * @param week 今天属于第几教学周；落在学期外（假期）是 null
     */
    fun state(
        hasSchedule: Boolean,
        week: Int?,
        today: List<WidgetCourse>,
        nowMin: Int,
    ): WidgetCourseState = when {
        !hasSchedule -> WidgetCourseState.NOT_SYNCED
        week == null -> WidgetCourseState.VACATION
        remainingToday(today, nowMin).isNotEmpty() -> WidgetCourseState.HAS_COURSE
        today.isNotEmpty() -> WidgetCourseState.FINISHED_TODAY
        else -> WidgetCourseState.NO_COURSE_TODAY
    }

    /**
     * 整块的标题（4×2 与 2×2 共用，保证口径一致）。
     *
     * 「今天没课」和「今天上完了」**对用户是同一件事：现在没有课**。用户要求一律写「无课程」，
     * 所以原来那两句（「今天没有课程」「今日课程已结束」）都收掉了。
     *
     * 另外两个状态**刻意保留**，因为写「无课程」会误导：
     *  · 还没同步过课表 —— 那是"没数据"，不是"没课"，用户得去 App 同步；
     *  · 假期 —— 那也不是"没课"，写无课程会让人以为课表坏了。
     */
    fun statusTitle(state: WidgetCourseState): String = when (state) {
        WidgetCourseState.NOT_SYNCED -> "还没有课表数据"
        WidgetCourseState.VACATION -> "假期中"
        WidgetCourseState.HAS_COURSE -> ""
        WidgetCourseState.FINISHED_TODAY, WidgetCourseState.NO_COURSE_TODAY -> "无课程"
    }

    fun statusMessage(state: WidgetCourseState): String = when (state) {
        WidgetCourseState.NOT_SYNCED -> "打开 App 同步课表后显示"
        WidgetCourseState.VACATION -> "期待新学期"
        else -> ""
    }

    // ── 4×2：左列今天、右列明天 ──────────────────────────────────────────

    /**
     * 「4×2 课表」的内容。
     *
     * @param todayDate / [tomorrowDate] 用来拼「今天 9.29 周二」这种列头
     * @param maxRows 每列最多列几行（[columnRows] 的粗估，还要过换行感知的精确装柜）
     * @param columnWidthDp / [columnHeightDp] 单列的实测宽高（dp；0 = 不知道，退回旧口径）。
     *        **知道尺寸时**：按课名换行估出每行真实自然高，装不下的行直接不画
     *        （宁可少一行完整的课，也不画上去被裁半截字 —— 2026-11-01 用户截图实证）。
     */
    fun twoDayPlan(
        hasSchedule: Boolean,
        week: Int?,
        todayDate: LocalDate,
        tomorrowDate: LocalDate,
        today: List<WidgetCourse>,
        tomorrow: List<WidgetCourse>,
        nowMin: Int,
        maxRows: Int = 2,
        columnWidthDp: Int = 0,
        columnHeightDp: Int = 0,
    ): WidgetTwoDayPlan {
        val courses = today.sortedBy { it.startMin }
        val st = state(hasSchedule, week, courses, nowMin)
        val weekText = weekLabel(week)
        val take = maxRows.coerceAtLeast(1)
        val nameDp = rowFontSizes(columnHeightDp).first

        fun fit(rows: List<WidgetCourseRow>): List<WidgetCourseRow> {
            if (columnHeightDp <= 0) return rows
            val naturals = rows.map {
                rowNaturalHeightDp(nameLineCount(it.course.name, columnWidthDp, nameDp))
            }
            return rows.take(visibleRowCount(columnHeightDp, naturals))
        }

        // 整块状态（假期 / 还没同步过课表）：两列都不画，换成居中一句话。
        // 以前只有假期走这条，未同步就退化成两列各写一句「无课程」—— 那是假的，
        // 用户会以为课表是空的，而不是"还没同步"。
        if (st == WidgetCourseState.VACATION || st == WidgetCourseState.NOT_SYNCED) {
            return WidgetTwoDayPlan(
                week = "",
                today = WidgetDayPlan(header = dateHeader("今天", todayDate), footer = "", rows = emptyList()),
                tomorrow = WidgetDayPlan(header = dateHeader("明天", tomorrowDate), footer = "", rows = emptyList()),
                overlayTitle = statusTitle(st),
                overlayMessage = statusMessage(st),
            )
        }

        val todayRows = fit(remainingToday(courses, nowMin).take(take).map(::toRow))
        val tomorrowRows = fit(tomorrow.sortedBy { it.startMin }.take(take).map(::toRow))

        return WidgetTwoDayPlan(
            week = weekText,
            today = WidgetDayPlan(
                header = dateHeader("今天", todayDate),
                // footer 仍报**总剩节数**（不是显示了几行）—— 少画的行不隐瞒
                footer = if (remainingToday(courses, nowMin).isEmpty()) "" else "剩余 ${remainingToday(courses, nowMin).size} 节",
                rows = todayRows,
                emptyText = if (remainingToday(courses, nowMin).isEmpty()) "无课程" else "",
            ),
            tomorrow = WidgetDayPlan(
                header = dateHeader("明天", tomorrowDate),
                footer = if (tomorrow.isEmpty()) "" else "共有 ${tomorrow.size} 节",
                rows = tomorrowRows,
                emptyText = if (tomorrow.isEmpty()) "无课程" else "",
            ),
        )
    }

    // ── 4×5（周课表）：按节次对齐的一周网格 ─────────────────────────────

    /** 周课表的一个格子（跨节次的课占 [slotSpan] 个槽） */
    data class WidgetWeekCell(
        /** 行槽索引（0 起，对应 [WidgetWeekPlan] 的节次槽） */
        val slot: Int,
        val slotSpan: Int,
        val name: String,
        val place: String,
        val code: String,
        val timeRange: String,
        /** 开始时刻（"8:00"）—— 6/7 列窄格放不下完整区间时只显示它 */
        val timeStart: String,
    )

    /** 周课表的一列（一天）。[cells] 与节次槽一一对应；null = 空槽（画占位对齐用） */
    data class WidgetWeekColumn(
        /** 列头大字：「一」…「日」；今天的列是「今」 */
        val dayChar: String,
        /** 列头小字：「11.2」 */
        val dateText: String,
        val isToday: Boolean,
        val cells: List<WidgetWeekCell?>,
    )

    /** 周末明细条的一行（一天一行，只在该天有课时出现） */
    data class WidgetWeekendLine(
        val text: String,
        val isToday: Boolean,
        val hasCourse: Boolean,
    )

    /** 「周课表」的全部内容：右上角周次 + 周一~周日 7 列 + （有课时的）周末明细条 */
    data class WidgetWeekPlan(
        /** 「第 8 周」；空串 = 不显示 */
        val week: String,
        val columns: List<WidgetWeekColumn>,
        /**
         * 周末明细行：**只在周六/日有课（含调休）时出现**——一行
         * "六 11.7 课名 地点 10:10-11:50"（完整时间段，网格窄格里放不下的部分在这补全）。
         * 周末没课时为空——7 列空格子本身就是"周末没课"的答案。
         */
        val weekendLines: List<WidgetWeekendLine>,
        val overlayTitle: String = "",
        val overlayMessage: String = "",
    ) {
        val hasOverlay: Boolean get() = overlayTitle.isNotBlank()

        /** 出现过的节次槽总数（渲染时算行高用） */
        val slotCount: Int get() = columns.maxOfOrNull { it.cells.size } ?: 0
    }

    /**
     * 「周课表」的内容（纯函数）。
     *
     * 口径（2026-11-01 四轮反馈的最终形态，用户明确要"7 列 + 相关信息"）：
     *  - **列**：周一..周日**恒 7 列**。单列 ~43dp，紧凑但信息齐全：
     *    课名（9dp、≤3 行）+ 教室（7.5dp、1 行，超宽省略）+ 开始时刻（7.5dp、1 行）。
     *    列头两行式（大字星期 + 小字日期），横向不再挤。
     *  - **周末**：永远在网格里（空格子 = 没课）；有课时底部补一行完整明细
     *    （完整时间段网格里放不下，不丢信息）。
     *  - **行**：节次槽 = 本周**实际出现过的起始节次**去重排序（空节次不占行高；
     *    调休之类的乱序自动正确）。
     *  - 跨节次的课占 [WidgetWeekCell.slotSpan] 个槽，被它盖住的槽在列里是 null。
     *  - 同列同槽撞车（自定义日程叠加）：**先到的赢**，后来的丢弃 —— 网格放不下两门。
     */
    fun weekPlan(
        hasSchedule: Boolean,
        week: Int?,
        coursesByDay: Map<Int, List<WidgetCourse>>,
        today: LocalDate,
    ): WidgetWeekPlan {
        if (!hasSchedule || week == null) {
            val st = if (!hasSchedule) WidgetCourseState.NOT_SYNCED else WidgetCourseState.VACATION
            return WidgetWeekPlan(
                week = "",
                columns = emptyList(),
                weekendLines = emptyList(),
                overlayTitle = statusTitle(st),
                overlayMessage = statusMessage(st),
            )
        }

        val weekdays = (1..7).toList()
        val slots = coursesByDay.values.flatten().map { it.startUnit }.distinct().sorted()
        val slotIndex = slots.withIndex().associate { (i, u) -> u to i }
        val monday = today.minusDays(((today.dayOfWeek.value + 6) % 7).toLong())

        val columns = weekdays.map { day ->
            val courses = coursesByDay[day].orEmpty().sortedBy { it.startUnit }
            val cells = arrayOfNulls<WidgetWeekCell>(slots.size)
            courses.forEach { c ->
                val idx = slotIndex[c.startUnit] ?: return@forEach
                if (cells[idx] != null) return@forEach   // 撞车：先到先得
                val span = slots.count { it >= c.startUnit && it <= c.endUnit }
                    .coerceIn(1, slots.size - idx)
                cells[idx] = WidgetWeekCell(
                    slot = idx,
                    slotSpan = span,
                    name = c.name,
                    place = c.place,
                    code = c.code,
                    timeRange = c.rangeText,
                    timeStart = c.startText,
                )
            }
            val date = monday.plusDays((day - 1).toLong())
            WidgetWeekColumn(
                dayChar = if (day == today.dayOfWeek.value) "今" else WEEKDAY_SHORT.getOrElse(day - 1) { "?" },
                dateText = "${date.monthValue}.${date.dayOfMonth}",
                isToday = day == today.dayOfWeek.value,
                cells = cells.toList(),
            )
        }

        // 周末明细条：只在该天有课时出一行（完整时间段在这补全；没课时条不出现）
        val weekendLines = (6..7).flatMap { day ->
            val courses = coursesByDay[day].orEmpty()
            if (courses.isEmpty()) {
                emptyList()
            } else {
                val date = monday.plusDays((day - 1).toLong())
                val head = "${WEEKDAY_SHORT[day - 1]} ${date.monthValue}.${date.dayOfMonth}"
                val body = courses.joinToString("；") {
                    listOf(it.name, it.place, it.rangeText)
                        .filter { s -> s.isNotBlank() }
                        .joinToString(" ")
                }
                listOf(
                    WidgetWeekendLine(
                        "$head $body",
                        day == today.dayOfWeek.value,
                        true,
                    ),
                )
            }
        }

        return WidgetWeekPlan(week = weekLabel(week), columns = columns, weekendLines = weekendLines)
    }

    /** 「一」「二」…「日」（周课表列头用单字，列宽寸土寸金） */
    private val WEEKDAY_SHORT = listOf("一", "二", "三", "四", "五", "六", "日")

    /**
     * 周课表固定开销（渲染器算行高预算用）：卡片内边距 20（10×2）+ 周次 13 + 间距 2 +
     * **两行列头 22**（大字星期 13 + 小字日期 9）+ 列头下 4 = 61，记 62。**宁可多记**。
     * 周末明细条出现时另加 [WEEKEND_STRIP_EXTRA_DP]。
     */
    const val WEEK_CHROME_DP_FOR_RENDER = 62

    /** 周末明细条一行的高度开销（10.5dp 字 + 间距），外加容器 4dp 上边距一并计入 */
    const val WEEKEND_STRIP_EXTRA_DP = 36

    /**
     * 节次行高：可用高度 ÷ 槽数。上限 120dp（盒子特别高时别让一行字飘在巨大空框里）。
     * @param chromeDp 固定开销（默认 [WEEK_CHROME_DP_FOR_RENDER]；周末明细条出现时传更大的值）
     * @return 0 = 不知道盒子多大，渲染时按自然高走
     */
    fun weekRowHeightDp(heightDp: Int, slotCount: Int, chromeDp: Int = WEEK_CHROME_DP_FOR_RENDER): Int {
        if (heightDp <= 0 || slotCount <= 0) return 0
        return ((heightDp - chromeDp) / slotCount).coerceIn(30, 120)
    }

    /** 课名字号按列宽分档：≥58dp 用 11dp，48~57 用 10dp，7 列的 43dp 用 9dp */
    fun weekNameSizeDp(columnWidthDp: Int): Int = when {
        columnWidthDp <= 0 -> 11
        columnWidthDp >= 58 -> 11
        columnWidthDp >= 48 -> 10
        else -> 9
    }

    /** 地点行：列宽 ≥40（7 列的 43dp 也在内）、行高扣掉课后还有富余才显示（7.5dp 一行，超宽省略） */
    fun weekShowPlace(columnWidthDp: Int, rowHeightDp: Int): Boolean =
        columnWidthDp >= 40 && rowHeightDp >= 64

    /** 时间行（开始时刻）：列宽 ≥40、行高 ≥54 才显示 */
    fun weekShowTime(columnWidthDp: Int, rowHeightDp: Int): Boolean =
        rowHeightDp >= 54 && columnWidthDp >= 40

    /** 列宽放不下完整时间段（<52dp）时，时间行只显示开始时刻（7 列的 43dp 命中） */
    fun weekTimeIsShort(columnWidthDp: Int): Boolean = columnWidthDp in 1..51

    /** 课名最多几行：行高在扣掉地点/时间后还能放几行（至少 1，至多 3） */
    fun weekNameMaxLines(columnWidthDp: Int, rowHeightDp: Int): Int {
        if (rowHeightDp <= 0) return 3
        var budget = rowHeightDp - 8   // 上下内边距
        if (weekShowTime(columnWidthDp, rowHeightDp)) budget -= 13
        if (weekShowPlace(columnWidthDp, rowHeightDp)) budget -= 13
        val lineH = weekNameSizeDp(columnWidthDp) + 3
        return (budget / lineH).coerceIn(1, 3)
    }

    // ── 2×2：两行 4×2 同款行样式（本节 + 下一节），圆气泡已下线 ──────────

    /**
     * 「2×2」的内容。
     *
     * 取数顺序：
     *  1. 今天还有没下课的课 → 头两节排两行，表头「今天 · 第 N 周」；
     *  2. 今天上完了 / 今天本来没课，但**明天有** → 明天的头两节，表头「明日预告 · 第 N 周」。
     *     组件空着最没用，而且这正是用户这次报的场景（今晚课上完、明天国庆没课，
     *     旧版只剩一句状态，看着像坏了）；
     *  3. 今明都没有 → 一句「无课程」。
     */
    fun nextClassPlan(
        hasSchedule: Boolean,
        week: Int?,
        today: List<WidgetCourse>,
        tomorrow: List<WidgetCourse>,
        nowMin: Int,
    ): WidgetNextClassPlan {
        val courses = today.sortedBy { it.startMin }
        val st = state(hasSchedule, week, courses, nowMin)
        val weekSuffix = weekLabel(week).let { if (it.isBlank()) "" else " · $it" }

        if (st == WidgetCourseState.NOT_SYNCED || st == WidgetCourseState.VACATION) {
            return WidgetNextClassPlan(
                header = "", remaining = 0,
                statusTitle = statusTitle(st), statusMessage = statusMessage(st),
                isVacation = st == WidgetCourseState.VACATION,
            )
        }

        val remain = remainingToday(courses, nowMin)
        if (remain.isNotEmpty()) return nextClassOf(remain, "今天$weekSuffix")

        val tomorrowCourses = tomorrow.sortedBy { it.startMin }
        if (tomorrowCourses.isNotEmpty()) {
            return nextClassOf(tomorrowCourses, "明日预告$weekSuffix")
        }

        return WidgetNextClassPlan(
            header = "", remaining = 0, statusTitle = statusTitle(st),
        )
    }

    /** 计划只带"本节 + 下一节"两门课的完整信息；每档显示多少由渲染层按盒子高度收 */
    private fun nextClassOf(
        courses: List<WidgetCourse>,
        header: String,
    ): WidgetNextClassPlan {
        val first = courses.first()
        return WidgetNextClassPlan(
            header = header,
            // 这节课以及之后还有几节（含本节）—— 拾光口径；气泡已下线，字段留作数据
            remaining = courses.size,
            firstCourse = first,
            nextCourse = courses.getOrNull(1),
        )
    }

    /**
     * 下一次「课表变化」的时刻（毫秒时间戳）：今天剩下的课里，最近的那个上课或下课时间点。
     *
     * 为什么需要它：系统对小组件的定时刷新下限是 30 分钟（`updatePeriodMillis`），
     * 光靠它，上课之后桌面上还会挂着旧的课表半小时 —— 显示错的比不显示更糟。
     * 所以在每次刷新后**顺手在下一个上下课时间点定一个（非精确）闹钟**。
     * 一天也就响几次，代价可以忽略。
     *
     * 已经上完今天的课就返回 null（交给系统的 30 分钟定时刷新）。
     */
    fun nextBoundaryMillis(today: List<WidgetCourse>, nowMin: Int, nowMillis: Long): Long? {
        val next = today.flatMap { listOf(it.startMin, it.endMin) }
            .filter { it > nowMin }
            .minOrNull() ?: return null
        // 往后不超过 6 小时，避免作息表里有异常记录时定出一个很远的闹钟
        val deltaMin = (next - nowMin).coerceAtMost(6 * 60)
        return nowMillis + deltaMin * 60_000L
    }

    // ── 版式容量：盒子能放下多少 ─────────────────────────────────────────
    //
    // 这几个数全靠手算，和布局 XML 里的内边距/字号严格对应，改布局就得回来改这里。
    // 放在纯逻辑里是为了能单测 —— 它们是"内容装不下被裁掉"这类问题的唯一保险。
    //
    // ⚠️ 实测（MEIZU 21 Note / Android 16）：4×2 真实画布 **342×195dp**（AppWidgetManager
    // 报的值还要按 sizeDp 的注释修正 ~30dp）、2×2 是 **163×195dp**。
    // 官方老公式算出来的 250×110dp 是错的（那台启动器的格子和公式差三成）。

    /**
     * 单行课行的**基准**高度（课名**一行**时的自然高度）。
     *
     * 2026-11-01 从 46 收到 44（行内边距 6→5，配合 4×2 卡片内边距 14→10）：
     * 修正后画布 165dp 要装两行课、其中一节课名换成两行（自然高 60），
     * 账本 46+62+1=109 > 可用 108 装不下 → 用户看到"少了一节课"。
     * 省出 10dp 固定开销后 44+60+1=105 ≤ 107，两节课**都完整显示**。
     *
     * ⚠️ 别随意加这个数：columnRows 是用它去除画布算"能画几行"，
     * 加大会直接让矮盒子的小组件从两节课变一节（0.32.5 曾把 46 改成 56，用户 4×2 组件
     * 立刻少了一行课）。字号分档已有 rowFontSizes 管字，行高留给行数才是正解。
     */
    const val COURSE_ROW_HEIGHT_DP = 44

    /** 课名每多占一行，行高多出的部分（13dp 字号 × 行高系数 ≈ 15.6，取整 16） */
    const val NAME_LINE_EXTRA_DP = 16

    /** 课名最多两行（widget_course_row.xml 里 maxLines=2） */
    const val NAME_MAX_LINES = 2

    /**
     * 课名在给定列宽里会占几行。
     *
     * 2026-11-01 的教训：issue #6 让长课名**换行**（不再省略）之后，行高账本还按
     * "课名一行"记 —— 两行课名的行实际 ~60dp，账本却记 46，最后一行被顶出画布，
     * "地点/时间段"被裁掉一半（用户截图实证："大学生职业生涯规划与就业指导"）。
     * 所以账本必须感知换行：CJK 记 1em、ASCII 记 0.55em，列宽不知道时按 1 行。
     */
    fun nameLineCount(name: String, columnWidthDp: Int, nameSizeDp: Int = 13): Int {
        if (columnWidthDp <= 0 || name.isBlank()) return 1
        val em = name.sumOf { ch -> if (ch.code > 0x2E80) 1.0 else 0.55 }
        // 列宽里要扣掉色条(3dp)和它与文字的间距(8dp)
        val usable = (columnWidthDp - 11).coerceAtLeast(nameSizeDp)
        val lines = kotlin.math.ceil(em * nameSizeDp / usable).toInt()
        return lines.coerceIn(1, NAME_MAX_LINES)
    }

    /** 一行课的自然高度 = 基准（一行课名）+ 每多一行课名 +[NAME_LINE_EXTRA_DP] */
    fun rowNaturalHeightDp(nameLines: Int): Int =
        COURSE_ROW_HEIGHT_DP + (nameLines.coerceIn(1, NAME_MAX_LINES) - 1) * NAME_LINE_EXTRA_DP

    /**
     * 给定每行的自然高度，这一列**实际画得下几行**。
     *
     * 装不下就少画一行 —— 宁可少一行完整的课，也不能画上去被裁掉半截字
     * （2026-11-01 用户截图实证的教训）。至少画 1 行。
     */
    fun visibleRowCount(heightDp: Int, naturals: List<Int>): Int {
        if (heightDp <= 0) return naturals.size.coerceAtLeast(1)
        val available = heightDp - LIST_CHROME_DP
        var used = 0
        var k = 0
        for (natural in naturals) {
            val cost = natural + if (k > 0) 1 else 0   // 行间 1dp 分隔线
            if (used + cost > available) break
            used += cost
            k++
        }
        return k.coerceAtLeast(1)
    }

    /**
     * 每行的撑高目标：把剩余空间**按行均摊**（各行自然高度可能不同——两行课名的行更高）。
     *
     * ⚠️ 旧实现给所有行同一个目标高度，且"允许压到 34dp" —— 那个压缩只存在于账本里，
     * 视图实际永远 ≥ 自然高，于是账本说"放得下"、实际溢出（就是这次裁字的来源）。
     * 现在：目标 = 自然高 + 均摊剩余（封顶 +[MAX_STRETCH_DP]），**永不低于自然高**。
     *
     * @return 与 [naturals] 等长；元素 0 表示"不知道盒子多大，不撑"
     */
    fun rowStretchTargetsDp(heightDp: Int, naturals: List<Int>): List<Int> {
        if (heightDp <= 0 || naturals.isEmpty()) return naturals.map { 0 }
        val available = heightDp - LIST_CHROME_DP
        val needed = naturals.sum() + (naturals.size - 1)
        val surplus = available - needed
        if (surplus <= 0) return naturals.toList()
        val extra = minOf(surplus / naturals.size, MAX_STRETCH_DP)
        return naturals.map { it + extra }
    }

    /** 撑高的上限（相对自然高）：只温和撑，防"巨大空框里飘一行字"（2×2 五轮起公开共用） */
    const val MAX_STRETCH_DP = 12

    /**
     * 除课程行以外的固定开销（2026-11-01 从 66 收到 58，随卡片内边距 14→10 联动）：
     * 卡片内边距 20（10×2，RemoteViews 覆盖，见 TodayClassesWidget.build）+ 周次 13 +
     * 间距 2 + 列头 15 + 列头下 8。**宁可多记**（可用高度算小）：记少了会放进实际放不下的行。
     */
    private const val LIST_CHROME_DP = 58

    /** 一列最多放几行课（4×2 实测 342×195 → 2 行；画不画得下还要过 [visibleRowCount]） */
    fun columnRows(heightDp: Int): Int {
        if (heightDp <= 0) return 2
        return ((heightDp - LIST_CHROME_DP) / COURSE_ROW_HEIGHT_DP).coerceIn(1, 5)
    }

    /**
     * 行字号按画布高度分档（issue #6）：不同启动器的 4×2 槽位高度差异大，
     * 一套字号打天下会在矮盒子上裁字。≥170dp 标准档（课名 13 / 小字 12），
     * 更矮用紧凑档（12 / 11）；返回 (课名, 小字)。
     */
    fun rowFontSizes(heightDp: Int): Pair<Int, Int> =
        if (heightDp <= 0 || heightDp >= 170) 13 to 12 else 12 to 11

    /**
     * 「2×2」两行课程各自画成什么形态（issue #13 五轮，2026-11-06，HyperOS rc2 实证后的重做）。
     *
     * ⚠️ 五轮教训：四轮的阶梯最后一档是"收 0（不画）"，判档门槛又依赖启动器报数 ——
     * HyperOS 2×2 的可用空间离紧凑档门槛只差 1~7dp，直接掉底变成"只剩一节课 +
     * 大片空白"。**"藏起一节课"永远不应该是任何高度档位的出口**，所以 HIDE 删除，
     * 最低档改成"单行一行式"——它小到任何合法盒子（minResize 110dp）都放得下。
     */
    enum class NextClassPreviewStyle {
        /** 单行一行式：课名（超长省略）+ 时间段同一行，不画地点。最低档 */
        MINI,

        /** 紧凑三行式：课名 / 地点 / 时间段各占一行；地点恒一行，过长先缩字号再裁左边 */
        COMPACT,

        /** 满档：竖排三行形态（课名 / 地点 / 时间段各占一行，不挤一行） */
        STACKED,
    }

    /**
     * 判档用的**各档自然高**（渲染层 Paint 实测后填入，纯函数只做选择）。
     * 每行的三档形态都给自然高——阶梯会从"本节"降到底，不只降"第二行"。
     */
    data class NextClassRowNaturals(
        val firstStacked: Int,
        val firstCompact: Int,
        val firstMini: Int,
        val secondStacked: Int,
        val secondCompact: Int,
        val secondMini: Int,
    )

    /** 某行某档的自然高（渲染层与单测共用口径） */
    fun nextClassRowNatDp(
        n: NextClassRowNaturals,
        style: NextClassPreviewStyle,
        first: Boolean,
    ): Int = when (style) {
        NextClassPreviewStyle.STACKED -> if (first) n.firstStacked else n.secondStacked
        NextClassPreviewStyle.COMPACT -> if (first) n.firstCompact else n.secondCompact
        NextClassPreviewStyle.MINI -> if (first) n.firstMini else n.secondMini
    }

    /**
     * 判档缓冲：宁可早降一档（形态保守、底部略空），也不赌启动器报数是准的。
     * 2026-11-06 rc2 实证：124 与 126 之间 1dp 就差一个"第二节消失"。
     * 八轮（2026-11-09）：字号回大字档，行内边距 2→0 腾 8dp、缓冲 8→5 再让 3dp。
     * 真机实测（157×147dp，MiSans 行高 14=19/13=18/11=15）：大字档两行自然高 102dp，
     * 可用 109 − 缓冲 5 = 104，留 2dp；再叠加账本每行 1dp 余量，实际余量约 4dp。
     * 且 [chooseCompactTier] 会在放不下时整组降字号，不会把地点砍成 MINI。
     */
    const val NEXT_CLASS_LADDER_BUFFER_DP = 5

    /** 紧凑三行档里地点的最小字号（dp）：缩到 11dp 就裁左边，保证「地点 ≥ 时间」的层级不被打破 */
    const val NEXT_CLASS_PLACE_MIN_DP = 11

    /**
     * 紧凑三行档的单边内边距（dp）；XML padding、stretch 基线与 compactNat 账本共用这一个数。
     * 八轮收到 0：省下的 4dp/行给放大后的三行让路；行并不会真的贴边——
     * [Widgets.stretch] 会把 surplus 以 padding 摊回去，真机上每行仍有约 1~2dp 上下留白。
     */
    const val NEXT_CLASS_TRIPLE_PAD_DP = 0

    /**
     * 紧凑三行档内「课名→地点→时间段」之间的行距（dp）。九轮（2026-11-09）从 0 加到 2：
     * 三行原本首尾紧贴（真机视图树实测地点行底 = 时间行顶），看着挤。
     * 对应 widget_course_row_triple.xml / widget_next_class_preview.xml 里的
     * `@dimen/widget_triple_line_gap`，改这个数必须同步 [nextClassSpacedRowsDp] 的账本。
     */
    const val NEXT_CLASS_TRIPLE_LINE_GAP_DP = 2

    /** 两节课之间的净间距（dp）：由两行各摊一半到自己的上下 padding 合成，只给紧凑三行档 */
    const val NEXT_CLASS_ROW_GAP_DP = 6

    /**
     * 2×2 的「排版余量」（dp）：只用来拉行距 / 课间隔，**不参与判档**。
     *
     * 依据 2026-11-09 真机 uiautomator 视图树（480dpi，1dp=3px）：SIZES 上报 157×177dp，
     * [Widgets.sizeDp] 统一减 [Widgets.HEIGHT_OVERREPORT_DP]=30 得 147dp；但桌面格子的
     * 实际高度约 177dp（卡片顶≈183px、下方图标标题从 y=713px 起 ⇒ 卡片高≈530px），
     * 两行课只画到 y=562px，底部空出约 50dp。即 30dp 是 Meizu 标定的值，
     * 在 HyperOS 的 2×2 上偏保守，导致 stretch 没把空白用起来。
     *
     * ⚠️ 只补回 24dp、留 6dp 安全边，且**只在 NextClassWidget 的排版高度里加**：
     * 判档仍走修正后的保守画布（[NEXT_CLASS_LADDER_BUFFER_DP] 与字号回退逻辑一个字没动），
     * 4×2 与其它组件完全不受影响。换启动器/机型若发现底部时间行被裁，把它调小或置 0。
     */
    const val NEXT_CLASS_2X2_LAYOUT_BONUS_DP = 24

    /**
     * 紧凑三行档的一套字号：课名 > 地点 > 时间，本节整体比下一节大一号。
     * 八轮（2026-11-09 用户拍板）默认 14/13/11 + 13/12/10；放不下时按
     * [NEXT_CLASS_COMPACT_TIERS] 逐级回退，**绝不因为放大字号把地点降级成 MINI 砍掉**。
     */
    data class CompactFontTier(
        val label: String,
        val n1: Int, val p1: Int, val t1: Int,
        val n2: Int, val p2: Int, val t2: Int,
    )

    val NEXT_CLASS_COMPACT_TIERS: List<CompactFontTier> = listOf(
        CompactFontTier("大字14/13/11", 14, 13, 11, 13, 12, 10),
        CompactFontTier("中字13/12/11", 13, 12, 11, 12, 11, 10),
        CompactFontTier("标准13/11/11", 13, 11, 11, 12, 10, 10),
        CompactFontTier("矮盒12/10/10", 12, 10, 10, 11, 9, 9),
    )

    /**
     * 从大字档往下试：取第一档"两行都没掉到 MINI"的字号。
     * 判档用渲染层传进来的**实测行高**账本（[naturals]），换字体/换启动器都自适应；
     * 全都不行就落最小档，由 [nextClassRowPair] 自己走 MINI 兜底（课永不蒸发）。
     */
    fun chooseCompactTier(
        availDp: Int,
        naturals: (CompactFontTier) -> NextClassRowNaturals,
    ): CompactFontTier {
        for (tier in NEXT_CLASS_COMPACT_TIERS) {
            val pair = nextClassRowPair(availDp, naturals(tier))
            if (pair.first != NextClassPreviewStyle.MINI && pair.second != NextClassPreviewStyle.MINI) {
                return tier
            }
        }
        return NEXT_CLASS_COMPACT_TIERS.last()
    }

    /** 地点自适应结果：最终显示的文本与字号（dp） */
    data class PlaceFit(val text: String, val sizeDp: Int)

    /**
     * 地点永远只占一行：先把字号从 [maxSizeDp] 逐级缩到 [minSizeDp] 直到完整放下，
     * 仍放不下就从**左边**裁（"…"+尾部）——教室细节（楼栋之后的房号）在末尾。
     * 八轮起 [minSizeDp] 固定 11dp（与时间同号）：一眼要看到的地点不允许比时间还小。
     *
     * 纯函数：宽度由 [measureWidthDp] 提供，渲染层传 Paint 实测，JVM 单测用默认估算。
     * 缩字号只会让行变矮，行高账本按 [maxSizeDp] 记属于"多记"，方向安全；
     * **时间行不跟着缩**（独立字号），否则地点缩到 11dp 时层级会塌成一样大。
     */
    fun fitPlaceText(
        place: String,
        usableWidthDp: Int,
        maxSizeDp: Int,
        minSizeDp: Int,
        measureWidthDp: (String, Int) -> Float = { text, size -> estimatePlaceWidthDp(text, size) },
    ): PlaceFit {
        if (place.isBlank() || usableWidthDp <= 0) return PlaceFit(place, maxSizeDp)
        val floor = minSizeDp.coerceIn(1, maxSizeDp)
        for (size in maxSizeDp downTo floor) {
            if (measureWidthDp(place, size) <= usableWidthDp.toFloat()) {
                return PlaceFit(place, size)
            }
        }
        val budget = usableWidthDp.toFloat() - measureWidthDp("…", floor)
        var tail = ""
        for (ch in place.reversed()) {
            val candidate = ch + tail
            if (measureWidthDp(candidate, floor) > budget) break
            tail = candidate
        }
        return PlaceFit("…" + tail, floor)
    }

    /** 无 Android 依赖的宽度估算：CJK 记 1em、ASCII 记 0.55em（与 nameLineCount 同口径，偏保守） */
    private fun estimatePlaceWidthDp(text: String, sizeDp: Int): Float =
        text.sumOf { ch -> (if (ch.code > 0x2E80) 1.0 else 0.55) * sizeDp }.toFloat()

    /**
     * 「2×2」两行的档位阶梯——紧凑档是**紧凑三行**版（#13 七轮定形、八轮放大字号）。
     *
     * 六轮的紧凑两行式把"地点(左，省略)｜时间段(右)"挤在同一行，长教室名会被右边的
     * 时间段吃掉。七轮把紧凑档换成 `widget_course_row_triple.xml`：课名 / 地点 / 时间段
     * 各占一行，地点恒单行。八轮（2026-11-09）按用户拍板把三行做成层级
     * **课名 14 > 地点 13 > 时间 11**（下一节各小一号），并腾出高度：行内边距 0dp（由
     * stretch 摊回）、判档缓冲 5dp、色条固定 42dp。自然高账本必须按 `lhN + lhPlace + lhTime` 分开记，
     * 字号放不下时由 [chooseCompactTier] 降级字号而不是降级形态，地点永远不消失。
     * 满档竖排仍只留给拉高的 2×3+。
     *
     *  · 高度读不到（≤0）→ **画满**（两行都满档竖排、自然高不撑）：options 首次回调
     *    前的空档不能表现为"课没了"；行是自然高，画满也不会行内裁字；
     *  · 从 [满/满, 紧凑/紧凑, 紧凑/单行, 单行/单行] 里取**第一个"两行自然高之和
     *    ≤ 可用−缓冲"的组合**——紧凑档是**两行都**一起转，不做混搭；
     *  · 全都超预算 → 单行/单行兜底——最低档任何合法盒子都放得下，
     *    **两节课在任何情况下都至少以最低档存在**。
     *
     * @param availDp 可用高度（画布 − 固定开销）；≤0 = 读不到尺寸 → 画满
     */
    fun nextClassRowPair(
        availDp: Int,
        n: NextClassRowNaturals,
    ): Pair<NextClassPreviewStyle, NextClassPreviewStyle> {
        if (availDp <= 0) return NextClassPreviewStyle.STACKED to NextClassPreviewStyle.STACKED
        val budget = availDp - NEXT_CLASS_LADDER_BUFFER_DP
        val ladder = listOf(
            NextClassPreviewStyle.STACKED to NextClassPreviewStyle.STACKED,
            NextClassPreviewStyle.COMPACT to NextClassPreviewStyle.COMPACT,
            NextClassPreviewStyle.COMPACT to NextClassPreviewStyle.MINI,
            NextClassPreviewStyle.MINI to NextClassPreviewStyle.MINI,
        )
        for ((first, second) in ladder) {
            val sum = nextClassRowNatDp(n, first, first = true) +
                nextClassRowNatDp(n, second, first = false)
            if (sum <= budget) return first to second
        }
        return NextClassPreviewStyle.MINI to NextClassPreviewStyle.MINI
    }

    /** 一行课的排版结果：目标高（喂给 [Widgets.stretch]）与内容自然高（不含摊开的间距） */
    data class SpacedRow(val targetHeightDp: Int, val contentHeightDp: Int)

    /**
     * 把「行距 + 课间隔 + 富余高度」折算成两行的 padding 摊余（九轮，2026-11-09）。
     *
     * 与判档**刻意解耦**：[nextClassRowPair] / [chooseCompactTier] 只看文字自然高，
     * 这里只决定每行该撑到多高；所以加大间距不会把 14/13/11 挤掉档，更不会把地点降级。
     * 只有紧凑三行档加间距（STACKED 版式自带行距、MINI 只有一行，都不加）。
     *
     * 课间隔由两行各摊 [NEXT_CLASS_ROW_GAP_DP] 到自己的上下 padding 合成：
     * 行1 底 +gap/2、行2 顶 +gap/2 ⇒ 净间隔 gap；卡片上下也各多 gap/2，整体更透气。
     * surplus 按两行基准高比例分，仍受 [MAX_STRETCH_DP] 封顶，行高永不低于内容自然高。
     */
    fun nextClassSpacedRowsDp(
        availDp: Int,
        nat1: Int,
        nat2: Int,
        compact1: Boolean,
        compact2: Boolean,
    ): Pair<SpacedRow, SpacedRow> {
        val lineExtra1 = if (compact1) NEXT_CLASS_TRIPLE_LINE_GAP_DP * 2 else 0
        val lineExtra2 = if (compact2) NEXT_CLASS_TRIPLE_LINE_GAP_DP * 2 else 0
        val gap1 = if (compact1) NEXT_CLASS_ROW_GAP_DP else 0
        val gap2 = if (compact2) NEXT_CLASS_ROW_GAP_DP else 0
        val content1 = nat1 + lineExtra1
        val content2 = nat2 + lineExtra2
        val base1 = content1 + gap1
        val base2 = content2 + gap2
        val total = base1 + base2
        val surplus = (availDp - total).coerceAtLeast(0)
        val share1 = (if (total > 0) surplus * base1 / total else 0).coerceAtMost(MAX_STRETCH_DP)
        val share2 = (surplus - share1).coerceAtLeast(0).coerceAtMost(MAX_STRETCH_DP)
        return SpacedRow(base1 + share1, content1) to SpacedRow(base2 + share2, content2)
    }

    /**
     * 「2×2」的固定开销账本（2026-11-05，issue #13 HyperOS 翻车后重算）：
     * 卡片内边距 20（10×2，配合 NextClassWidget.build 里的 setViewPadding 14→10）
     * + 表头 22 + 行距 2。账例同 0.32 系列：**宁可多记**（可用高度算小）。
     */
    const val NEXT_CLASS_CHROME_DP = 44

    /** 「2×2」渲染层的可用高度 = 画布 − 固定开销；读不到尺寸返回 0（走"自然高"分支） */
    fun nextClassAvailableDp(heightDp: Int): Int =
        nextClassAvailableDp(heightDp, NEXT_CLASS_CHROME_DP)

    /** 同上，固定开销由渲染层实测（表头行高随系统字体变，魔数 44 在 MiSans 上偏小） */
    fun nextClassAvailableDp(heightDp: Int, chromeDp: Int): Int =
        if (heightDp <= 0) 0 else (heightDp - chromeDp).coerceAtLeast(0)

    /**
     * 竖排行（课名 / 地点 / 时间段各占一行）的自然高。
     *
     * 竖排 = 单行版式（[COURSE_ROW_HEIGHT_DP]=44：课名 + "地点·时间"一行）多出一行地点小字：
     * 13dp 行 + 2dp 行距 ≈ 15dp。课名换行另加 [NAME_LINE_EXTRA_DP]。
     */
    fun stackedRowNaturalHeightDp(nameLines: Int): Int =
        rowNaturalHeightDp(nameLines) + 15

    /**
     * 「2×2」按盒子高度决定显示到哪一行。
     *
     * 正常 2×2 = 195dp 高，课名 / 时间段 / 地点 / 预告 都放得下。
     * 但这个盒子**可能被人拉矮**，也可能还是更早版本留下的低矮实例
     * （实例尺寸由启动器记着，改 provider 的 targetCell 不会自动改已有实例）——
     * 硬塞只会被裁成半截字，所以从下往上收：先收地点，课名永远留着。
     */
    fun nextClassDetail(heightDp: Int): RowDetail = when {
        heightDp <= 0 -> RowDetail.FULL
        heightDp >= 78 -> RowDetail.FULL
        else -> RowDetail.NAME
    }

    // ── 「电量与校园卡」的块高 ───────────────────────────────────────────

    /** 电量组件的固定开销：内边距 28 + 更新时间行 12 + 分栏标题 16 + 中间分隔线 1 */
    private const val BLOCK_CHROME_DP = 57

    /**
     * 每个数据块该占多高（每半两个块）。
     *
     * 这里比课程行撑得狠一点（数据块里没有会被裁的次要行，风险小）：
     * 195dp 的盒子里每块约 67dp（不撑时 51dp），把两半铺开。
     */
    fun blockHeightDp(heightDp: Int): Int {
        if (heightDp <= 0) return 0
        val fit = (heightDp - BLOCK_CHROME_DP - 4) / 2
        return fit.coerceIn(48, 51 + 18)
    }

    /** 一行课最多显示多少信息（宽度越窄，越先舍弃次要信息） */
    enum class RowDetail {
        /** 只放得下课名 + 时间 */
        NAME,

        /** 课名 + 地点 + 时间段 */
        FULL,
    }

    /**
     * 4×2 每列宽约 148dp、2×2 宽约 135dp，都放得下"地点 + 时间段"；
     * 再窄（被人拉小）就只留时间段。
     */
    fun rowDetail(widthDp: Int): RowDetail = when {
        widthDp <= 0 -> RowDetail.FULL
        widthDp >= 120 -> RowDetail.FULL
        else -> RowDetail.NAME
    }
}
