package com.zzupocket.app.ui.today

import com.zzupocket.app.data.gms.GmsScheduleMapper
import com.zzupocket.app.domain.ConflictDisplayChoice
import com.zzupocket.app.domain.ScheduleBlock
import com.zzupocket.app.domain.Session
import com.zzupocket.app.domain.conflictDisplayKey
import com.zzupocket.app.domain.scheduleBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayCourseDisplayTest {
    private val periods = GmsScheduleMapper.defaultPeriods()

    private fun session(code: String, name: String = code, start: Int = 9, end: Int = 10, week: Int = 7) = Session(
        id = "$code-$week-$start", semesterId = 194, source = "eas", courseCode = code, courseName = name,
        weekIndex = week, weekday = 2, startUnit = start, endUnit = end, date = null,
        startTime = "19:00", endTime = "20:40", teacherName = null, roomText = "教学楼101", campusText = null, color = null,
    )

    private fun reportedPair(week: Int = 7) = listOf(
        session("drawing", "工程制图", week = week), session("exploration", "探索人体的奥秘", week = week),
    )

    @Test fun ordinaryCourseRetainsItsTitlePlaceAndTimes() {
        val row = TodayTimeline.courseEntries(scheduleBlocks(reportedPair().take(1)), periods).single()
        assertEquals("工程制图", row.title)
        assertEquals("教学楼101", row.subtitle)
        assertEquals(1140, row.startMin)
        assertEquals(1240, row.endMin)
        assertFalse(row.hasUnresolvedConflict)
    }

    @Test fun reportedPairBecomesOneUnresolvedEntryWithoutGuessingAttendance() {
        val rows = TodayTimeline.courseEntries(scheduleBlocks(reportedPair()), periods)
        val row = rows.single()
        assertEquals("课程冲突", row.title)
        assertEquals(listOf("工程制图", "探索人体的奥秘"), row.conflictCourseNames)
        assertTrue(TodayTimeline.nextCourseHint(rows, 1130)!!.contains("有课程冲突"))
        assertEquals("当前时段有课程冲突 · 点按课表查看", TodayTimeline.nextCourseHint(rows, 1170))
        assertEquals("今天的课已上完", TodayTimeline.nextCourseHint(rows, 1240))
    }

    @Test fun selectedCourseAppliesOnlyToChosenWeek() {
        val sessions = (7..9).flatMap { reportedPair(it) }
        val block = scheduleBlocks(sessions).first()
        val choice = ConflictDisplayChoice(194, block.conflictKey, 8, block.sessions.last().conflictDisplayKey())
        val rows = TodayTimeline.courseEntries(scheduleBlocks(sessions, listOf(choice)), periods)
        assertEquals(listOf("课程冲突", "探索人体的奥秘", "课程冲突"), rows.map { it.title })
        assertFalse(rows[1].hasUnresolvedConflict)
        assertEquals("正在上《探索人体的奥秘》· 还有 1 小时 10 分下课",
            TodayTimeline.nextCourseHint(listOf(rows[1]), 1170))
    }

    @Test fun partialOverlapUsesActualPeriodBoundariesAndMergesResolvedFragments() {
        val sessions = listOf(session("A", start = 1, end = 4), session("B", start = 3, end = 6))
        val blocks = scheduleBlocks(sessions)
        val unresolved = TodayTimeline.courseEntries(blocks, periods)
        assertEquals(listOf("A", "课程冲突", "B"), unresolved.map { it.title })
        assertEquals(periods[2].startText, TodayTimeline.clock(unresolved[1].startMin))
        assertEquals(periods[3].endText, TodayTimeline.clock(unresolved[1].endMin))
        val conflict = blocks.first { it.isConflict }
        val choice = ConflictDisplayChoice(194, conflict.conflictKey, 7, sessions.first().conflictDisplayKey())
        val resolved = TodayTimeline.courseEntries(scheduleBlocks(sessions, listOf(choice)), periods)
        assertEquals(listOf("A", "B"), resolved.map { it.title })
        assertEquals(periods[0].startText, TodayTimeline.clock(resolved[0].startMin))
        assertEquals(periods[3].endText, TodayTimeline.clock(resolved[0].endMin))
        assertEquals(periods[4].startText, TodayTimeline.clock(resolved[1].startMin))
    }

    @Test fun newConflictMemberReturnsToUnresolvedEntry() {
        val sessions = reportedPair()
        val block = scheduleBlocks(sessions).single()
        val choice = ConflictDisplayChoice(194, block.conflictKey, 7, sessions.last().conflictDisplayKey())
        val row = TodayTimeline.courseEntries(scheduleBlocks(sessions + session("new"), listOf(choice)), periods).single()
        assertTrue(row.hasUnresolvedConflict)
        assertEquals(3, row.conflictCourseNames.size)
    }

    @Test fun dateOnlyMakeupAndTimesWithoutPeriodCacheRemainVisible() {
        val session = session("makeup").copy(weekIndex = 0, weekday = 0, startUnit = 0, endUnit = 0)
        val row = TodayTimeline.courseEntries(listOf(ScheduleBlock(0, 0, listOf(session))), emptyList()).single()
        assertEquals("makeup", row.title)
        assertEquals(1140, row.startMin)
        assertEquals(1240, row.endMin)
    }

    @Test fun ordinaryUpcomingHintIsUnchanged() {
        val rows = TodayTimeline.courseEntries(scheduleBlocks(reportedPair().take(1)), periods)
        assertEquals("下一节 19:00《工程制图》· 10 分钟后", TodayTimeline.nextCourseHint(rows, 1130))
        assertEquals(null, TodayTimeline.nextCourseHint(emptyList(), 1130))
    }
}
