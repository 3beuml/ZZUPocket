package com.zzupocket.app.ui.schedule

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zzupocket.app.core.ScheduleText
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.domain.Session
import com.zzupocket.app.domain.conflictDisplayKey

/** 窄格使用对称的两行标题，不为塞进一行而缩小「课程冲突」。 */
@Composable
internal fun ConflictPlaceholderTitle(prefs: UiPrefs) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val twoLines = maxWidth < with(density) { prefs.courseFontSp.sp.toDp() * 4 }
        Text(
            if (twoLines) "课程\n冲突" else "课程冲突",
            fontSize = prefs.courseFontSp.sp,
            lineHeight = (prefs.courseFontSp + 2).sp,
            maxLines = 2,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
}

/** 测量整组名称后统一缩略，保留各课程前缀，避免逐项等分窄格后只剩省略号。 */
@Composable
internal fun ConflictCourseNames(sessions: List<Session>, prefs: UiPrefs, span: Int) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val baseStyle = LocalTextStyle.current
    val names = sessions.map { it.courseName }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val layout = remember(names, prefs.roomFontSp, span, constraints, density, baseStyle) {
            var best: Triple<String, Float, Int>? = null
            // 通常保留两个字；名称开头相同则多留一两个字，以便区分课程。
            val preferredPrefix = (2..4).firstOrNull { prefix ->
                names.map { it.take(prefix) }.distinct().size == names.distinct().size
            } ?: 4
            search@ for (prefix in preferredPrefix downTo 1) {
                for (step in 0..((prefs.roomFontSp - 7f) * 2).toInt()) {
                    val font = prefs.roomFontSp - step * 0.5f
                    val lineHeight = with(density) { (font + 1).sp.toPx() }
                    val lines = minOf(if (span >= 2) minOf(names.size, 4) else 2, (constraints.maxHeight / lineHeight).toInt().coerceAtLeast(1))
                    val label = names.joinToString("/\n", "（", "）") { if (it.length > prefix) it.take(prefix) + "…" else it }
                    val result = measurer.measure(
                        label,
                        style = baseStyle.copy(fontSize = font.sp, lineHeight = (font + 1).sp),
                        maxLines = lines,
                        constraints = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight),
                    )
                    if (!result.hasVisualOverflow) {
                        best = Triple(label, font, lines)
                        break@search
                    }
                }
            }
            best ?: Triple(
                names.take(2).joinToString("/", "（", if (names.size > 2) "/+${names.size - 2}）" else "）") { it.take(1) + "…" },
                7f,
                if (span >= 2) minOf(names.size, 4) else 2,
            )
        }
        Text(
            layout.first,
            Modifier.fillMaxWidth(),
            fontSize = layout.second.sp,
            lineHeight = (layout.second + 1).sp,
            maxLines = layout.third,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** 放在原有课程详情的最上方；不另开详情页，也不在同步时弹出选择框。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConflictDisplaySection(
    detail: ConflictDetails,
    saving: Boolean,
    error: String?,
    notice: String?,
    onCourseClick: (Session) -> Unit,
    onSave: (String?, Set<Int>) -> Unit,
) {
    val block = detail.block
    var editing by remember(block.conflictKey, block.weekIndex) { mutableStateOf(false) }
    var selectedKey by remember(block.conflictKey, block.weekIndex, block.selectedSessionKey) {
        mutableStateOf(block.selectedSession?.conflictDisplayKey())
    }
    var weeks by remember(block.conflictKey, block.weekIndex) { mutableStateOf(setOf(block.weekIndex)) }
    LaunchedEffect(notice) { if (notice != null) editing = false }

    val colors = MaterialTheme.colorScheme
    val accent = if (block.showPlaceholder) colors.error else colors.primary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (block.showPlaceholder) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (block.showPlaceholder) colors.onErrorContainer else colors.onSecondaryContainer,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
                Text("冲突详情", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent)
            }
            Text(
                "第 ${block.weekIndex} 周 · ${ScheduleText.weekdayName(block.weekday)} · ${block.startUnit}–${block.endUnit} 节",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    block.sessions.forEach { session ->
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(session.courseName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { onCourseClick(session) }, enabled = !saving) { Text("查看详情") }
            }
            Text(
                listOfNotNull(
                    "${session.startUnit}–${session.endUnit} 节",
                    if (session.experiment) "实验/见习" else null,
                    session.teacherName,
                    ScheduleText.tidyPlace(session.roomText ?: session.campusText),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(
        "本周显示：${block.selectedSession?.courseName ?: "课程冲突占位"}",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = accent,
    )
    detail.matchingBlocks.filter { it.selectedSession != null }.groupBy { it.selectedSessionKey }
        .values.forEach { sameChoice ->
            Text(
                "${ScheduleText.compressWeeks(sameChoice.map { it.weekIndex })}：显示${sameChoice.first().displaySession.courseName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    if (!detail.editable) {
        Text("刷新课表后可设置本学期的显示方式", style = MaterialTheme.typography.bodySmall)
    } else if (!editing) {
        FilledTonalButton(onClick = {
            selectedKey = block.selectedSession?.conflictDisplayKey()
            weeks = setOf(block.weekIndex)
            editing = true
        }, enabled = !saving) { Text("更改显示") }
    } else {
        Text("显示方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ConflictDisplayOption("保留课程冲突占位", selectedKey == null, !saving) { selectedKey = null }
        block.sessions.forEach { session ->
            val key = session.conflictDisplayKey()
            val label = "显示${session.courseName}" + if (session.experiment) "（实验/见习）" else ""
            ConflictDisplayOption(label, selectedKey == key, !saving) { selectedKey = key }
        }
        Text("适用教学周", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { weeks = setOf(block.weekIndex) }, enabled = !saving) { Text("仅本周") }
            TextButton(onClick = { weeks = detail.weeks.toSet() }, enabled = !saving) { Text("全部冲突周") }
            TextButton(onClick = { weeks = detail.weeks.filter { it % 2 == 1 }.toSet() }, enabled = !saving) { Text("单周") }
            TextButton(onClick = { weeks = detail.weeks.filter { it % 2 == 0 }.toSet() }, enabled = !saving) { Text("双周") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            detail.weeks.forEach { week ->
                FilterChip(
                    selected = week in weeks,
                    onClick = { weeks = if (week in weeks) weeks - week else weeks + week },
                    enabled = !saving,
                    label = { Text("$week") },
                )
            }
        }
        Text(
            "仅更改所选周的课表显示。其他课程仍保留在详情中，重新同步不会覆盖此选择。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onSave(selectedKey, weeks) }, enabled = weeks.isNotEmpty() && !saving) {
                Text(if (saving) "保存中…" else "保存显示")
            }
            TextButton(onClick = { editing = false }, enabled = !saving) { Text("取消") }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun ConflictDisplayOption(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            label,
            Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
