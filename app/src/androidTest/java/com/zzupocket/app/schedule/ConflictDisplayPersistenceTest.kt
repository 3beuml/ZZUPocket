package com.zzupocket.app.schedule

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.db.SemesterEntity
import com.zzupocket.app.data.gms.GmsScheduleMapper
import com.zzupocket.app.data.repo.ScheduleRepository
import com.zzupocket.app.data.repo.TodayTimetable
import com.zzupocket.app.ui.today.TodayTimeline
import com.zzupocket.app.ui.today.TodayTimelineViewModel
import androidx.lifecycle.ViewModelStore
import com.zzupocket.app.domain.Course
import com.zzupocket.app.domain.Session
import com.zzupocket.app.domain.conflictDisplayKey
import com.zzupocket.app.domain.scheduleBlocks
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** 使用独立测试库走真实保存/同步路径，不访问学校接口或用户课表。 */
@RunWith(AndroidJUnit4::class)
class ConflictDisplayPersistenceTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: ScheduleRepository
    private val databaseName = "conflict-display-test-${UUID.randomUUID()}.db"

    @Before fun open() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        reopen()
    }

    private fun reopen() {
        db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        repo = ScheduleRepository(db, AppGraph.easClient, AppGraph.authRepository)
    }

    @After fun close() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    private fun sessions() = (7..9).flatMap { week ->
        listOf("A", "B").map { code ->
            Session(
                id = "$code-$week", semesterId = 194, source = "eas", courseCode = code,
                courseName = "课程$code", weekIndex = week, weekday = 2, startUnit = 1, endUnit = 2,
                date = null, startTime = null, endTime = null, teacherName = null, roomText = null,
                campusText = null, color = null,
            )
        }
    }

    private suspend fun sync(rows: List<Session>, scopeKey: String? = null) {
        val semesterId = rows.first().semesterId
        val courses = rows.groupBy { it.courseCode }.map { (code, list) ->
            Course("$semesterId:$code", semesterId, code, list.first().courseName, 0.0, null, emptyList(), list)
        }
        repo.saveTimetable(rows.first().source, semesterId, rows, courses, scopeKey = scopeKey)
    }

    @Test fun choiceSurvivesSyncAndDatabaseReopen() = runBlocking {
        val rows = sessions()
        sync(rows)
        val block = scheduleBlocks(rows).first()
        val selected = block.sessions.last().conflictDisplayKey()
        repo.saveConflictDisplay(block, setOf(7, 9), selected)
        // 模拟重新同步：顺序、课程名、教室甚至记录 id 都变化。
        sync(rows.reversed().map { it.copy(id = "new-${it.id}", courseName = "更新课程${it.courseCode}", roomText = "新教室") })
        db.close()
        reopen()
        val blocks = scheduleBlocks(repo.sessionsOfSemester(194), repo.conflictDisplayChoices(194))
        assertEquals(listOf(false, true, false), blocks.map { it.showPlaceholder })
        assertEquals(selected, blocks.first().displaySession.conflictDisplayKey())
        assertEquals(6, repo.sessionsOfSemester(194).size)
    }

    @Test fun resettingOneWeekPreservesOtherWeeksAndNameOverrides() = runBlocking {
        val rows = sessions()
        sync(rows)
        val block = scheduleBlocks(rows).first()
        repo.saveConflictDisplay(block, setOf(7, 8, 9), block.sessions.first().conflictDisplayKey())
        repo.saveDisplayNameOverride(194, "A", "自定义课程名")
        repo.saveConflictDisplay(block, setOf(8), null)
        sync(rows)
        val blocks = scheduleBlocks(repo.sessionsOfSemester(194), repo.conflictDisplayChoices(194))
        assertEquals(listOf(false, true, false), blocks.map { it.showPlaceholder })
        assertTrue(repo.sessionsOfSemester(194).filter { it.courseCode == "A" }.all { it.courseName == "自定义课程名" })
    }

    @Test fun differentWeeksCanChooseDifferentCourses() = runBlocking {
        val rows = sessions()
        sync(rows)
        val block = scheduleBlocks(rows).first()
        repo.saveConflictDisplay(block, setOf(7), block.sessions.first().conflictDisplayKey())
        repo.saveConflictDisplay(block, setOf(8), block.sessions.last().conflictDisplayKey())
        val blocks = scheduleBlocks(repo.sessionsOfSemester(194), repo.conflictDisplayChoices(194))
        assertFalse(blocks[0].showPlaceholder)
        assertFalse(blocks[1].showPlaceholder)
        assertTrue(blocks[2].showPlaceholder)
        assertEquals(block.sessions.first().courseCode, blocks[0].displaySession.courseCode)
        assertEquals(block.sessions.last().courseCode, blocks[1].displaySession.courseCode)
    }

    @Test fun graduateTermScopeSurvivesReopenAndDoesNotCarryChoicesIntoNextTerm() = runBlocking {
        val rows = sessions().map { it.copy(source = "gms", semesterId = -10_001) }
        sync(rows, scopeKey = "20261")
        val block = scheduleBlocks(rows, scopeKey = "20261").first()
        repo.saveConflictDisplay(block, setOf(7), block.sessions.first().conflictDisplayKey())
        // 当天只有自定义日程，也必须使用研究生学期范围，不能按当天是否有 gms 来源判断。
        val customDay = rows.map { it.copy(source = ScheduleRepository.SOURCE_CUSTOM) }
        val customBlock = scheduleBlocks(customDay, scopeKey = "20261").first()
        repo.saveConflictDisplay(customBlock, setOf(7), customBlock.sessions.first().conflictDisplayKey())
        val customHome = TodayTimeline.courseEntries(TodayTimetable.displayBlocks(repo, customDay), GmsScheduleMapper.defaultPeriods())
        assertEquals(3, customHome.size)
        assertFalse(customHome.first().hasUnresolvedConflict)
        sync(rows, scopeKey = "20262")
        db.close()
        reopen()
        val scope = repo.timetableScope("gms", -10_001)
        assertEquals("20262", scope)
        assertTrue(scheduleBlocks(repo.cachedSessions("gms", -10_001), repo.conflictDisplayChoices(-10_001), scopeKey = scope)
            .all { it.showPlaceholder })
        val homeEntries = TodayTimeline.courseEntries(TodayTimetable.displayBlocks(repo, rows), GmsScheduleMapper.defaultPeriods())
        assertEquals(3, homeEntries.size)
        assertTrue(homeEntries.all { it.hasUnresolvedConflict })
    }

    @Test fun homeColdLoadAndReturnFromScheduleHonorSavedChoices() = runBlocking {
        val rows = sessions().map {
            it.copy(startUnit = 9, endUnit = 10, startTime = "19:00", endTime = "20:40",
                courseName = if (it.courseCode == "A") "工程制图" else "探索人体的奥秘")
        }
        db.semesterDao().upsertAll(listOf(SemesterEntity(
            194, "20261", "测试学期", "2026", "2026-09-07", "2027-01-10", false,
            (1..18).joinToString(","), true, 0L,
        )))
        sync(rows)
        val clock = Clock.fixed(Instant.parse("2026-10-20T10:50:00Z"), ZoneId.of("Asia/Shanghai"))
        val stores = mutableListOf<ViewModelStore>()
        fun freshViewModel(): TodayTimelineViewModel {
            lateinit var vm: TodayTimelineViewModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                vm = TodayTimelineViewModel(repo, clock)
                stores += ViewModelStore().apply { put("timeline", vm) }
            }
            return vm
        }
        try {
            val vm = freshViewModel()
            val initial = withTimeout(10_000) { vm.state.first { it.loaded } }
            assertEquals(7, initial.week)
            assertTrue(initial.entries.single().hasUnresolvedConflict)
            val block = scheduleBlocks(rows).first()
            repo.saveConflictDisplay(block, setOf(7), block.sessions.last().conflictDisplayKey())
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.reload() }
            val returned = withTimeout(10_000) { vm.state.first { it.loaded && it.entries.singleOrNull()?.title == "探索人体的奥秘" } }
            assertFalse(returned.entries.single().hasUnresolvedConflict)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { stores.forEach { it.clear() } }
            stores.clear()
            db.close()
            reopen()
            val restarted = freshViewModel()
            val cold = withTimeout(10_000) { restarted.state.first { it.loaded } }
            assertEquals("探索人体的奥秘", cold.entries.single().title)
            assertFalse(cold.entries.single().hasUnresolvedConflict)
            assertEquals(6, repo.sessionsOfSemester(194).size)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { stores.forEach { it.clear() } }
        }
    }
}
