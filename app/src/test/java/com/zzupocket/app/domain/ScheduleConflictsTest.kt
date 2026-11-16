package com.zzupocket.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ScheduleConflictsTest {
    private fun session(code: String, start: Int = 1, end: Int = 2, week: Int = 7, day: Int = 2) = Session(
        id = "$code-$week-$day-$start-$end", semesterId = 194, source = "eas",
        courseCode = code, courseName = "课程$code", weekIndex = week, weekday = day,
        startUnit = start, endUnit = end, date = null, startTime = null, endTime = null,
        teacherName = null, roomText = null, campusText = null, color = null,
    )

    @Test fun `普通跨节课程保持整块 相邻课程不算冲突`() {
        val blocks = scheduleBlocks(listOf(session("A"), session("B", 3, 4)))
        assertEquals(listOf(1..2, 3..4), blocks.map { it.startUnit..it.endUnit })
        assertTrue(blocks.none { it.isConflict })
    }

    @Test fun `相同节次首次显示冲突占位并保留全部课程`() {
        val block = scheduleBlocks(listOf(session("A"), session("B"))).single()
        assertTrue(block.showPlaceholder)
        assertEquals(setOf("A", "B"), block.sessions.map { it.courseCode }.toSet())
    }

    @Test fun `本科周二9至10节工程制图与探索人体的奥秘都保留且可选择`() {
        // 来自报告的课程名/星期/节次；周号和课程代码仅为测试占位。
        val drawing = session("drawing", 9, 10, day = 2).copy(courseName = "工程制图")
        val exploration = session("exploration", 9, 10, day = 2).copy(courseName = "探索人体的奥秘")
        val sessions = (7..9).flatMap { week ->
            listOf(drawing.copy(id = "drawing-$week", weekIndex = week), exploration.copy(id = "exploration-$week", weekIndex = week))
        }
        val blocks = scheduleBlocks(sessions)
        val block = blocks.first()
        assertTrue(blocks.all { it.showPlaceholder })
        assertEquals(2, block.weekday)
        assertEquals(9..10, block.startUnit..block.endUnit)
        assertEquals(setOf("工程制图", "探索人体的奥秘"), block.sessions.map { it.courseName }.toSet())
        val choice = ConflictDisplayChoice(194, block.conflictKey, 8, exploration.conflictDisplayKey())
        val selected = scheduleBlocks(sessions, listOf(choice))
        assertEquals(listOf(true, false, true), selected.map { it.showPlaceholder })
        assertEquals("探索人体的奥秘", selected[1].displaySession.courseName)
    }

    @Test fun `部分重叠只在实际交集显示占位 不吞掉后半段`() {
        val blocks = scheduleBlocks(listOf(session("A", 1, 4), session("B", 3, 6)))
        assertEquals(listOf(1..2, 3..4, 5..6), blocks.map { it.startUnit..it.endUnit })
        assertEquals(listOf(false, true, false), blocks.map { it.showPlaceholder })
        assertEquals(listOf("B"), blocks.last().sessions.map { it.courseCode })
    }

    @Test fun `包含关系保留前后两段 同时覆盖三门课`() {
        val blocks = scheduleBlocks(listOf(session("A", 1, 6), session("B", 3, 4), session("C", 3, 4)))
        assertEquals(listOf(1..2, 3..4, 5..6), blocks.map { it.startUnit..it.endUnit })
        assertEquals(3, blocks[1].sessions.size)
    }

    @Test fun `链式重叠按当前课程集合切段`() {
        val blocks = scheduleBlocks(listOf(session("A", 1, 2), session("B", 2, 3), session("C", 3, 4)))
        assertEquals(listOf(1..1, 2..2, 3..3, 4..4), blocks.map { it.startUnit..it.endUnit })
        assertEquals(listOf(1, 2, 2, 1), blocks.map { it.sessions.size })
    }

    @Test fun `不同教学周 星期 学期互不冲突`() {
        val a = session("A")
        val blocks = scheduleBlocks(listOf(a, session("B", week = 8), session("C", day = 3), session("D").copy(semesterId = 195)))
        assertEquals(4, blocks.size)
        assertTrue(blocks.none { it.isConflict })
    }

    @Test fun `同课程理论与实验是两个可选时段`() {
        val a = session("A")
        val experiment = a.copy(id = "experiment", experiment = true)
        val block = scheduleBlocks(listOf(a, experiment)).single()
        assertTrue(block.showPlaceholder)
        assertNotEquals(a.conflictDisplayKey(), experiment.conflictDisplayKey())
    }

    @Test fun `重复记录不产生假冲突`() {
        val a = session("A")
        assertFalse(scheduleBlocks(listOf(a, a)).single().isConflict)
    }

    @Test fun `仅选中的周改变显示且不删除其他课程`() {
        val sessions = (7..9).flatMap { listOf(session("A", week = it), session("B", week = it)) }
        val conflict = scheduleBlocks(sessions).first()
        val chosen = conflict.sessions.first()
        val choices = listOf(ConflictDisplayChoice(194, conflict.conflictKey, 8, chosen.conflictDisplayKey()))
        val blocks = scheduleBlocks(sessions, choices)
        assertEquals(listOf(true, false, true), blocks.map { it.showPlaceholder })
        assertEquals(chosen.courseCode, blocks[1].displaySession.courseCode)
        assertTrue(blocks.all { it.sessions.size == 2 })
        assertEquals(6, sessions.size)
    }

    @Test fun `重新同步排序 日期和名称地点变化后仍应用原选择`() {
        val sessions = listOf(session("A"), session("B"))
        val block = scheduleBlocks(sessions).single()
        val chosen = block.sessions.first()
        val choice = ConflictDisplayChoice(194, block.conflictKey, 7, chosen.conflictDisplayKey())
        val refreshed = sessions.reversed().map { it.copy(id = "new-${it.id}", courseName = "改名", roomText = "新教室", date = "2026-10-20") }
        val out = scheduleBlocks(refreshed, listOf(choice)).single()
        assertFalse(out.showPlaceholder)
        assertEquals(chosen.courseCode, out.displaySession.courseCode)
    }

    @Test fun `课程集合或时间变化时旧选择失效并恢复冲突提示`() {
        val sessions = listOf(session("A"), session("B"))
        val block = scheduleBlocks(sessions).single()
        val choice = ConflictDisplayChoice(194, block.conflictKey, 7, sessions.first().conflictDisplayKey())
        assertTrue(scheduleBlocks(sessions + session("C"), listOf(choice)).single().showPlaceholder)
        assertTrue(scheduleBlocks(sessions.map { it.copy(endUnit = 3) }, listOf(choice)).single().showPlaceholder)
    }

    @Test fun `无效选中课程安全回退到占位`() {
        val sessions = listOf(session("A"), session("B"))
        val block = scheduleBlocks(sessions).single()
        val choice = ConflictDisplayChoice(194, block.conflictKey, 7, "不存在的课程")
        assertTrue(scheduleBlocks(sessions, listOf(choice)).single().showPlaceholder)
    }

    @Test fun `研究生共用合成学期编号时按真实学期隔离选择`() {
        val sessions = listOf(session("A"), session("B"))
        val block = scheduleBlocks(sessions, scopeKey = "20261").single()
        val choice = ConflictDisplayChoice(194, block.conflictKey, 7, sessions.first().conflictDisplayKey())
        assertFalse(scheduleBlocks(sessions, listOf(choice), scopeKey = "20261").single().showPlaceholder)
        assertTrue(scheduleBlocks(sessions, listOf(choice), scopeKey = "20262").single().showPlaceholder)
    }

    @Test fun `无位置的记录不进网格 越界结束节次裁剪到现有网格`() {
        val blocks = scheduleBlocks(listOf(session("A", 9, 12), session("B", 0, 2), session("C", week = 0)))
        assertEquals(9..10, blocks.single().let { it.startUnit..it.endUnit })
    }

    @Test fun `随机时段在每个节次都完整保留实际占用集合`() {
        val random = Random(42)
        repeat(40) {
            val sessions = (1..12).map { n ->
                val start = random.nextInt(1, 11)
                session("$n", start, random.nextInt(start, 11))
            }
            val blocks = scheduleBlocks(sessions)
            for (unit in 1..10) {
                val expected = sessions.filter { unit in it.startUnit..it.endUnit }.map { it.id }.toSet()
                val actual = blocks.filter { unit in it.startUnit..it.endUnit }.flatMap { it.sessions }.map { it.id }.toSet()
                assertEquals("第 $unit 节", expected, actual)
            }
        }
    }
}
