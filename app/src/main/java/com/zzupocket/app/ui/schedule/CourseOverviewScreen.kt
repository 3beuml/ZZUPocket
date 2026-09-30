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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
                text = "本学期 ${courses.size} 门课",
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
            }
        }
    }

    state.detailCourse?.let { course ->
        CourseDetailSheet(course = course, onDismiss = vm::closeDetail)
    }
}

@Composable
private fun CourseCard(course: Course, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = course.nameZh,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
