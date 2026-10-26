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
 *   今天 · 第 4 周              ╭──╮
 *  ▓ 大学物理                   │ 3│   ← 圆气泡 = 这节课以及之后还有几节（含本节）
 *    10:00 - 11:35              ╰──╯
 *    南3 101
 *    → 14:00 程序设计基础               ← 再往后那节课
 * ```
 *
 * 这是**回退后的形态**：中间那版做成了"星期表头 + 一行一节的列表"，
 * 真机上被否掉 —— 今天上完、明天又没课时，整个内容区只剩一句状态，看着像坏了；
 * 而且列表版把 4×2 的「明天的课看右边」文案带进了 2×2（2×2 没有右边）。
 * 用户要求回到"一节课大字 + 圆气泡 + 一行预告"，所以这里按那个形态重写。
 */
data class WidgetNextClassPlan(
    /** 「今天 · 第 4 周」/「明日预告 · 第 4 周」；状态态下为空 */
    val header: String,
    /** 圆气泡里的数字：这节课以及之后还有几节（含本节）；0 = 不显示气泡 */
    val remaining: Int,
    val name: String,
    /** `10:00 - 11:35`：2×2 竖着有空间，写完整时间段（4×2 的列宽写不下） */
    val timeRange: String,
    val place: String,
    /** 课名下面那行预告：「→ 14:00 程序设计基础」；没有就是空串 */
    val preview: String,
    /** 课程代码，用来给色条取色（与 App 课表格子同一套算法） */
    val code: String,
    /** 非空 = 整块状态（无课程 / 假期中 / 还没有课表数据） */
    val statusTitle: String = "",
    val statusMessage: String = "",
    /** 假期要整块盖住内容，所以单独一个标记（不靠文案去猜） */
    val isVacation: Boolean = false,
) {
    val isStatus: Boolean get() = statusTitle.isNotBlank()
    val hasCourse: Boolean get() = name.isNotBlank()
}

/**
 * 课表小组件的**全部判断逻辑**，刻意做成纯函数（不碰 Android、不碰 Room）。
 *
 * ## 口径（沿用拾光课程表 Apache-2.0 的取数规则）
 *  1. 「还没下课的课」= `end_time > now`（正在上的那节算在内，左闭右开）；
 *  2. 4×2 左列今天 = 今天里**还没下课**的，按开始时间升序 → footer「剩余 N 节」；
 *  3. 4×2 右列明天 = 明天**全部**（不看钟点）→ footer「共有 N 节」；
 *  4. 某列没有课 → 整列居中「无课程」；假期 / 还没同步 → 整块盖住（见 [twoDayPlan]）；
 *  5. 2×2 = **一节课大字 + 圆气泡 + 一行预告**（[nextClassPlan]）：今天还有课就用今天的
 *     第一节；今天没了但明天有 → 顶上明天的第一节（表头「明日预告」）；今明都没有 → 「无课程」。
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
     * @param maxRows 每列最多列几行（按实测画布高度算，见 [columnRows]）
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
    ): WidgetTwoDayPlan {
        val courses = today.sortedBy { it.startMin }
        val st = state(hasSchedule, week, courses, nowMin)
        val weekText = weekLabel(week)
        val take = maxRows.coerceAtLeast(1)

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

        val todayRows = remainingToday(courses, nowMin)
        val tomorrowRows = tomorrow.sortedBy { it.startMin }

        return WidgetTwoDayPlan(
            week = weekText,
            today = WidgetDayPlan(
                header = dateHeader("今天", todayDate),
                footer = if (todayRows.isEmpty()) "" else "剩余 ${todayRows.size} 节",
                rows = todayRows.take(take).map(::toRow),
                emptyText = if (todayRows.isEmpty()) "无课程" else "",
            ),
            tomorrow = WidgetDayPlan(
                header = dateHeader("明天", tomorrowDate),
                footer = if (tomorrowRows.isEmpty()) "" else "共有 ${tomorrowRows.size} 节",
                rows = tomorrowRows.take(take).map(::toRow),
                emptyText = if (tomorrowRows.isEmpty()) "无课程" else "",
            ),
        )
    }

    // ── 2×2：一节课大字 + 圆气泡 + 一行预告 ──────────────────────────────

    /** 「→ 14:00 程序设计基础」这种预告行 */
    fun previewText(course: WidgetCourse): String = "→ ${course.startText} ${course.name}"

    /**
     * 「2×2」的内容。
     *
     * 取数顺序：
     *  1. 今天还有没下课的课 → 第一节占大块，表头「今天 · 第 N 周」；
     *  2. 今天上完了 / 今天本来没课，但**明天有** → 顶上明天的第一节，表头「明日预告 · 第 N 周」。
     *     组件空着最没用，而且这正是用户这次报的场景（今晚课上完、明天国庆没课，
     *     旧版只剩一句状态，看着像坏了）；
     *  3. 今明都没有 → 一句「无课程」。
     *
     * @param previewRows 课名下面还能放几行预告（按盒子高度算，见 [nextClassPreviews]）
     */
    fun nextClassPlan(
        hasSchedule: Boolean,
        week: Int?,
        today: List<WidgetCourse>,
        tomorrow: List<WidgetCourse>,
        nowMin: Int,
        previewRows: Int = 1,
    ): WidgetNextClassPlan {
        val courses = today.sortedBy { it.startMin }
        val st = state(hasSchedule, week, courses, nowMin)
        val weekSuffix = weekLabel(week).let { if (it.isBlank()) "" else " · $it" }

        if (st == WidgetCourseState.NOT_SYNCED || st == WidgetCourseState.VACATION) {
            return WidgetNextClassPlan(
                header = "", remaining = 0, name = "", timeRange = "", place = "",
                preview = "", code = "",
                statusTitle = statusTitle(st), statusMessage = statusMessage(st),
                isVacation = st == WidgetCourseState.VACATION,
            )
        }

        val remain = remainingToday(courses, nowMin)
        if (remain.isNotEmpty()) return nextClassOf(remain, "今天$weekSuffix", previewRows)

        val tomorrowCourses = tomorrow.sortedBy { it.startMin }
        if (tomorrowCourses.isNotEmpty()) {
            return nextClassOf(tomorrowCourses, "明日预告$weekSuffix", previewRows)
        }

        return WidgetNextClassPlan(
            header = "", remaining = 0, name = "", timeRange = "", place = "",
            preview = "", code = "", statusTitle = statusTitle(st),
        )
    }

    /** 把一串课排成"第一节大字 + 后面几行预告" */
    private fun nextClassOf(
        courses: List<WidgetCourse>,
        header: String,
        previewRows: Int,
    ): WidgetNextClassPlan {
        val first = courses.first()
        return WidgetNextClassPlan(
            header = header,
            // 这节课以及之后还有几节（含本节）—— 与拾光的圆气泡口径一致
            remaining = courses.size,
            name = first.name,
            timeRange = first.rangeText,
            place = first.place,
            preview = courses.drop(1)
                .take(previewRows.coerceAtLeast(0))
                .joinToString("   ") { previewText(it) },
            code = first.code,
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
    // ⚠️ 实测（MEIZU 21 Note / Android 16）：4×2 真实画布 **342×195dp**、2×2 是 **163×195dp**。
    // 官方老公式算出来的 250×110dp 是错的（那台启动器的格子和公式差三成）。

    /**
     * 一行课的自然内容高：课名**可两行**（13dp × 2 ≈ 32）+ 地点/时间段（12dp ≈ 14）
     * + 上下内边距 12 ≈ 56（issue #6：长班级名换行而不是省略，行高随之抬高；
     * 短课名的行被 minHeight 撑到同一高度，内容竖直居中不受影响）。
     */
    /**
     * 单行课行的**基准**高度。⚠️ 别随意加这个数：columnRows 是用它去除画布算"能画几行"，
     * 加大会直接让矮盒子的小组件从两节课变一节（0.32.5 曾把 46 改成 56，用户 4×2 组件
     * 立刻少了一行课）。字号分档已有 rowFontSizes 管字，行高留给行数才是正解。
     */
    const val COURSE_ROW_HEIGHT_DP = 46

    /**
     * 撑高的上限（相对不撑时的自然高度）。
     *
     * ⚠️ 实测教训：一开始我按"可用高度 ÷ 行数"狠狠撑（195dp 的盒子里每行撑到 64dp），
     * 结果**最后一行被压扁**了 —— 列容器本身不会撑满（`match_parent` 在 RemoteViews 的
     * 加权父容器里没生效），多出来的高度全由最后一个孩子吸收，于是它的文字被裁掉 6dp。
     * 所以只温和撑（最多 +[MAX_STRETCH_DP]），并且从可用高度里**留出 [SLACK_DP] 的余量**：
     * 宁可底部留一点点空，也不能裁字。
     */
    private const val MAX_STRETCH_DP = 12

    /** 计算可用高度时先扣掉的余量（防裁字） */
    private const val SLACK_DP = 10

    /** 除课程行以外的固定开销：内边距 28 + 周次 12 + 间距 2 + 列头 16 + 列头下方 8 */
    private const val LIST_CHROME_DP = 66

    /**
     * 一行课在给定盒子里应该占多高（dp）。
     *
     * 行内容只有约 50dp 高，而 4×2 的实测画布有 195dp —— 不撑的话两行只占 100dp，
     * 下面近百 dp 全空（用户报的"下部分很空"）。所以按「可用高度 ÷ 行数」撑，
     * 但只温和地撑（见 [MAX_STRETCH_DP] 的注释），并且留余量防裁字。
     *
     * @param rowCount 这一列**实际**有几行（不是"最多能放几行"）
     * @return 0 表示"不知道盒子多大，别撑"（保持自然高度）
     */
    fun courseRowHeightDp(heightDp: Int, rowCount: Int, chromeDp: Int = LIST_CHROME_DP): Int {
        if (heightDp <= 0 || rowCount <= 0) return 0
        val area = heightDp - chromeDp - (rowCount - 1) - SLACK_DP
        val fit = area / rowCount
        // 温和撑高；行数画不下时**允许往下压**（最低 34dp，仍够 12/11sp 小字），
        // 而不是咬牙维持基准高把最后一行顶出画布裁掉。
        return fit.coerceIn(34, COURSE_ROW_HEIGHT_DP + MAX_STRETCH_DP)
    }

    /** 一列最多放几行课（4×2 实测 342×195 → 2 行） */
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

    /** 2×2「下一节课」的大字课名：标准档 17dp，矮盒子 15dp */
    fun bigNameSizeDp(heightDp: Int): Int =
        if (heightDp <= 0 || heightDp >= 170) 17 else 15

    /**
     * 「2×2」能放几行预告。
     *
     * 195dp 的实测画布上：课名 / 时间段 / 地点三行约 50dp，加上表头与内边距共约 110dp，
     * 剩下 85dp 本来能放好几行预告 —— 但**只放一行**：这个组件的定位是"我下一节是什么"，
     * 预告多了就变成课表了（那是 4×2 该干的事）。
     * 盒子被拉矮时先收预告。
     */
    fun nextClassPreviews(heightDp: Int): Int = when {
        heightDp <= 0 -> 1      // 读不到尺寸时按正常 2×2
        heightDp >= 105 -> 1
        else -> 0
    }

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
