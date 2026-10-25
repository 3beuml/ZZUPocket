package com.zzupocket.app.ui.schedule

import com.zzupocket.app.domain.Session
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 编辑显示对话框的时段归并（issue #6 单双周场景）：
 * 同一时段教室相同 → 一条覆盖管全部周；教室不同（单周 302、双周 405）→ 按教室拆组，
 * 键带各自的周次，编辑互不串扰。
 */
class RoomEditSlotsTest {

    private fun session(
        weekday: Int,
        startUnit: Int,
        room: String?,
        week: Int,
        code: String = "154014",
    ) = Session(
        id = "s-$code-$weekday-$startUnit-$week-$room",
        semesterId = 194,
        source = "eas",
        courseCode = code,
        courseName = "网球",
        weekIndex = week,
        weekday = weekday,
        startUnit = startUnit,
        endUnit = startUnit + 1,
        date = null,
        startTime = null,
        endTime = null,
        teacherName = null,
        roomText = room,
        campusText = null,
        color = null,
    )

    @Test
    fun `同时段同教室合成一组管全部周`() {
        val slots = roomEditSlots(
            listOf(
                session(2, 1, "中2414", 1),
                session(2, 1, "中2414", 2),
                session(2, 1, "中2414", 3),
            ),
        )
        assertEquals(1, slots.size)
        val s = slots.single()
        assertEquals(true, s.wholeSlot)
        assertEquals(listOf(1, 2, 3), s.weeks)
        assertEquals("2|1", s.editKey) // 不带周次 = 全部周
        assertEquals("中2414", s.room)
    }

    @Test
    fun `单双周换教室按时段教室拆组`() {
        val slots = roomEditSlots(
            listOf(
                session(3, 1, "中3 302", 7),
                session(3, 1, "中3 405", 8),
                session(3, 1, "中3 302", 9), // 单周教室跨多个周次，要合回同一组
            ),
        )
        assertEquals(2, slots.size)
        val odd = slots.first { it.room == "中3 302" }
        val even = slots.first { it.room == "中3 405" }
        assertEquals(false, odd.wholeSlot)
        assertEquals(listOf(7, 9), odd.weeks)
        assertEquals("3|1|7,9", odd.editKey)
        assertEquals(listOf(8), even.weeks)
        assertEquals("3|1|8", even.editKey)
    }

    @Test
    fun `不同时段互不合并`() {
        val slots = roomEditSlots(
            listOf(
                session(2, 1, "中2414", 1),
                session(4, 1, "南1_206", 1),
            ),
        )
        assertEquals(2, slots.size)
        assertEquals(listOf("2|1", "4|1"), slots.map { it.editKey })
    }

    @Test
    fun `initialValue 依次回退到全部周覆盖和教务原值`() {
        val odd = RoomEditSlot(weekday = 3, startUnit = 1, endUnit = 2, room = "中3 302", weeks = listOf(7, 9), wholeSlot = false)
        // 精确周命中
        assertEquals("单周改", odd.initialValue(mapOf("3|1|7,9" to "单周改", "3|1" to "全部周改")))
        // 精确周没有 → 全部周覆盖兜底（apply 语义相同）
        assertEquals("全部周改", odd.initialValue(mapOf("3|1" to "全部周改")))
        // 都没有 → 教务原值
        assertEquals("中3 302", odd.initialValue(emptyMap()))
        assertEquals("中3 302", odd.initialValue(mapOf("9|9|9" to "无关")))
    }
}
