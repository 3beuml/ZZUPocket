package com.zzupocket.app.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zzupocket.app.core.ScheduleText
import com.zzupocket.app.domain.Plan
import com.zzupocket.app.domain.PlanColor
import com.zzupocket.app.domain.PlanKind
import com.zzupocket.app.domain.PlanSpec
import com.zzupocket.app.domain.WeekMode

/** 快捷名称：学生最常加的那几类 */
private val QUICK_NAMES = listOf("自习", "实验", "复习", "作业", "论文", "社团")

/** 自定义日程在网格/今天里的底色。深浅模式各调一档透明度，保证字看得清 */
fun planBlockColor(colorKey: String?, dark: Boolean): Color {
    val color = PlanColor.of(colorKey) ?: PlanColor.DEFAULT
    return Color(color.argb).copy(alpha = if (dark) 0.34f else 0.52f)
}

/** 新建 / 编辑自定义日程。
 *
 * 一个对话框干三件事：新建、编辑、删除。日程字段不多，
 * 再拆一层"详情页"反而多一次跳转。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanEditDialog(
    initial: Plan?,
    availableWeeks: List<Int>,
    maxUnit: Int,
    canStartFocus: Boolean,
    /** 网格里正在看的那一周 —— "仅当周"用的就是它 */
    currentWeek: Int,
    onDismiss: () -> Unit,
    onSave: (PlanSpec) -> Unit,
    onDelete: ((Plan) -> Unit)? = null,
    onStartFocus: ((Plan) -> Unit)? = null,
    semesterId: Int,
) {
    val weeks = availableWeeks.ifEmpty { (1..18).toList() }
    val firstWeek = weeks.firstOrNull() ?: 1
    val lastWeek = weeks.lastOrNull() ?: 18

    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var kind by remember(initial) { mutableStateOf(initial?.kind ?: PlanKind.EVENT) }
    var teacher by remember(initial) { mutableStateOf(initial?.teacherName.orEmpty()) }
    var weekday by remember(initial) { mutableStateOf(initial?.weekday ?: 1) }
    var startUnit by remember(initial) { mutableStateOf(initial?.startUnit ?: 1) }
    var endUnit by remember(initial) { mutableStateOf(initial?.endUnit ?: 2) }
    var weekFrom by remember(initial) {
        mutableStateOf((initial?.weeks?.minOrNull() ?: firstWeek).toString())
    }
    var weekTo by remember(initial) {
        mutableStateOf((initial?.weeks?.maxOrNull() ?: lastWeek).toString())
    }
    var weekMode by remember(initial) { mutableStateOf(inferWeekMode(initial, weeks)) }
    var room by remember(initial) { mutableStateOf(initial?.roomText.orEmpty()) }
    var color by remember(initial) { mutableStateOf(PlanColor.of(initial?.colorKey) ?: PlanColor.DEFAULT) }

    val from = weekFrom.toIntOrNull()
    val to = weekTo.toIntOrNull()
    val rangeOk = from != null && to != null && from <= to
    val selectedWeeks = if (rangeOk) {
        weekMode.filter(weeks.filter { it >= from!! && it <= to!! })
    } else {
        emptyList()
    }
    val unitOk = endUnit >= startUnit
    val canSave = name.isNotBlank() && rangeOk && unitOk && selectedWeeks.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val what = if (initial == null) "添加" else "编辑"
            Text(if (kind == PlanKind.COURSE) "${what}课程" else "${what}日程")
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 类型：课程 = 手动补录一门课（学校接口漏给的兜底）；日程 = 自习/社团这类安排。
                // 类型记在 code 前缀上，对存储零影响。
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlanKind.entries.forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = { Text(k.label) },
                        )
                    }
                }
                if (kind == PlanKind.COURSE) {
                    Text(
                        text = "课表里缺的课可以自己补上：格子、今天、组件都会显示，教务刷新不会冲掉。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    label = { Text(if (kind == PlanKind.COURSE) "课程名" else "名称") },
                    placeholder = {
                        Text(if (kind == PlanKind.COURSE) "如：人体寄生虫学" else "自习 / 实验 / 家教…")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 常用名字点一下就填好，省得每次敲字（尤其是期末周天天加日程的时候）
                if (kind == PlanKind.EVENT) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QUICK_NAMES.forEach { quick ->
                            FilterChip(
                                selected = name == quick,
                                onClick = { name = quick },
                                label = { Text(quick) },
                            )
                        }
                    }
                }
                if (kind == PlanKind.COURSE) {
                    OutlinedTextField(
                        value = teacher,
                        onValueChange = { teacher = it.take(16) },
                        label = { Text("教师（可留空）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                FieldLabel("星期")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScheduleText.WEEKDAY_NAMES.forEachIndexed { index, label ->
                        FilterChip(
                            selected = weekday == index + 1,
                            onClick = { weekday = index + 1 },
                            label = { Text(label) },
                        )
                    }
                }

                FieldLabel("节次")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..maxUnit).forEach { unit ->
                        FilterChip(
                            selected = startUnit == unit,
                            onClick = {
                                startUnit = unit
                                if (endUnit < unit) endUnit = unit
                            },
                            label = { Text("$unit") },
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..maxUnit).forEach { unit ->
                        FilterChip(
                            selected = endUnit == unit,
                            onClick = { endUnit = unit },
                            enabled = unit >= startUnit,
                            label = { Text("$unit") },
                        )
                    }
                }
                Text(
                    text = if (unitOk) "第 $startUnit - $endUnit 节" else "结束节次不能早于开始节次",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unitOk) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )

                FieldLabel("周次")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 最常用的一档：只给"我现在看的这一周"排一件事，点一下就完事
                    val onlyThisWeek = weekMode == WeekMode.ALL && from == currentWeek && to == currentWeek
                    FilterChip(
                        selected = onlyThisWeek,
                        onClick = {
                            weekMode = WeekMode.ALL
                            weekFrom = currentWeek.toString()
                            weekTo = currentWeek.toString()
                        },
                        label = { Text("仅当周（第 $currentWeek 周）") },
                    )
                    WeekMode.entries.forEach { mode ->
                        FilterChip(
                            selected = weekMode == mode,
                            onClick = { weekMode = mode },
                            label = { Text(mode.label) },
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WeekNumberField("从", weekFrom, Modifier.weight(1f)) { weekFrom = it }
                    WeekNumberField("到", weekTo, Modifier.weight(1f)) { weekTo = it }
                }
                Text(
                    text = if (!rangeOk) {
                        "周次范围不对"
                    } else {
                        "共 ${selectedWeeks.size} 周" + selectedWeeks.take(6).joinToString(
                            prefix = "（",
                            postfix = if (selectedWeeks.size > 6) "…）" else "）",
                        ) { "$it" }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (rangeOk) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )

                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it.take(20) },
                    label = { Text("地点（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                FieldLabel("颜色")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlanColor.entries.forEach { item ->
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(item.argb))
                                .then(
                                    if (item == color) {
                                        Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.onSurface,
                                            CircleShape,
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { color = item },
                        )
                    }
                }

                if (initial != null && canStartFocus && onStartFocus != null) {
                    TextButton(onClick = { onStartFocus(initial) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("开始专注（标签用「${initial.name}」）")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        PlanSpec(
                            code = initial?.code,
                            semesterId = semesterId,
                            name = name.trim(),
                            weekday = weekday,
                            startUnit = startUnit,
                            endUnit = endUnit,
                            weeks = selectedWeeks,
                            roomText = room.trim().ifEmpty { null },
                            colorKey = color.name,
                            teacherName = if (kind == PlanKind.COURSE) teacher.trim().ifEmpty { null } else null,
                            kind = kind,
                        ),
                    )
                },
                enabled = canSave,
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (initial != null && onDelete != null) {
                    TextButton(onClick = { onDelete(initial) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 与教务课程撞课的提醒。只拦一次，确认后照存 */
@Composable
fun PlanConflictDialog(
    conflicts: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("和上课时间重叠") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("这个日程和下面这些课撞了：", style = MaterialTheme.typography.bodyMedium)
                conflicts.forEach { line ->
                    Text(
                        text = "· $line",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "有些安排本来就是重叠的（比如一边上课一边自习），所以这里只提醒。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("仍然保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("改一下") } },
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun WeekNumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> onChange(raw.filter(Char::isDigit).take(2)) },
        label = { Text("$label 第几周") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** 老日程回填周次模式：能整除才认为是单/双周，否则按每周 */private fun inferWeekMode(plan: Plan?, availableWeeks: List<Int>): WeekMode {
    if (plan == null) return WeekMode.ALL
    val selected = plan.weeks.toSet()
    if (selected.isEmpty()) return WeekMode.ALL
    val all = availableWeeks.ifEmpty { (1..18).toList() }
    return when {
        selected == WeekMode.ODD.filter(all).toSet() -> WeekMode.ODD
        selected == WeekMode.EVEN.filter(all).toSet() -> WeekMode.EVEN
        else -> WeekMode.ALL
    }
}
