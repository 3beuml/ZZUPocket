package com.zzupocket.app.ui.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.zzupocket.app.ui.components.GlassCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.core.ScheduleText
import com.zzupocket.app.domain.Course

/**
 * 课程总览：该学期要上的课**每行一门**，点进去看详情。
 *
 * 只列教务课程；自己添加的日程（P3 起）不进这里 —— 否则分不清哪些是正式课程。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseOverviewScreen(
    semesterId: Int,
    onBack: () -> Unit,
    vm: ScheduleViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // 自己的 VM 实例也要先加载学期列表，之后再切到指定学期
    LaunchedEffect(state.semesters, semesterId) {
        if (state.semesters.isNotEmpty() && state.selectedSemester?.id != semesterId) {
            vm.selectSemester(semesterId)
        }
    }

    val courses = state.courses.sortedBy { it.nameZh }
    // 手动补录的课程也列进来（issue #7 兜底：学校接口漏给的课用户自己补的），
    // 带「手动」标注与教务课程区分
    val manual = state.plans
        .filter { it.kind == com.zzupocket.app.domain.PlanKind.COURSE }
        .sortedBy { it.name }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课程总览") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = if (manual.isEmpty()) {
                    "本学期 ${courses.size} 门课"
                } else {
                    "本学期 ${courses.size} 门课 · 手动补录 ${manual.size} 门"
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(courses, key = { it.key }) { course ->
                    CourseCard(course) { vm.openDetail(course) }
                }
                items(manual, key = { "custom:${it.code}" }) { plan ->
                    CourseCard(plan.toManualCourse(), badge = "手动") {
                        vm.openDetail(plan.toManualCourse())
                    }
                }
            }
        }
    }

    state.detailCourse?.let { course ->
        CourseDetailSheet(
            course = course,
            onDismiss = vm::closeDetail,
            overrides = state.detailOverrides,
            onApplyEdits = vm::applyDisplayEdits,
            onResetOverrides = vm::resetDisplayOverrides,
        )
    }
}

/** 手动补录的课程 → [Course]：复用总览卡片与详情页的渲染（教师/时段/周次都能自动算出来） */
private fun com.zzupocket.app.domain.Plan.toManualCourse(): com.zzupocket.app.domain.Course =
    com.zzupocket.app.domain.Course(
        key = "custom:$code",
        semesterId = semesterId,
        code = code,
        nameZh = name,
        credits = 0.0,
        openDepartment = null,
        teachers = teacherName?.let { listOf(it) }.orEmpty(),
        sessions = weeks.map { w ->
            com.zzupocket.app.domain.Session(
                id = "custom:$code:$w",
                semesterId = semesterId,
                source = com.zzupocket.app.data.repo.ScheduleRepository.SOURCE_CUSTOM,
                courseCode = code,
                courseName = name,
                weekIndex = w,
                weekday = weekday,
                startUnit = startUnit,
                endUnit = endUnit,
                date = null,
                startTime = null,
                endTime = null,
                teacherName = teacherName,
                roomText = roomText,
                campusText = null,
                color = colorKey,
            )
        },
    )

@Composable
private fun CourseCard(course: Course, badge: String? = null, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = course.nameZh,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            if (course.teachers.isNotEmpty()) {
                Text(
                    text = course.teachers.joinToString("、"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = ScheduleText.timeSlots(course.slots),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = ScheduleText.compressWeeks(course.weeks),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}
