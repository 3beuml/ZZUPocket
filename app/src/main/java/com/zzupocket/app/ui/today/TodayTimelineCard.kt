package com.zzupocket.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.ScheduleText
import com.zzupocket.app.data.repo.ScheduleRepository
import com.zzupocket.app.domain.FocusStats
import com.zzupocket.app.domain.Phase
import com.zzupocket.app.domain.PlanColor
import com.zzupocket.app.domain.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 「今天」时间轴的界面状态 */
data class TodayTimelineUi(
    val loaded: Boolean = false,
    /** 当前是学期第几周（拿不到就是 null） */
    val week: Int? = null,
    val dateText: String = "",
    val entries: List<TimelineEntry> = emptyList(),
    /** 现在时刻（当天第几分钟），用来画"现在"那条线、并高亮进行中的一段 */
    val nowMin: Int = 0,
    val courseCount: Int = 0,
    /** 今天累计专注秒数（只算 FOCUS 段） */
    val focusSeconds: Int = 0,
    /** 今天完成的番茄数 */
    val focusCount: Int = 0,
    /** "下一节 14:00《…》· 40 分钟后" 这类提示 */
    val nextHint: String? = null,
    /** 今天**自己加的**日程（课表里的自定义日程），单独列一张卡，不和教务课程混在一起 */
    val customEntries: List<TimelineEntry> = emptyList(),
)

class TodayTimelineViewModel : ViewModel() {

    private val scheduleRepo: ScheduleRepository = AppGraph.scheduleRepository

    private val _state = MutableStateFlow(TodayTimelineUi())
    val state: StateFlow<TodayTimelineUi> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    /** 从课表/专注页切回来时调用 —— 时间轴是会变的 */
    fun reload() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val now = System.currentTimeMillis()
        val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
        val today = LocalDate.now()

        // 本科 + 研究生统一口径：研究生账号没有学期行，也要能看到今天的课
        val sessions = runCatching {
            com.zzupocket.app.data.repo.TodayTimetable
                .sessionsOn(scheduleRepo, AppGraph.uiPrefsStore, today)
        }.getOrDefault(emptyList())
        val periods = scheduleRepo.periods()

        // 今天（按 FocusStats 的口径：凌晨 4 点起算）的专注记录
        val dayStart = FocusStats.dayStart(FocusStats.dayKey(now))
        val records = runCatching { AppGraph.focusRepository.recordsSince(dayStart) }
            .getOrDefault(emptyList())
            .filter { it.phase == Phase.FOCUS }

        // 每门课今天已经专注了多少（番茄钟计时时会把课程写进 contextRef）
        val focusByRef = records.filter { it.contextRef.isNotBlank() }
            .groupBy { it.contextRef }
            .mapValues { (_, list) -> list.sumOf { it.actualSeconds } }

        val courseEntries = sessions
            .mapNotNull { session -> courseEntry(session, periods, null, focusByRef) }
            .sortedBy { it.startMin }
        val customEntries = sessions
            .filter { it.source == ScheduleRepository.SOURCE_CUSTOM }
            .mapNotNull { session -> courseEntry(session, periods, null, focusByRef) }
            .sortedBy { it.startMin }
        val focusEntries = records
            .map { record -> focusEntry(record) }
            .filter { it.endMin > it.startMin }
            .sortedBy { it.startMin }

        _state.value = TodayTimelineUi(
            loaded = true,
            week = com.zzupocket.app.data.repo.TodayTimetable.currentWeekAt(scheduleRepo, AppGraph.uiPrefsStore, today),
            dateText = DATE_FMT.format(today),
            // 只画**真实发生的事**：课 / 自己加的日程 / 已完成的专注。
            //
            // 课与课之间的"空档"不再画（用户明确说「空档 30 分钟 / 可以塞一个专注」这类
            // 提示是噪音，今天页只该显示课和日程）。空档本身仍在 TodayTimeline.build 里算、
            // 也仍有单测 —— 以后要做"课间可用时间"直接取用即可，这里只是不渲染。
            entries = TodayTimeline.build(courseEntries, focusEntries)
                .filter { it.kind != TimelineKind.GAP },
            nowMin = nowMin,
            courseCount = sessions.size,
            focusSeconds = records.sumOf { it.actualSeconds },
            focusCount = records.count { it.completed },
            nextHint = nextHint(courseEntries, nowMin),
            customEntries = customEntries,
        )
    }

    private fun courseEntry(
        session: Session,
        periods: List<com.zzupocket.app.domain.Period>,
        semesterId: Int?,
        focusByRef: Map<String, Int>,
    ): TimelineEntry? {
        val startPeriod = periods.firstOrNull { it.indexNo == session.startUnit }
        val endPeriod = periods.firstOrNull { it.indexNo == session.endUnit }
        val start = TodayTimeline.minuteOfDay(startPeriod?.startText ?: session.startTime) ?: return null
        val end = TodayTimeline.minuteOfDay(endPeriod?.endText ?: session.endTime) ?: (start + 45)
        val ref = semesterId?.let {
            "course:${ScheduleRepository.courseKeyOf(it, session.courseCode)}"
        }
        return TimelineEntry(
            kind = TimelineKind.COURSE,
            startMin = start,
            endMin = end,
            title = session.courseName,
            // 老师不进时间轴（点开课程详情能看到）；地点统一规整成"南2 206"
            subtitle = ScheduleText.tidyPlace(session.roomText ?: session.campusText).orEmpty(),
            colorArgb = PlanColor.of(session.color)?.argb,
            focusSeconds = ref?.let { focusByRef[it] } ?: 0,
        )
    }

    private fun focusEntry(record: com.zzupocket.app.domain.FocusRecord): TimelineEntry {
        val start = TodayTimeline.minuteOfEpoch(record.startedAt)
        val rawEnd = TodayTimeline.minuteOfEpoch(maxOf(record.endedAt, record.startedAt))
        // 跨零点（凌晨那一段）时把终点夹到当天末尾，避免出现负数长度
        val end = if (rawEnd <= start) minOf(1440, start + (record.actualSeconds / 60).coerceAtLeast(1)) else rawEnd
        return TimelineEntry(
            kind = TimelineKind.FOCUS,
            startMin = start,
            endMin = end,
            title = record.tagName.ifBlank { "专注" },
            subtitle = FocusStats.humanDuration(record.actualSeconds) +
                if (record.completed) "" else " · 中断",
        )
    }

    /** "正在上《…》· 还有 32 分钟下课" / "下一节 14:00《…》· 40 分钟后" */
    private fun nextHint(courses: List<TimelineEntry>, nowMin: Int): String? {
        courses.firstOrNull { it.covers(nowMin) }?.let { ongoing ->
            return "正在上《${ongoing.title}》· 还有 " +
                "${TodayTimeline.duration(ongoing.endMin - nowMin)}下课"
        }
        val next = courses.filter { it.startMin > nowMin }.minByOrNull { it.startMin }
        if (next != null) {
            val wait = next.startMin - nowMin
            val gapText = if (wait < 60) "$wait 分钟" else TodayTimeline.duration(wait)
            // 以前这里还会补一句「够一个番茄」——属于替用户做决定的多余提示，去掉
            return "下一节 ${TodayTimeline.clock(next.startMin)}《${next.title}》· ${gapText}后"
        }
        return if (courses.isEmpty()) null else "今天的课已上完"
    }

    private companion object {
        val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("M 月 d 日 EEEE", Locale.CHINA)
    }
}

/**
 * 「今天」页的时间轴卡片：把**上课 / 已完成的专注**画在同一根轴上。点一下跳到课表。
 *
 * 刻意不画"课间空档"那一行（见 `load()` 里的说明）：今天页只该回答
 * "我今天有什么课、已经专注了什么"，不替用户安排空档怎么用。
 */
@Composable
fun TodayTimelineCard(
    state: TodayTimelineUi,
    onOpenSchedule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpenSchedule),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("今天", style = MaterialTheme.typography.titleMedium)
                state.week?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "第 $it 周",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (state.dateText.isNotEmpty()) {
                    Text(
                        text = state.dateText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            }

            if (state.focusSeconds > 0) {
                Text(
                    text = buildString {
                        append("今日专注 ${FocusStats.humanDuration(state.focusSeconds)}")
                        if (state.focusCount > 0) append(" · ${state.focusCount} 个番茄")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            state.nextHint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (!state.loaded) {
                Text(
                    text = "读取中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (state.entries.isEmpty()) {
                Text(
                    text = "今天没有课，也没有专注记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                HorizontalDivider(Modifier.padding(top = 2.dp))
                TimelineList(state)
            }
        }
    }
}

@Composable
private fun TimelineList(state: TodayTimelineUi) {
    val entries = state.entries
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        var nowPlaced = false
        entries.forEach { entry ->
            if (!nowPlaced && entry.startMin > state.nowMin) {
                NowRow(state.nowMin)
                nowPlaced = true
            }
            TimelineRow(entry = entry, nowMin = state.nowMin)
        }
        if (!nowPlaced) NowRow(state.nowMin)
    }
}

/** 「现在 HH:mm」那条线 */
@Composable
private fun NowRow(nowMin: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = TodayTimeline.clock(nowMin),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.width(48.dp),
        )
        Spacer(Modifier.width(24.dp))
        Text(
            text = "现在",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun TimelineRow(entry: TimelineEntry, nowMin: Int) {
    val ongoing = entry.covers(nowMin)
    val railColor = MaterialTheme.colorScheme.outlineVariant
    val dotColor = when (entry.kind) {
        TimelineKind.COURSE -> entry.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
        TimelineKind.FOCUS -> MaterialTheme.colorScheme.tertiary
        TimelineKind.GAP -> MaterialTheme.colorScheme.surfaceVariant
    }
    val titleColor = when {
        ongoing -> MaterialTheme.colorScheme.primary
        entry.kind == TimelineKind.GAP -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // 左：时间栏
        Column(
            modifier = Modifier.width(48.dp).padding(top = 2.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = TodayTimeline.clock(entry.startMin),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = TodayTimeline.clock(entry.endMin),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.width(8.dp))
        // 中：竖轴 + 圆点
        Box(Modifier.width(16.dp).fillMaxHeight()) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(railColor),
            )
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 6.dp)
                    .size(if (ongoing) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        Spacer(Modifier.width(8.dp))
        // 右：内容
        Column(
            modifier = Modifier.weight(1f).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entryTitle(entry),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (entry.kind == TimelineKind.GAP) FontWeight.Normal else FontWeight.Medium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ongoing) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "进行中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            entrySubtitle(entry)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.kind == TimelineKind.COURSE && entry.focusSeconds > 0) {
                Text(
                    text = "已专注 ${FocusStats.humanDuration(entry.focusSeconds)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

private fun entryTitle(entry: TimelineEntry): String = entry.title

private fun entrySubtitle(entry: TimelineEntry): String? =
    entry.subtitle.takeIf { it.isNotBlank() }
