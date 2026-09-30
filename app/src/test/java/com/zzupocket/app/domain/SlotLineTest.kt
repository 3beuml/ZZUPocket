package com.zzupocket.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SlotLineTest {

    private fun session(
        week: Int,
        weekday: Int,
        start: Int,
        end: Int,
        room: String? = "南3 107",
        teacher: String? = "谭新莲",
    ) = Session(
        id = "$week-$weekday-$start",
        semesterId = 1,
        source = "eas",
        courseCode = "C1",
        courseName = "计算机网络",
        weekIndex = week,
        weekday = weekday,
        startUnit = start,
        endUnit = end,
        date = null,
        startTime = null,
        endTime = null,
        teacherName = teacher,
        roomText = room,
        campusText = null,
        color = null,
    )

    @Test
    fun `整学期同一时段且地点教师相同 —— 只产出一行`() {
        // 这正是之前被列成 16 行废话的情况
        val lines = (1..16).map { session(week = it, weekday = 3, start = 5, end = 6) }.toSlotLines()
        assertEquals(1, lines.size)
        assertEquals(listOf(3), listOf(lines[0].weekday))
        assertEquals((1..16).toList(), lines[0].weeks)
        assertEquals("南3 107", lines[0].room)
    }

    @Test
    fun `同一时段中途换教室 —— 拆成两行，各自带周次`() {
        val sessions = (1..8).map { session(it, 3, 5, 6, room = "南3 107") } +
            (9..16).map { session(it, 3, 5, 6, room = "南1 203") }
        val lines = sessions.toSlotLines()
        assertEquals(2, lines.size)
        assertEquals("南3 107", lines[0].room)
        assertEquals((1..8).toList(), lines[0].weeks)
        assertEquals("南1 203", lines[1].room)
        assertEquals((9..16).toList(), lines[1].weeks)
    }

    @Test
    fun `多个时段各自归并，并按星期与节次排序`() {
        val sessions = (1..4).map { session(it, 4, 5, 6) } +      // 周四 5-6
            (1..4).map { session(it, 2, 3, 4) }                   // 周二 3-4
        val lines = sessions.toSlotLines()
        assertEquals(2, lines.size)
        assertEquals(2, lines[0].weekday)   // 周二在前
        assertEquals(4, lines[1].weekday)
        assertEquals((1..4).toList(), lines[0].weeks)
        assertEquals((1..4).toList(), lines[1].weeks)
    }

    @Test
    fun `换老师也会拆开`() {
        val sessions = (1..4).map { session(it, 3, 5, 6, teacher = "甲") } +
            (5..8).map { session(it, 3, 5, 6, teacher = "乙") }
        assertEquals(2, sessions.toSlotLines().size)
    }

    @Test
    fun `空列表不崩`() {
        assertEquals(0, emptyList<Session>().toSlotLines().size)
    }
}
