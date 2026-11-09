package com.zzupocket.app.schedule

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.db.SemesterEntity
import com.zzupocket.app.data.prefs.DarkMode
import com.zzupocket.app.data.prefs.ThemePreset
import com.zzupocket.app.data.repo.ScheduleRepository
import com.zzupocket.app.domain.Course
import com.zzupocket.app.domain.Session
import com.zzupocket.app.domain.conflictDisplayKey
import com.zzupocket.app.domain.scheduleBlocks
import com.zzupocket.app.ui.theme.ZZUPocketTheme
import com.zzupocket.app.ui.today.TodayCardViewModel
import com.zzupocket.app.ui.today.TodayScreen
import com.zzupocket.app.ui.today.TodayTimelineViewModel
import com.zzupocket.app.ui.today.TodayViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** 通过首页真实 ViewModel、真实 Room 数据及 TodayScreen 验证首屏，不连接教务。 */
@RunWith(AndroidJUnit4::class)
class TodayConflictUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "today-conflict-test-${UUID.randomUUID()}.db"
    private lateinit var db: AppDatabase
    private lateinit var repo: ScheduleRepository
    private val viewModels = ViewModelStore()
    private val rows = listOf(
        session("management", "管理学", 7, 8, "16:00", "17:40"),
        session("drawing", "工程制图"), session("exploration", "探索人体的奥秘"),
    )

    private fun session(code: String, name: String, start: Int = 9, end: Int = 10,
                        startTime: String = "19:00", endTime: String = "20:40") = Session(
        id = "today-$code", semesterId = 194, source = "eas", courseCode = code, courseName = name,
        weekIndex = 7, weekday = 2, startUnit = start, endUnit = end, date = null,
        startTime = startTime, endTime = endTime, teacherName = "教师甲", roomText = "教学楼101", campusText = null, color = null,
    )

    @Before fun seedTimetable(): Unit = runBlocking {
        db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        repo = ScheduleRepository(db, AppGraph.easClient, AppGraph.authRepository)
        db.semesterDao().upsertAll(listOf(SemesterEntity(
            194, "20261", "测试学期", "2026", "2026-09-07", "2027-01-10", false,
            (1..18).joinToString(","), true, 0L,
        )))
        val courses = rows.map { Course("194:${it.courseCode}", 194, it.courseCode, it.courseName, 0.0, null, emptyList(), listOf(it)) }
        repo.saveTimetable("eas", 194, rows, courses)
    }

    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModels.clear() }
        db.close()
        context.deleteDatabase(databaseName)
    }

    private fun timelineAt(instant: String): TodayTimelineViewModel {
        lateinit var vm: TodayTimelineViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = TodayTimelineViewModel(repo, Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Shanghai")))
            viewModels.put("timeline", vm)
        }
        return vm
    }

    private fun showHome(timeline: TodayTimelineViewModel, onOpenSchedule: () -> Unit = {},
                         dark: () -> DarkMode = { DarkMode.LIGHT }, fontScale: () -> Float = { 1f }) {
        lateinit var today: TodayViewModel
        lateinit var card: TodayCardViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            today = TodayViewModel().also { viewModels.put("today", it) }
            card = TodayCardViewModel().also { viewModels.put("card", it) }
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale())) {
                ZZUPocketTheme(darkMode = dark(), preset = ThemePreset.AMBER) {
                    TodayScreen({}, {}, onOpenSchedule, {}, {}, vm = today, timelineVm = timeline, cardVm = card)
                }
            }
        }
        compose.waitUntil(10_000) { timeline.state.value.loaded }
    }

    @Test fun homeFirstLoadAndScheduleReturnUseTheSameWeekChoice() {
        val timeline = timelineAt("2026-10-20T10:50:00Z")
        var opened = 0
        showHome(timeline, onOpenSchedule = { opened++ })
        compose.onNodeWithText("课程冲突").assertIsDisplayed()
        compose.onNodeWithText("工程制图 / 探索人体的奥秘").assertIsDisplayed()
        compose.onNodeWithText("现在").assertIsDisplayed()
        compose.onNodeWithText("18:50").assertIsDisplayed()
        screenshot("today-conflict-unresolved")
        compose.onNodeWithText("点按查看课表").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
        runBlocking {
            val block = scheduleBlocks(rows).first { it.isConflict }
            repo.saveConflictDisplay(block, setOf(7), rows.last().conflictDisplayKey())
        }
        compose.runOnIdle { timeline.reload() }
        compose.waitUntil(10_000) { timeline.state.value.entries.lastOrNull()?.title == "探索人体的奥秘" }
        compose.onNodeWithText("课程冲突").assertDoesNotExist()
        compose.onNodeWithText("工程制图").assertDoesNotExist()
        compose.onNodeWithText("探索人体的奥秘").assertIsDisplayed()
        compose.onNodeWithText("现在").assertIsDisplayed()
        screenshot("today-conflict-selected")
    }

    @Test fun unresolvedOngoingConflictRemainsClearInDarkThemeAndLargerText() {
        val timeline = timelineAt("2026-10-20T11:30:00Z")
        var dark by mutableStateOf(DarkMode.LIGHT)
        var scale by mutableStateOf(1f)
        showHome(timeline, dark = { dark }, fontScale = { scale })
        compose.onNodeWithText("当前时段有课程冲突 · 点按课表查看").assertIsDisplayed()
        compose.onNodeWithText("课程冲突").assertIsDisplayed()
        compose.onNodeWithText("现在").assertIsDisplayed()
        screenshot("today-conflict-ongoing")
        compose.runOnIdle { dark = DarkMode.DARK; scale = 1.3f }
        compose.onNodeWithText("课程冲突").assertIsDisplayed()
        compose.onNodeWithText("点按查看课表").assertIsDisplayed()
        compose.onNodeWithText("现在").assertIsDisplayed()
        screenshot("today-conflict-dark-large-font")
    }

    private fun screenshot(name: String) {
        // 等点按涟漪结束，避免把暂态按压底色当作已选择课程的常态样式。
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        // Android 原生涟漪由平台渲染时钟驱动，Compose 虚拟时钟不会推进它。
        android.os.SystemClock.sleep(600)
        compose.waitForIdle()
        val directory = File(context.getExternalFilesDir(null), "conflict-test-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
