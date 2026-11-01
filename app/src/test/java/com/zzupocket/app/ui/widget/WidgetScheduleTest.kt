package com.zzupocket.app.ui.widget

import com.zzupocket.app.domain.Period
import com.zzupocket.app.domain.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // 实测修正后画布 165dp → 2 行（195dp 是系统多报的值，见 Widgets.sizeDp）
        assertEquals(2, WidgetSchedule.columnRows(165))
        assertEquals(1, WidgetSchedule.columnRows(110))   // 老公式那种小盒子只放得下一行
        assertEquals(5, WidgetSchedule.columnRows(400))   // 拉很大也有上限
        assertEquals(2, WidgetSchedule.columnRows(0))     // 读不到尺寸时按实测值
    }

    @Test
    fun `课名换行估算_CJK按整字_ASCII按半字`() {
        // 单列实测约 148dp，可用文字宽 ≈ 137dp；14 个汉字 12dp 字号 ≈ 168dp → 两行
        assertEquals(2, WidgetSchedule.nameLineCount("大学生职业生涯规划与就业指导", 148))
        // 4 个汉字 ≈ 48dp → 一行
        assertEquals(1, WidgetSchedule.nameLineCount("编译原理", 148))
        // 列宽未知按一行（旧口径兜底）
        assertEquals(1, WidgetSchedule.nameLineCount("大学生职业生涯规划与就业指导", 0))
        // 纯 ASCII（实验课英文名）按 0.55em
        assertEquals(1, WidgetSchedule.nameLineCount("College English", 148))
    }

    @Test
    fun `行自然高随课名行数抬高`() {
        assertEquals(44, WidgetSchedule.rowNaturalHeightDp(1))
        assertEquals(60, WidgetSchedule.rowNaturalHeightDp(2))
    }

    @Test
    fun `165dp 画布两行课都装得下_含两行课名`() {
        // 2026-11-01 的修复目标：一节单行课名(44) + 一节两行课名(60) + 分隔线 1
        // = 105 ≤ 可用 165-58(账本固定开销，卡片内边距已省到 10dp) = 107 → 两行都显示
        assertEquals(2, WidgetSchedule.visibleRowCount(165, listOf(44, 60)))
        // 真装不下的矮盒子才少画：110dp 可用 52，第二行 60 放不下 → 只画第一行
        assertEquals(1, WidgetSchedule.visibleRowCount(110, listOf(44, 60)))
        // 至少画一行
        assertEquals(1, WidgetSchedule.visibleRowCount(60, listOf(44, 44)))
        // 不知道高度时不裁（旧口径）
        assertEquals(2, WidgetSchedule.visibleRowCount(0, listOf(44, 44)))
    }

    @Test
    fun `剩余空间按行均摊_且永不低于自然高`() {
        // 165dp 两行单行课名：可用 107，需要 44+44+1=89 → 剩 18 → 每行 +9
        assertEquals(listOf(53, 53), WidgetSchedule.rowStretchTargetsDp(165, listOf(44, 44)))
        // 大盒子里只温和撑（+12 封顶）
        assertEquals(listOf(56, 56), WidgetSchedule.rowStretchTargetsDp(195, listOf(44, 44)))
        // 有两行课名的行：需要 44+60+1=105，剩 2 → 每行 +1
        assertEquals(listOf(45, 61), WidgetSchedule.rowStretchTargetsDp(165, listOf(44, 60)))
        // 不知道高度时不撑
        assertEquals(listOf(0, 0), WidgetSchedule.rowStretchTargetsDp(0, listOf(44, 44)))
    }

    @Test
    fun `4×2 换行感知装柜_两节课都显示且不被裁`() {
        // 场景复刻 2026-11-01 的截图：明天两节课，第二节课名 14 字（两行）。
        // 修复后（卡片内边距 10 + 行内边距 5）账本判定两行都装得下 → 都显示。
        val tomorrow = listOf(
            course(480, 525, "编译原理", "南4 103"),
            course(600, 645, "大学生职业生涯规划与就业指导", "北1 103"),
        )
        val p = WidgetSchedule.twoDayPlan(
            hasSchedule = true,
            week = 8,
            todayDate = monday,
            tomorrowDate = tuesday,
            today = emptyList(),
            tomorrow = tomorrow,
            nowMin = 400,
            maxRows = 2,
            columnWidthDp = 148,
            columnHeightDp = 165,
        )
        assertEquals(2, p.tomorrow.rows.size)
        assertEquals(listOf("编译原理", "大学生职业生涯规划与就业指导"), p.tomorrow.rows.map { it.course.name })
        assertEquals("共有 2 节", p.tomorrow.footer)
        // 真正装不下（更矮的盒子）才退回少画一行，且 footer 不隐瞒总数
        val tight = WidgetSchedule.twoDayPlan(
            hasSchedule = true,
            week = 8,
            todayDate = monday,
            tomorrowDate = tuesday,
            today = emptyList(),
            tomorrow = tomorrow,
            nowMin = 400,
            maxRows = 2,
            columnWidthDp = 148,
            columnHeightDp = 110,
        )
        assertEquals(1, tight.tomorrow.rows.size)
        assertEquals("共有 2 节", tight.tomorrow.footer)
    }

    @Test
    fun `字号按画布高度分档`() {
        // 标准档（实测 4×2 高 195dp；读不到尺寸按标准）
        assertEquals(13 to 12, WidgetSchedule.rowFontSizes(195))
        assertEquals(13 to 12, WidgetSchedule.rowFontSizes(0))
        // 矮盒子（别的启动器 4×2 槽位更矮）用紧凑档防裁字
        assertEquals(12 to 11, WidgetSchedule.rowFontSizes(160))
        assertEquals(17, WidgetSchedule.bigNameSizeDp(195))
        assertEquals(15, WidgetSchedule.bigNameSizeDp(160))
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

    // ── 4×5 周课表 ──────────────────────────────────────────────────────

    /** 带节次的课（周课表按 startUnit 对齐） */
    private fun unitCourse(
        startUnit: Int,
        endUnit: Int,
        name: String = "课",
        code: String = "C1",
    ) = WidgetCourse(
        code = code, name = name, place = "南2 206",
        startMin = 480 + startUnit * 60, endMin = 480 + endUnit * 60,
        startUnit = startUnit, endUnit = endUnit,
    )

    private val wednesday = java.time.LocalDate.of(2026, 11, 4)   // 周三

    @Test
    fun `周课表_列决策_周末有课才加列`() {
        val plan = WidgetSchedule.weekPlan(
            hasSchedule = true,
            week = 8,
            coursesByDay = mapOf(
                1 to listOf(unitCourse(1, 2)),
                3 to listOf(unitCourse(3, 4)),
                6 to listOf(unitCourse(5, 6, "周六课")),
            ),
            today = wednesday,
        )
        // 周一..周五恒显示，周日没课不加，周六有课要加 → 6 列
        assertEquals(6, plan.columns.size)
        assertEquals(listOf("一 11.2", "二 11.3", "今 11.4", "四 11.5", "五 11.6", "六 11.7"), plan.columns.map { it.header })
        assertTrue(plan.columns[2].isToday)
        assertFalse(plan.columns[0].isToday)
        // 第 8 周
        assertEquals("第 8 周", plan.week)
    }

    @Test
    fun `周课表_节次槽按本周实际出现的起始节次收敛`() {
        val plan = WidgetSchedule.weekPlan(
            hasSchedule = true,
            week = 8,
            // 1-2 和 3-4 两个大节 → 全周只有 2 个槽（5/6/7…节没课不浪费行高）
            coursesByDay = mapOf(
                1 to listOf(unitCourse(1, 2, "上午课")),
                2 to listOf(unitCourse(3, 4, "下午课")),
            ),
            today = wednesday,
        )
        assertEquals(2, plan.slotCount)
        val monday = plan.columns[0].cells
        assertEquals("上午课", monday[0]?.name)
        assertEquals(1, monday[0]?.slotSpan)     // 1-2 只覆盖槽 1
        assertNull(monday[1])                     // 周一第 2 槽（3 节）没课
        val tuesday = plan.columns[1].cells
        assertNull(tuesday[0])                    // 周二第 1 槽（1 节）没课
        assertEquals("下午课", tuesday[1]?.name)
        assertEquals(1, tuesday[1]?.slotSpan)     // 3-4 只覆盖槽 3
    }

    @Test
    fun `周课表_跨节的课占多行_被盖住的槽留空`() {
        val plan = WidgetSchedule.weekPlan(
            hasSchedule = true,
            week = 8,
            coursesByDay = mapOf(
                // 1-4 连上；另一天 3-4 和 5-6
                1 to listOf(unitCourse(1, 4, "连堂课")),
                2 to listOf(unitCourse(3, 4), unitCourse(5, 6)),
            ),
            today = wednesday,
        )
        // 槽 = [1, 3, 5]
        assertEquals(3, plan.slotCount)
        val monday = plan.columns[0].cells
        assertEquals("连堂课", monday[0]?.name)
        assertEquals(2, monday[0]?.slotSpan)   // 1-4 覆盖槽 1、3（5 节不在范围内）
        assertNull(monday[1])                   // 被连堂格覆盖
        assertNull(monday[2])                   // 5 节的槽，连堂课没到、周一也没别的课
        val tuesday = plan.columns[1].cells
        assertNull(tuesday[0])
        assertEquals(1, tuesday[1]?.slotSpan)   // 3-4 占槽 3 一格
        assertEquals(1, tuesday[2]?.slotSpan)   // 5-6 占槽 5 一格
    }

    @Test
    fun `周课表_同列同槽撞车先到先得`() {
        val plan = WidgetSchedule.weekPlan(
            hasSchedule = true,
            week = 8,
            coursesByDay = mapOf(
                1 to listOf(unitCourse(1, 2, "先到的课", "A"), unitCourse(1, 2, "后到的课", "B")),
            ),
            today = wednesday,
        )
        assertEquals("先到的课", plan.columns[0].cells[0]?.name)
    }

    @Test
    fun `周课表_未同步与假期走整块状态`() {
        val p1 = WidgetSchedule.weekPlan(hasSchedule = false, week = 8, coursesByDay = emptyMap(), today = wednesday)
        assertEquals("还没有课表数据", p1.overlayTitle)
        val p2 = WidgetSchedule.weekPlan(hasSchedule = true, week = null, coursesByDay = emptyMap(), today = wednesday)
        assertEquals("假期中", p2.overlayTitle)
        assertTrue(p2.hasOverlay)
    }

    @Test
    fun `周课表_行高与可见性分档`() {
        // 4×5 实测 ~443dp、5 槽：可用 (443-54)/5 ≈ 77dp
        assertEquals(77, WidgetSchedule.weekRowHeightDp(443, 5))
        // 上限 120
        assertEquals(120, WidgetSchedule.weekRowHeightDp(900, 2))
        // 不知道尺寸按 0（渲染走自然高）
        assertEquals(0, WidgetSchedule.weekRowHeightDp(0, 5))
        // 字号：单列 62dp 用 11dp，52 用 10，45 用 9
        assertEquals(11, WidgetSchedule.weekNameSizeDp(62))
        assertEquals(10, WidgetSchedule.weekNameSizeDp(52))
        assertEquals(9, WidgetSchedule.weekNameSizeDp(45))
        // 宽列高行：名字+地点+时间全显示；矮行只留时间
        assertTrue(WidgetSchedule.weekShowPlace(62, 77))
        assertTrue(WidgetSchedule.weekShowTime(77))
        assertFalse(WidgetSchedule.weekShowPlace(62, 40))
        assertTrue(WidgetSchedule.weekShowTime(40))
        assertFalse(WidgetSchedule.weekShowTime(30))
        // 名字行数：77dp 高、显示地点+时间后还能 3 行封顶
        assertEquals(3, WidgetSchedule.weekNameMaxLines(62, 77))
        assertEquals(1, WidgetSchedule.weekNameMaxLines(62, 30))
    }
}
