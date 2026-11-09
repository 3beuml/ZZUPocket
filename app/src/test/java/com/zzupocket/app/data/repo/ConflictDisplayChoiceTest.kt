package com.zzupocket.app.data.repo

import com.zzupocket.app.data.db.DisplayOverrideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConflictDisplayChoiceTest {
    private fun row(week: String?, kind: String = ScheduleRepository.KIND_CONFLICT) = DisplayOverrideEntity(
        id = "194|conflict|key|$week", semesterId = 194, courseCode = "key", kind = kind,
        weekday = 2, startUnit = 1, weeks = week, value = "selected", updatedAt = 0,
    )

    @Test fun `冲突选择只读取自己的类型和明确周号`() {
        val choices = ScheduleRepository.readConflictDisplayChoices(
            listOf(row("7"), row("8"), row("7", "name"), row(null), row("1,3"), row("0")),
        )
        assertEquals(listOf(7, 8), choices.map { it.weekIndex })
        assertEquals(listOf("key", "key"), choices.map { it.conflictKey })
    }

    @Test fun `同课程同节次理论和实验不会入库互相覆盖`() {
        val theory = ScheduleRepository.sessionIdOf(194, "A", 7, 2, 1, "2026-10-20")
        val experiment = ScheduleRepository.sessionIdOf(194, "A", 7, 2, 1, "2026-10-20", experiment = true)
        assertNotEquals(theory, experiment)
        assertEquals("194:A:7:2:1:2026-10-20", theory)
    }
}
