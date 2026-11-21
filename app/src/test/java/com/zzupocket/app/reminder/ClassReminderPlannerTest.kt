package com.zzupocket.app.reminder

import com.zzupocket.app.ui.today.TimelineEntry
import com.zzupocket.app.ui.today.TimelineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 课程提醒纯计算层的回归测试：挑哪一节、什么时刻提醒、哪些情况不提醒。 */
class ClassReminderPlannerTest {

    private val monday = LocalDate.of(2026, 11, 23)

    private fun course(
        start: Int,
        end: Int,
        name: String = "高等数学",
        conflicts: List<String> = emptyList(),
    ) = TimelineEntry(
        kind = TimelineKind.COURSE,
        startMin = start,
        endMin = end,
        title = name,
        subtitle = "10:00 · 南2 206",
        conflictCourseNames = conflicts,
    )

    @Test
    fun `挑下一节课且触发时刻等于开始减提前量`() {
        val plan = ClassReminderPlanner.planNext(
            today = monday,
            nowMillis = epochOf(monday, 8 * 60),
            leadMinutes = 15,
            entriesOn = { listOf(course(10 * 60, 10 * 60 + 45)) },
        )!!
        assertEquals("高等数学", plan.title)
        assertEquals(epochOf(monday, 10 * 60), plan.startAt)
        assertEquals(epochOf(monday, 10 * 60) - 15 * 60_000L, plan.notifyAt)
        assertFalse(plan.isConflict)
    }

    @Test
    fun `正在上的课跳过去找下一节`() {
        // 08:00-09:00 正在进行，10:00 那节才是"下一节"
        val plan = ClassReminderPlanner.planNext(
            today = monday,
            nowMillis = epochOf(monday, 8 * 60 + 30),
            leadMinutes = 15,
            entriesOn = {
                listOf(
                    course(8 * 60, 9 * 60, "正在上"),
                    course(10 * 60, 10 * 60 + 45, "下一节"),
                )
            },
        )!!
        assertEquals("下一节", plan.title)
        assertEquals(epochOf(monday, 10 * 60), plan.startAt)
    }

    @Test
    fun `提前窗口已过但课还没开始时仍返回该课`() {
        // 09:55 看 10:00 的课、提前 15 分钟 → notifyAt=09:45 已在过去；
        // planner 只看 startAt>now，仍返回，由 arm() 立即触发（不能因窗口过了就静默丢掉这节）。
        val now = epochOf(monday, 9 * 60 + 55)
        val plan = ClassReminderPlanner.planNext(
            today = monday,
            nowMillis = now,
            leadMinutes = 15,
            entriesOn = { listOf(course(10 * 60, 10 * 60 + 45)) },
        )!!
        assertEquals(epochOf(monday, 10 * 60), plan.startAt)
        assertTrue(plan.notifyAt < now)
    }

    @Test
    fun `excludeKey 命中时顺延到下一节`() {
        val firstStart = epochOf(monday, 10 * 60)
        val firstKey = "$firstStart|第一节"
        val plan = ClassReminderPlanner.planNext(
            today = monday,
            nowMillis = epochOf(monday, 8 * 60),
            leadMinutes = 15,
            entriesOn = {
                listOf(
                    course(10 * 60, 10 * 60 + 45, "第一节"),
                    course(11 * 60, 11 * 60 + 45, "第二节"),
                )
            },
            excludeKey = firstKey,
        )!!
        assertEquals("第二节", plan.title)
        assertEquals(epochOf(monday, 11 * 60), plan.startAt)
    }

    @Test
    fun `跨周末从周五找到下周一`() {
        val friday = LocalDate.of(2026, 11, 27)
        val nextMonday = friday.plusDays(3)
        val plan = ClassReminderPlanner.planNext(
            today = friday,
            nowMillis = epochOf(friday, 18 * 60),
            leadMinutes = 15,
            entriesOn = { date ->
                if (date == nextMonday) listOf(course(10 * 60, 10 * 60 + 45, "周一第一节")) else emptyList()
            },
        )!!
        assertEquals("周一第一节", plan.title)
        assertEquals(epochOf(nextMonday, 10 * 60), plan.startAt)
    }

    @Test
    fun `冲突占位按课程名集合生成去重键并标记冲突`() {
        val plan = ClassReminderPlanner.planNext(
            today = monday,
            nowMillis = epochOf(monday, 8 * 60),
            leadMinutes = 15,
            entriesOn = { listOf(course(10 * 60, 10 * 60 + 45, "课程冲突", listOf("高等数学", "线性代数"))) },
        )!!
        assertTrue(plan.isConflict)
        val start = epochOf(monday, 10 * 60)
        // 课程名按排序后的集合拼接：与输入顺序无关，保证同一冲突无论从哪天算起键都一致
        val sortedNames = listOf("高等数学", "线性代数").sorted().joinToString("/")
        assertEquals("$start|conflict:$sortedNames", plan.key)
    }

    @Test
    fun `十四天都没有课返回空`() {
        assertNull(
            ClassReminderPlanner.planNext(
                today = monday,
                nowMillis = epochOf(monday, 8 * 60),
                leadMinutes = 15,
                entriesOn = { emptyList() },
            )
        )
    }
}
