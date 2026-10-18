package com.zzupocket.app.data.gms

import com.zzupocket.app.domain.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GmsScheduleMapperTest {

    private fun entry(
        week: Int,
        day: Int,
        period: Int,
        name: String = "高等数学",
        code: String? = "123456_01",
        teacher: String? = "张老师",
        room: String? = "北1201",
    ) = GmsClient.WeekEntry(
        weekIndex = week,
        weekday = day,
        period = period,
        courseName = name,
        courseCode = code,
        className = null,
        teacher = teacher,
        room = room,
    )

    @Test
    fun `连续节次合并成一个块`() {
        val sessions = GmsScheduleMapper.toSessions(
            listOf(entry(7, 1, 1), entry(7, 1, 2)),
        )
        assertEquals(1, sessions.size)
        assertEquals(1, sessions[0].startUnit)
        assertEquals(2, sessions[0].endUnit)
        assertEquals("高等数学", sessions[0].courseName)
        assertEquals("张老师", sessions[0].teacherName)
        assertEquals("北1201", sessions[0].roomText)
    }

    @Test
    fun `中间断开的节次不会被合并`() {
        val sessions = GmsScheduleMapper.toSessions(
            listOf(entry(7, 1, 1), entry(7, 1, 3)),
        )
        assertEquals(2, sessions.size)
        assertEquals(listOf(1, 3), sessions.map { it.startUnit })
    }

    @Test
    fun `不同课程不会被合并`() {
        val sessions = GmsScheduleMapper.toSessions(
            listOf(
                entry(7, 1, 1, name = "高等数学", code = "111_01"),
                entry(7, 1, 2, name = "数值分析", code = "222_01"),
            ),
        )
        assertEquals(2, sessions.size)
        assertEquals(setOf("111_01", "222_01"), sessions.map { it.courseCode }.toSet())
    }

    @Test
    fun `没有课程号的课用课程名当 key`() {
        val sessions = GmsScheduleMapper.toSessions(
            listOf(entry(7, 2, 5, name = "政治课", code = null)),
        )
        assertEquals("政治课", sessions.single().courseCode)
        val course = GmsScheduleMapper.toCourses(sessions).single()
        assertEquals("政治课", course.code)
        assertEquals("政治课", course.nameZh)
    }

    @Test
    fun `同一门课的多个时段聚成一门课`() {
        val sessions = GmsScheduleMapper.toSessions(
            listOf(
                entry(7, 1, 1), entry(7, 1, 2),
                entry(7, 3, 5),
            ),
        )
        val course = GmsScheduleMapper.toCourses(sessions).single()
        assertEquals("高等数学", course.nameZh)
        assertEquals(2, course.sessions.size)
        assertEquals(listOf("张老师"), course.teachers)
        assertEquals(listOf("北1201"), course.roomTexts)
    }

    @Test
    fun `有节次时间时回填起止时间`() {
        val periods = listOf(
            Period(1, "第1节", 800, 845, "MORNING", 1),
            Period(2, "第2节", 855, 940, "MORNING", 2),
        )
        val session = GmsScheduleMapper.toSessions(
            listOf(entry(7, 1, 1), entry(7, 1, 2)),
            periods,
        ).single()
        assertEquals("08:00", session.startTime)
        assertEquals("09:40", session.endTime)
    }

    @Test
    fun `学期周次和当前教学周来自本周课表对齐`() {
        val today = LocalDate.of(2026, 10, 18)
        val entries = (10..17).flatMap { week ->
            buildList {
                add(entry(week, 1, 1))
                if (week == 12) add(entry(week, 2, 3, name = "数值分析", code = "222_01"))
            }
        }
        val current = listOf(
            GradLesson(1, 1, "高等数学"),
            GradLesson(2, 3, "数值分析"),
        )
        val semester = GmsScheduleMapper.toSemester(
            GmsClient.TermDto(value = "20261", text = "2026-2027-1", selected = true),
            entries,
            current,
            today,
        )
        assertEquals(10, semester.firstWeek)
        assertEquals(17, semester.lastWeek)
        assertEquals((10..17).toList(), semester.weekIndices)
        assertEquals(GmsWeekAlign.weekOneMonday(today, 12), semester.startDate)
        assertEquals(12, GmsWeekAlign.currentWeekIndex(entries, current))
    }

    @Test
    fun `官方作息表覆盖 10 节课且不含辅助项`() {
        val periods = GmsScheduleMapper.defaultPeriods()
        assertEquals(10, periods.size)
        assertEquals("08:00", periods[0].startText)
        assertEquals("08:45", periods[0].endText)
        assertEquals("19:55", periods[9].startText)
        assertEquals("20:40", periods[9].endText)
    }

    @Test
    fun `本周课表为空时学期仍然可以生成`() {
        val semester = GmsScheduleMapper.toSemester(
            GmsClient.TermDto(value = "20261", text = "研究生课表"),
            listOf(entry(3, 1, 1)),
            emptyList(),
            LocalDate.of(2026, 10, 18),
        )
        assertEquals(listOf(3), semester.weekIndices)
        assertTrue(semester.startDate == null)
    }
}
