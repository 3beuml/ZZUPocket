package com.zzupocket.app.ui.widget

import com.zzupocket.app.domain.Period
import com.zzupocket.app.domain.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 课表小组件口径的回归测试。
 *
 * 这一版是照着**拾光课程表**（用户要求"完全按照拾光课表的来"）逐条对齐的，
 * 所以测试里钉的就是它的那几条规则：
 *   · 「还没下课的课」= `end_time > now`（正在上的那节算在内，左闭右开）；
 *   · 4×2 左列今天只列还没下课的 → footer「剩余 N 节」；右列明天列全部 → footer「共有 N 节」；
 *   · 某列没课 → 整列「无课程」；假期 → 整块「假期中」；
 *   · 2×2：今天还有课就列今天的；今天没了但明天有 → 表头「明日预告」；
 *     今明都没课 → 「今天没有课程」/「今日课程已结束」；
 *   · 一块 = 课名 + （地点／时间段），**不带老师**。
 */
class WidgetScheduleTest {

    // ── 造数据 ───────────────────────────────────────────────────────────

    private fun course(
        start: Int,
        end: Int,
        name: String = "课",
        place: String = "",
        code: String = "C1",
    ) = WidgetCourse(code = code, name = name, place = place, startMin = start, endMin = end)

    private fun period(indexNo: Int, start: Int, end: Int) = Period(
        indexNo = indexNo,
        nameZh = "第${indexNo}节",
        startTime = start,
        endTime = end,
        dayPart = "MORNING",
        segmentIndex = indexNo,
    )

    private fun session(
        courseName: String = "高等数学A",
        startUnit: Int = 1,
        endUnit: Int = 2,
        startTime: String? = null,
        endTime: String? = null,
        roomText: String? = "南2 南2_206",
        campusText: String? = null,
    ) = Session(
        id = "s1",
        semesterId = 1,
        source = "eas",
        courseCode = "MATH001",
        courseName = courseName,
        weekIndex = 3,
        weekday = 1,
        startUnit = startUnit,
        endUnit = endUnit,
        date = null,
        startTime = startTime,
        endTime = endTime,
        teacherName = null,
        roomText = roomText,
        campusText = campusText,
        color = null,
    )

    private val periods = listOf(period(1, 800, 845), period(2, 850, 935))
    private val monday = LocalDate.of(2026, 9, 28)
    private val tuesday = LocalDate.of(2026, 9, 29)

    // ── 基础工具 ─────────────────────────────────────────────────────────

    @Test
    fun `分钟转时刻补零`() {
        assertEquals("08:00", WidgetSchedule.clock(480))
        assertEquals("00:05", WidgetSchedule.clock(5))
        assertEquals("23:59", WidgetSchedule.clock(-1))
    }

    @Test
    fun `时刻解析接受各种写法并拒绝垃圾`() {
        assertEquals(480, WidgetSchedule.minuteOf("08:00"))
        assertEquals(480, WidgetSchedule.minuteOf("8:00"))
        assertEquals(485, WidgetSchedule.minuteOf(" 8:05 "))
        assertNull(WidgetSchedule.minuteOf(null))
        assertNull(WidgetSchedule.minuteOf(""))
        assertNull(WidgetSchedule.minuteOf("25:00"))
        assertNull(WidgetSchedule.minuteOf("08:99"))
        assertNull(WidgetSchedule.minuteOf("第八节"))
    }

    @Test
    fun `时间段是 无空格的连字符写法_与拾光一致`() {
        val c = course(600, 695, place = "南3 101")
        assertEquals("10:00", c.startText)
        assertEquals("11:35", c.endText)
        assertEquals("10:00-11:35", c.rangeText)
    }

    @Test
    fun `列头带日期_周次文案`() {
        assertEquals("今天 9.28 周一", WidgetSchedule.dateHeader("今天", monday))
        assertEquals("明天 9.29 周二", WidgetSchedule.dateHeader("明天", tuesday))
        assertEquals("第 5 周", WidgetSchedule.weekLabel(5))
        // 周次未知（假期）不显示
        assertEquals("", WidgetSchedule.weekLabel(null))
        assertEquals("", WidgetSchedule.weekLabel(0))
    }

    @Test
    fun `星期中文名`() {
        assertEquals("周一", WidgetSchedule.weekdayName(1))
        assertEquals("周日", WidgetSchedule.weekdayName(7))
        assertEquals("第9天", WidgetSchedule.weekdayName(9))
    }

    // ── 一节课的构造 ─────────────────────────────────────────────────────

    @Test
    fun `节次时间优先用作息表`() {
        val c = WidgetSchedule.toCourse(session(), periods)
        assertEquals(480, c!!.startMin)
        assertEquals(575, c.endMin)
        assertEquals("高等数学A", c.name)
    }

    @Test
    fun `作息表查不到时退回课程自带时间`() {
        val c = WidgetSchedule.toCourse(
            session(startUnit = 99, endUnit = 99, startTime = "14:00", endTime = "15:35"),
            periods,
        )
        assertEquals(840, c!!.startMin)
        assertEquals(935, c.endMin)
    }

    @Test
    fun `地点被规整成 楼栋+房号`() {
        assertEquals("南2 206", WidgetSchedule.toCourse(session(), periods)!!.place)
        assertEquals("南1 302", WidgetSchedule.toCourse(session(roomText = "南1_302"), periods)!!.place)
        assertEquals(
            "主校区",
            WidgetSchedule.toCourse(session(roomText = null, campusText = "主校区"), periods)!!.place,
        )
    }

    @Test
    fun `时间完全解析不出来时这一节不显示`() {
        assertNull(
            WidgetSchedule.toCourse(
                session(startUnit = 99, endUnit = 99, startTime = null, endTime = null),
                periods,
            ),
        )
    }

    @Test
    fun `缺结束时间时按 45 分钟算`() {
        val c = WidgetSchedule.toCourse(
            session(startUnit = 99, endUnit = 99, startTime = "14:00", endTime = null),
            periods,
        )
        assertEquals(840, c!!.startMin)
        assertEquals(885, c.endMin)
    }

    @Test
    fun `一节课变成一行_带地点与时间段`() {
        val row = WidgetSchedule.toRow(course(600, 695, "大学物理", "南3 101"))
        assertEquals("大学物理", row.course.name)
        assertEquals("南3 101", row.place)
        assertEquals("10:00-11:35", row.timeRange)
    }

    // ── 状态判定 ─────────────────────────────────────────────────────────

    @Test
    fun `还没同步课表时不说今天没课`() {
        val st = WidgetSchedule.state(false, 5, emptyList(), 600)
        assertEquals(WidgetCourseState.NOT_SYNCED, st)
        assertEquals("还没有课表数据", WidgetSchedule.statusTitle(st))
        assertTrue(WidgetSchedule.statusMessage(st).contains("打开 App"))
    }

    @Test
    fun `有课表但不在教学周内算假期`() {
        val st = WidgetSchedule.state(true, null, listOf(course(600, 645)), 600)
        assertEquals(WidgetCourseState.VACATION, st)
        assertEquals("假期中", WidgetSchedule.statusTitle(st))
        assertEquals("期待新学期", WidgetSchedule.statusMessage(st))
    }

    @Test
    fun `今天没课与今天的课上完了都写无课程`() {
        // 用户口径：这两种状态对用户是同一件事（现在没有课），一律写「无课程」。
        // 以前分成「今天没有课程」「今日课程已结束」两句，还会跟一句「明天的课看右边」——
        // 而 2×2 根本没有右边，那是 4×2 的文案串味。
        val blank = WidgetSchedule.state(true, 5, emptyList(), 600)
        assertEquals(WidgetCourseState.NO_COURSE_TODAY, blank)
        assertEquals("无课程", WidgetSchedule.statusTitle(blank))

        val finished = WidgetSchedule.state(true, 5, listOf(course(480, 525)), 700)
        assertEquals(WidgetCourseState.FINISHED_TODAY, finished)
        assertEquals("无课程", WidgetSchedule.statusTitle(finished))
        assertEquals("", WidgetSchedule.statusMessage(finished))
    }

    @Test
    fun `还没下课的判定是左闭右开`() {
        val c = course(600, 645)
        assertTrue(WidgetSchedule.notEnded(c, 599))
        assertTrue(WidgetSchedule.notEnded(c, 600))
        assertTrue(WidgetSchedule.notEnded(c, 644))
        // 下课那一分钟算"已下课"，否则会显示出已经上完的课
        assertTrue(!WidgetSchedule.notEnded(c, 645))
    }

    @Test
    fun `今天还没下课的课要排序且排除已上完的`() {
        val today = listOf(course(840, 885), course(480, 525), course(600, 645))
        val remain = WidgetSchedule.remainingToday(today, 620)
        assertEquals(listOf("10:00", "14:00"), remain.map { it.startText })
    }

    // ── 4×2：左列今天、右列明天 ──────────────────────────────────────────

    private fun twoDay(
        hasSchedule: Boolean = true,
        week: Int? = 5,
        today: List<WidgetCourse>,
        tomorrow: List<WidgetCourse> = emptyList(),
        nowMin: Int,
        maxRows: Int = 2,
    ) = WidgetSchedule.twoDayPlan(
        hasSchedule = hasSchedule,
        week = week,
        todayDate = monday,
        tomorrowDate = tuesday,
        today = today,
        tomorrow = tomorrow,
        nowMin = nowMin,
        maxRows = maxRows,
    )

    @Test
    fun `4×2 左列是今天还没下课的_右边是明天全部`() {
        val p = twoDay(
            today = listOf(
                course(480, 525, "高等数学A", "南2 206"),
                course(600, 645, "大学物理", "南3 101"),
                course(840, 885, "线性代数", "南1 302"),
            ),
            tomorrow = listOf(
                course(480, 525, "编译原理实验", "中2414"),
                course(610, 650, "编译原理", "南4_103"),
            ),
            nowMin = 620,   // 正在上 10:00 那节
        )
        assertEquals("第 5 周", p.week)
        assertEquals("今天 9.28 周一", p.today.header)
        assertEquals("明天 9.29 周二", p.tomorrow.header)
        // 今天：已上完的不再列，正在上的那节留在最前
        assertEquals("剩余 2 节", p.today.footer)
        assertEquals(listOf("大学物理", "线性代数"), p.today.rows.map { it.course.name })
        assertEquals("", p.today.emptyText)
        // 明天：全部列出（不看钟点）
        assertEquals("共有 2 节", p.tomorrow.footer)
        assertEquals(listOf("编译原理实验", "编译原理"), p.tomorrow.rows.map { it.course.name })
    }

    @Test
    fun `4×2 每列最多显示 maxRows 行_footer 仍是总数`() {
        val five = (0 until 5).map { course(480 + it * 60, 520 + it * 60, "课$it") }
        val p = twoDay(today = five, tomorrow = five, nowMin = 400, maxRows = 2)
        assertEquals(2, p.today.rows.size)
        assertEquals(2, p.tomorrow.rows.size)
        // footer 报的是"还剩几节"，不是"显示了几行"
        assertEquals("剩余 5 节", p.today.footer)
        assertEquals("共有 5 节", p.tomorrow.footer)
    }

    @Test
    fun `4×2 某列没课就写无课程`() {
        val p = twoDay(today = listOf(course(480, 525)), tomorrow = emptyList(), nowMin = 700)
        assertEquals("无课程", p.today.emptyText)
        assertTrue(p.today.rows.isEmpty())
        assertEquals("", p.today.footer)
        assertEquals("无课程", p.tomorrow.emptyText)
    }

    @Test
    fun `4×2 假期整块盖住内容`() {
        val p = twoDay(
            week = null,
            today = listOf(course(600, 645)),
            tomorrow = listOf(course(600, 645)),
            nowMin = 600,
        )
        assertTrue(p.hasOverlay)
        assertEquals("假期中", p.overlayTitle)
        assertEquals("期待新学期", p.overlayMessage)
        assertEquals("", p.week)
        assertTrue(p.today.rows.isEmpty())
    }

    @Test
    fun `4×2 还没同步课表也整块盖住_不能假装没课`() {
        // 以前只有假期走整块状态，未同步会退化成两列各写一句「无课程」——
        // 那是假的：用户会以为课表是空的，而不是"还没同步"。
        val p = twoDay(hasSchedule = false, week = null, today = emptyList(), tomorrow = emptyList(), nowMin = 600)
        assertTrue(p.hasOverlay)
        assertEquals("还没有课表数据", p.overlayTitle)
        assertTrue(p.overlayMessage.contains("打开 App"))
        // 不是假期，所以不该写"期待新学期"
        assertTrue(!p.overlayMessage.contains("新学期"))
    }

    // ── 2×2：一节课大字 + 圆气泡 + 一行预告 ──────────────────────────────

    private fun nextClass(
        hasSchedule: Boolean = true,
        week: Int? = 5,
        today: List<WidgetCourse>,
        tomorrow: List<WidgetCourse> = emptyList(),
        nowMin: Int,
        previewRows: Int = 1,
    ) = WidgetSchedule.nextClassPlan(
        hasSchedule = hasSchedule,
        week = week,
        today = today,
        tomorrow = tomorrow,
        nowMin = nowMin,
        previewRows = previewRows,
    )

    @Test
    fun `2×2 今天还有课就显示下一节_带圆气泡与预告`() {
        val p = nextClass(
            today = listOf(course(600, 695, "大学物理", "南3 101"), course(840, 885, "线性代数")),
            tomorrow = listOf(course(480, 525, "编译原理实验")),
            nowMin = 500,
        )
        assertEquals("今天 · 第 5 周", p.header)
        assertEquals("大学物理", p.name)
        assertEquals("10:00-11:35", p.timeRange)
        assertEquals("南3 101", p.place)
        // 圆气泡 = 这节课以及之后还有几节（含本节）
        assertEquals(2, p.remaining)
        assertEquals("→ 14:00 线性代数", p.preview)
        assertTrue(p.hasCourse)
        assertTrue(!p.isStatus)
    }

    @Test
    fun `2×2 正在上的那节算下一节_且剩一节时气泡是 1`() {
        val p = nextClass(
            today = listOf(course(600, 695, "大学物理", "南3 101")),
            nowMin = 620,
        )
        assertEquals("大学物理", p.name)
        assertEquals(1, p.remaining)
        assertEquals("", p.preview)
    }

    @Test
    fun `2×2 今天没了但明天有_顶上明天的第一节`() {
        val p = nextClass(
            today = listOf(course(480, 525, "线性代数")),
            tomorrow = listOf(course(480, 525, "编译原理实验", "中2414"), course(610, 650, "编译原理")),
            nowMin = 700,
        )
        assertEquals("明日预告 · 第 5 周", p.header)
        assertEquals("编译原理实验", p.name)
        assertEquals(2, p.remaining)
        assertEquals("→ 10:10 编译原理", p.preview)
    }

    @Test
    fun `2×2 今明都没课就是一句无课程`() {
        val blank = nextClass(today = emptyList(), tomorrow = emptyList(), nowMin = 600)
        assertTrue(blank.isStatus)
        assertEquals("无课程", blank.statusTitle)
        assertEquals("", blank.statusMessage)
        assertTrue(!blank.hasCourse)

        val finished = nextClass(today = listOf(course(480, 525)), tomorrow = emptyList(), nowMin = 700)
        assertEquals("无课程", finished.statusTitle)
    }

    @Test
    fun `2×2 预告行数按盒子高度给_最多一行`() {
        // 这个组件的定位是"我下一节是什么"，预告多了就变成课表（那是 4×2 的事）
        assertEquals(1, WidgetSchedule.nextClassPreviews(195))
        assertEquals(1, WidgetSchedule.nextClassPreviews(0))
        assertEquals(0, WidgetSchedule.nextClassPreviews(80))

        val multi = nextClass(
            today = listOf(course(600, 645, "A"), course(700, 745, "B"), course(800, 845, "C")),
            nowMin = 500,
            previewRows = 2,
        )
        assertEquals("→ 11:40 B   → 13:20 C", multi.preview)
    }

    @Test
    fun `2×2 矮盒子先收地点_课名永远留着`() {
        assertEquals(WidgetSchedule.RowDetail.FULL, WidgetSchedule.nextClassDetail(195))
        assertEquals(WidgetSchedule.RowDetail.FULL, WidgetSchedule.nextClassDetail(78))
        assertEquals(WidgetSchedule.RowDetail.NAME, WidgetSchedule.nextClassDetail(77))
        assertEquals(WidgetSchedule.RowDetail.FULL, WidgetSchedule.nextClassDetail(0))
    }

    @Test
    fun `2×2 假期标记单独给_不靠文案去猜`() {
        val p = nextClass(week = null, today = listOf(course(600, 645)), nowMin = 600)
        assertTrue(p.isVacation)
        assertEquals("假期中", p.statusTitle)
        assertEquals("期待新学期", p.statusMessage)
    }

    @Test
    fun `2×2 还没同步课表时也给状态`() {
        val p = nextClass(hasSchedule = false, week = null, today = emptyList(), nowMin = 600)
        assertEquals("还没有课表数据", p.statusTitle)
        assertTrue(!p.isVacation)
    }

    // ── 版式容量（实测画布 342×195 / 163×195） ──────────────────────────

    @Test
    fun `4×2 每列两行`() {
        assertEquals(2, WidgetSchedule.columnRows(195))
        assertEquals(1, WidgetSchedule.columnRows(110))   // 老公式那种小盒子只放得下一行
        assertEquals(5, WidgetSchedule.columnRows(400))   // 拉很大也有上限
        assertEquals(2, WidgetSchedule.columnRows(0))     // 读不到尺寸时按实测值
    }

    @Test
    fun `行高按可用高度撑_但只温和撑且留余量`() {
        // 195dp、2 行：可用 = 195-66-1-10(余量) = 118 → 每行 59dp（自然 44 + 撑 15 → 封顶 56）
        assertEquals(58, WidgetSchedule.courseRowHeightDp(195, 2))
        // 只有 1 行时也封顶（不然是一个巨大的空框里飘一行字）
        assertEquals(58, WidgetSchedule.courseRowHeightDp(195, 1))
        // 盒子很小：不低于内容高度
        assertEquals(WidgetSchedule.COURSE_ROW_HEIGHT_DP, WidgetSchedule.courseRowHeightDp(110, 1))
        // 不知道盒子多大时不撑
        assertEquals(0, WidgetSchedule.courseRowHeightDp(0, 2))
    }

    @Test
    fun `电量块高把两半铺开_也留余量`() {
        // 195dp：可用 = 195-57-10 = 128 → 每块 64 → 封顶 67
        assertEquals(67, WidgetSchedule.blockHeightDp(195))
        // 盒子很矮时也至少给 48dp（不然数字会被挤扁）
        assertEquals(48, WidgetSchedule.blockHeightDp(100))
        // 不知道盒子多大时不撑
        assertEquals(0, WidgetSchedule.blockHeightDp(0))
    }

    @Test
    fun `窄到一定程度就不显示地点`() {
        assertEquals(WidgetSchedule.RowDetail.FULL, WidgetSchedule.rowDetail(148))
        assertEquals(WidgetSchedule.RowDetail.FULL, WidgetSchedule.rowDetail(120))
        assertEquals(WidgetSchedule.RowDetail.NAME, WidgetSchedule.rowDetail(119))
        assertEquals(WidgetSchedule.RowDetail.FULL, WidgetSchedule.rowDetail(0))
    }

    // ── 课程边界闹钟 ─────────────────────────────────────────────────────

    @Test
    fun `下一个边界取最近的上课或下课时刻`() {
        val today = listOf(course(600, 645), course(665, 710))
        val now = 1_000_000L
        assertEquals(now + 35 * 60_000L, WidgetSchedule.nextBoundaryMillis(today, 610, now))
        assertEquals(now + 15 * 60_000L, WidgetSchedule.nextBoundaryMillis(today, 650, now))
    }

    @Test
    fun `今天没有边界了就返回 null_交给系统的定时刷新`() {
        assertNull(WidgetSchedule.nextBoundaryMillis(listOf(course(600, 645)), 700, 1L))
        assertNull(WidgetSchedule.nextBoundaryMillis(emptyList(), 600, 1L))
    }

    @Test
    fun `异常作息不会定出很远的闹钟`() {
        val at = WidgetSchedule.nextBoundaryMillis(listOf(course(23 * 60, 23 * 60 + 45)), 60, 0L)
        assertEquals(6 * 60 * 60_000L, at)
    }
}
