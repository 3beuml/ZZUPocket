package com.zzupocket.app.ui.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.domain.FocusHeatmap
import com.zzupocket.app.domain.FocusStats
import com.zzupocket.app.domain.FocusSummary
import com.zzupocket.app.domain.FocusTotals
import com.zzupocket.app.domain.TagTotal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusStatsScreen(
    onBack: () -> Unit,
    vm: FocusStatsViewModel = viewModel(),
) {
    val records by vm.records.collectAsStateWithLifecycle()
    val today = remember { FocusStats.dayKey(System.currentTimeMillis()) }
    val summary = remember(records, today) {
        FocusStats.summarize(records, System.currentTimeMillis())
    }
    val secondsByDay = remember(records) { FocusStats.secondsByDay(records) }
    val streak = remember(secondsByDay, today) { FocusHeatmap.streak(secondsByDay, today) }

    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("学习统计") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            OverviewCard(summary, streak)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "打卡热力图",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    FocusHeatmap(
                        secondsByDay = secondsByDay,
                        today = today,
                        selectedDay = selectedDay,
                        onDayClick = { day ->
                            selectedDay = if (selectedDay == day) null else day
                        },
                        modifier = Modifier.padding(start = 12.dp),
                    )
                    SelectedDayLine(selectedDay, secondsByDay)
                    HeatmapLegend(Modifier.padding(horizontal = 16.dp))
                }
            }

            PeriodCard(summary)

            TagCard(summary.perTag)

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OverviewCard(summary: FocusSummary, streak: Int) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                BigStat(
                    label = "累计专注",
                    value = FocusStats.humanDuration(summary.allTime.seconds),
                    modifier = Modifier.weight(1f),
                )
                BigStat(
                    label = "累计番茄",
                    value = "${summary.allTime.sessions} 个",
                    modifier = Modifier.weight(1f),
                )
                BigStat(
                    label = "连续打卡",
                    value = if (streak > 0) "$streak 天" else "—",
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = "一天从凌晨 4 点算起：学到凌晨 1 点记在前一天晚上。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SelectedDayLine(day: LocalDate?, secondsByDay: Map<LocalDate, Int>) {
    val text = if (day == null) {
        "点一下格子看那天的详情"
    } else {
        val weekday = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)
        val fmt = DateTimeFormatter.ofPattern("M月d日")
        val seconds = secondsByDay[day] ?: 0
        "${day.format(fmt)} $weekday · ${FocusStats.humanDuration(seconds)}"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun PeriodCard(summary: FocusSummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "周 · 月 · 年",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            PeriodRow("今天", summary.today)
            PeriodRow("本周", summary.week)
            PeriodRow("本月", summary.month)
            PeriodRow("本年", summary.year)
            PeriodRow("累计", summary.allTime)
        }
    }
}

@Composable
private fun PeriodRow(label: String, totals: FocusTotals) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp))
        Text(
            text = FocusStats.humanDuration(totals.seconds),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${totals.sessions} 个番茄",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TagCard(perTag: List<TagTotal>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "按标签",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (perTag.isEmpty()) {
                Text(
                    text = "还没有记录。去专注页开一个番茄试试。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val max = perTag.maxOf { it.totals.seconds }.coerceAtLeast(1)
            perTag.forEach { tag ->
                TagRow(tag, max)
            }
        }
    }
}

@Composable
private fun TagRow(tag: TagTotal, maxSeconds: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tag.tagName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${FocusStats.humanDuration(tag.totals.seconds)} · ${tag.totals.sessions} 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 用纯色条代替进度条：一眼比长短，不用读数
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val ratio = tag.totals.seconds.toFloat() / maxSeconds.toFloat()
            Box(
                Modifier
                    .fillMaxWidth(ratio.coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
