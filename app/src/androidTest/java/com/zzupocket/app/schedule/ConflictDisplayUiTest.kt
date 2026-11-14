package com.zzupocket.app.schedule

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zzupocket.app.data.prefs.GridDensity
import com.zzupocket.app.data.prefs.BlockBorder
import com.zzupocket.app.data.prefs.CourseColorMode
import com.zzupocket.app.data.prefs.DarkMode
import com.zzupocket.app.data.prefs.ThemePreset
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.domain.ScheduleBlock
import com.zzupocket.app.domain.ConflictDisplayChoice
import com.zzupocket.app.domain.Course
import com.zzupocket.app.domain.Session
import com.zzupocket.app.domain.conflictDisplayKey
import com.zzupocket.app.domain.scheduleBlocks
import com.zzupocket.app.ui.schedule.ConflictDetails
import com.zzupocket.app.ui.schedule.ConflictDisplaySection
import com.zzupocket.app.ui.schedule.CourseDetailSheet
import com.zzupocket.app.ui.schedule.ScheduleGrid
import com.zzupocket.app.ui.theme.ZZUPocketTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ConflictDisplayUiTest {
    @get:Rule val compose = createComposeRule()

    private fun session(code: String, name: String, day: Int = 1, start: Int = 1, end: Int = 2, week: Int = 7) = Session(
        id = "$code-$day-$start-$week", semesterId = 194, source = "eas", courseCode = code, courseName = name,
        weekIndex = week, weekday = day, startUnit = start, endUnit = end, date = null,
        startTime = null, endTime = null, teacherName = "教师甲", roomText = "教学楼101", campusText = null, color = null,
    )

    @Test fun placeholdersStayReadableAtEveryGridDensityAndOpenAllCourses() {
        val rows = listOf(
            session("A", "高等数学（甲）"), session("B", "大学英语（乙）"),
            session("C", "计算机基础", 3, 3, 6), session("D", "大学物理实验", 3, 4, 5),
            session("E", "课程甲", 5, 7, 8), session("F", "课程乙", 5, 7, 8), session("G", "课程丙", 5, 7, 8),
            // 本科真实报告的课程名和时段；周次、教师和教室仍为模拟值。
            session("drawing", "工程制图", 2, 9, 10), session("exploration", "探索人体的奥秘", 2, 9, 10),
            session("drawing", "工程制图", 1, 9, 10), session("management", "管理学", 3, 9, 10),
            session("manufacturing", "制造工程基础", 4, 9, 10), session("seminar", "新生研讨课", 5, 9, 10),
        )
        var prefs by mutableStateOf(UiPrefs(liveOutlineEnabled = false, darkMode = DarkMode.LIGHT))
        var clicked: ScheduleBlock? = null
        compose.setContent {
            ZZUPocketTheme(darkMode = DarkMode.LIGHT, preset = ThemePreset.AMBER) {
                Surface {
                    ScheduleGrid(rows, emptyList(), prefs, LocalDate.of(2026, 10, 19), emptyList()) { clicked = it }
                }
            }
        }
        GridDensity.entries.forEach { density ->
            compose.runOnIdle { prefs = prefs.copy(density = density) }
            compose.onAllNodes(hasText("课程冲突") or hasText("课程\n冲突"), useUnmergedTree = true).assertCountEquals(4)
            screenshot("conflict-grid-${density.name.lowercase()}")
        }
        compose.onNodeWithContentDescription("课程冲突：高等数学（甲）、大学英语（乙），点击查看冲突详情").performClick()
        compose.runOnIdle { assertEquals(setOf("A", "B"), clicked!!.sessions.map { it.courseCode }.toSet()) }
        compose.onNodeWithContentDescription("课程冲突：工程制图、探索人体的奥秘，点击查看冲突详情").performClick()
        compose.runOnIdle {
            assertEquals(2, clicked!!.weekday)
            assertEquals(9..10, clicked!!.startUnit..clicked!!.endUnit)
            assertEquals(setOf("工程制图", "探索人体的奥秘"), clicked!!.sessions.map { it.courseName }.toSet())
        }
    }

    @Test fun displayEditorSelectsCourseAndWeeksWithoutChangingExistingDetails() {
        val rows = (7..9).flatMap {
            listOf(session("drawing", "工程制图", 2, 9, 10, it), session("exploration", "探索人体的奥秘", 2, 9, 10, it))
        }
        val blocks = scheduleBlocks(rows)
        val detail = ConflictDetails(blocks.first(), blocks)
        var saved: Pair<String?, Set<Int>>? = null
        compose.setContent {
            ZZUPocketTheme(darkMode = DarkMode.LIGHT, preset = ThemePreset.AMBER) {
                CourseDetailSheet(
                    course = Course("194:drawing", 194, "drawing", "工程制图", 0.0, null, emptyList(), rows.filter { it.courseCode == "drawing" }),
                    onDismiss = {},
                    onApplyEdits = { _, _, _ -> },
                    beforeDetails = {
                        ConflictDisplaySection(detail, false, null, null, {}, { key, weeks -> saved = key to weeks })
                    },
                )
            }
        }
        compose.onNodeWithText("冲突详情").assertIsDisplayed()
        screenshot("conflict-detail-overview", inDialog = true)
        compose.onNodeWithText("更改显示").performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
            .performSemanticsAction(SemanticsActions.Expand) { it() }
        compose.onNodeWithText("显示探索人体的奥秘").performClick()
        compose.onNodeWithText("7").performScrollTo().performClick()
        compose.onNodeWithText("保存显示").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("全部冲突周").performScrollTo().performClick()
        compose.onNodeWithText("保存显示").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(rows.first { it.courseCode == "exploration" }.conflictDisplayKey(), saved!!.first)
            assertEquals(setOf(7, 8, 9), saved!!.second)
        }
        screenshot("conflict-display-weeks", inDialog = true)
        compose.onNodeWithText("冲突详情").performScrollTo()
        screenshot("conflict-display-editor", inDialog = true)
    }

    @Test fun selectedCourseKeepsConflictAccessInMonochromeAndDarkTheme() {
        val rows = listOf(
            session("drawing", "工程制图", 1, 9, 10),
            session("drawing", "工程制图", 2, 9, 10), session("exploration", "探索人体的奥秘", 2, 9, 10),
        )
        var prefs by mutableStateOf(UiPrefs(
            liveOutlineEnabled = false, darkMode = DarkMode.LIGHT,
            colorMode = CourseColorMode.SINGLE, blockBorder = BlockBorder.NONE,
        ))
        var choices by mutableStateOf(emptyList<ConflictDisplayChoice>())
        var clicked: ScheduleBlock? = null
        compose.setContent {
            ZZUPocketTheme(darkMode = prefs.darkMode, preset = ThemePreset.AMBER) {
                Surface {
                    ScheduleGrid(rows, emptyList(), prefs, LocalDate.of(2026, 10, 19), choices) { clicked = it }
                }
            }
        }
        val title = hasText("课程冲突") or hasText("课程\n冲突")
        compose.onAllNodes(title, useUnmergedTree = true).assertCountEquals(1)
        screenshot("conflict-grid-monochrome")
        compose.runOnIdle { prefs = prefs.copy(darkMode = DarkMode.DARK) }
        compose.onAllNodes(title, useUnmergedTree = true).assertCountEquals(1)
        screenshot("conflict-grid-dark")
        val block = scheduleBlocks(rows).first { it.isConflict }
        compose.runOnIdle {
            choices = listOf(ConflictDisplayChoice(194, block.conflictKey, block.weekIndex, block.sessions.last().conflictDisplayKey()))
        }
        compose.onAllNodes(title, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("课程冲突：工程制图、探索人体的奥秘，当前显示探索人体的奥秘，点击更改显示").performClick()
        compose.runOnIdle { assertEquals(2, clicked!!.sessions.size) }
        screenshot("conflict-grid-chosen-dark")
    }

    private fun screenshot(name: String, inDialog: Boolean = false) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "conflict-test-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { output ->
            // 捕获窗口根节点，避免系统合成器的旧帧或滚动内容节点的缩放影响截图。
            val root = if (inDialog) compose.onNode(isDialog()) else compose.onRoot()
            root.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }
}
