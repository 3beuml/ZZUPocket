package com.zzupocket.app.ui.focus

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.zzupocket.app.ui.components.GlassCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.data.db.FocusTagEntity
import com.zzupocket.app.data.prefs.TimerPreset
import com.zzupocket.app.domain.FocusRecord
import com.zzupocket.app.ui.nav.DOCK_CLEARANCE
import com.zzupocket.app.domain.FocusStats
import com.zzupocket.app.domain.Phase
import com.zzupocket.app.domain.PomodoroClock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FocusScreen(
    onOpenStats: () -> Unit = {},
    vm: FocusViewModel = viewModel(),
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showSettings by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var showNewTag by remember { mutableStateOf(false) }
    var showManageTags by remember { mutableStateOf(false) }

    // Android 13+ 的通知权限。不给也能计时（时间用墙钟算），只是看不到常驻通知。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("专注") },
                actions = {
                    IconButton(onClick = onOpenStats) {
                        Icon(Icons.Filled.Insights, contentDescription = "学习统计")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "计时设置")
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
                .padding(horizontal = 20.dp)
                // 悬浮 dock 是覆盖层：垫出尾部余量，「今天」统计卡能完整滚出胶囊
                .padding(bottom = DOCK_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            TimerDial(ui)

            Controls(ui, vm)

            TagSection(
                ui = ui,
                onToggle = vm::toggleTag,
                onNew = { showNewTag = true },
                onManage = { showManageTags = true },
            )

            TodayCard(ui, onManual = { showManual = true })

            TodayRecords(ui.todayRecords, onDelete = vm::deleteRecord)

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSettings) {
        TimerSettingsDialog(
            ui = ui,
            onDismiss = { showSettings = false },
            onPreset = vm::applyPreset,
            onSave = { f, s, l, e, ab, af ->
                vm.saveDurations(f, s, l, e)
                vm.setAutoStart(ab, af)
                showSettings = false
            },
        )
    }

    if (showNewTag) {
        NewTagDialog(
            existing = ui.tags.map { it.name } + ui.courseCandidates,
            onDismiss = { showNewTag = false },
            onConfirm = { name ->
                vm.createTag(name)
                showNewTag = false
            },
        )
    }

    if (showManageTags) {
        TagManageDialog(
            tags = ui.tags,
            onRename = vm::renameTag,
            onDelete = vm::archiveTag,
            onDismiss = { showManageTags = false },
        )
    }

    if (showManual) {
        ManualRecordDialog(
            onDismiss = { showManual = false },
            onConfirm = { minutes, daysBack ->
                val now = System.currentTimeMillis()
                val backdated = now - daysBack * 24L * 3600_000L - minutes * 60_000L
                vm.addManualRecord(minutes = minutes, startedAt = backdated, note = "手动补录")
                showManual = false
            },
        )
    }
}

// ── 计时圆盘 ──────────────────────────────────────────────────────────────

@Composable
private fun TimerDial(ui: FocusUiState) {
    val phase = ui.timer.phase
    val total = ui.displayTotalSeconds.coerceAtLeast(1)
    val remaining = if (ui.timer.idle) total else ui.timer.remainingSeconds
    val progress = if (ui.timer.idle) 0f else ui.timer.progress

    val ringColor = when (phase) {
        Phase.FOCUS -> MaterialTheme.colorScheme.primary
        Phase.SHORT_BREAK -> MaterialTheme.colorScheme.tertiary
        Phase.LONG_BREAK -> MaterialTheme.colorScheme.secondary
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(232.dp)) {
            val strokeWidth = 14.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            if (progress > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when {
                    ui.timer.idle -> "下一个：${phase.label}"
                    ui.timer.paused -> "${phase.label} · 已暂停"
                    else -> phase.label
                },
                style = MaterialTheme.typography.titleSmall,
                color = ringColor,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = PomodoroClock.format(remaining),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = ui.tagName.ifBlank { "未分类" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.timer.snapshot.focusRound > 0 && ui.prefs.longBreakEvery > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "长休息前已专注 ${ui.timer.snapshot.focusRound % ui.prefs.longBreakEvery}" +
                        "/${ui.prefs.longBreakEvery} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── 控制按钮 ──────────────────────────────────────────────────────────────

@Composable
private fun Controls(ui: FocusUiState, vm: FocusViewModel) {
    val idle = ui.timer.idle
    val label = when {
        idle && ui.timer.phase == Phase.FOCUS -> "开始专注"
        idle -> "开始${ui.timer.phase.label}"
        ui.timer.paused -> "继续"
        else -> "暂停"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                when {
                    idle -> vm.start()
                    ui.timer.paused -> vm.resume()
                    else -> vm.pause()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(
                if (ui.timer.running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = vm::skip,
                enabled = !idle,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("跳过")
            }
            OutlinedButton(
                onClick = { vm.extend(5) },
                enabled = !idle,
                modifier = Modifier.weight(1f),
            ) { Text("+5 分钟") }
            OutlinedButton(
                onClick = vm::stop,
                enabled = !idle,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("结束")
            }
        }
    }
}

// ── 标签 ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSection(
    ui: FocusUiState,
    onToggle: (String) -> Unit,
    onNew: () -> Unit,
    onManage: () -> Unit,
) {
    val tags = ui.tags.map { it.name }
    // 课名候选默认收起：一次性铺 30 个芯片只会让人找不到重点
    var showCourseNames by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "标签",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onManage) { Text("管理") }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            tags.forEach { name ->
                FilterChip(
                    selected = ui.tagName == name,
                    onClick = { onToggle(name) },
                    label = { Text(name) },
                )
            }
            // 选中的是课名（不在标签表里）时也画出来，否则看不出自己选了什么
            if (ui.tagName.isNotBlank() && ui.tagName !in tags) {
                FilterChip(
                    selected = true,
                    onClick = { onToggle(ui.tagName) },
                    label = { Text(ui.tagName) },
                )
            }
            FilterChip(
                selected = false,
                onClick = onNew,
                label = { Text("＋ 新建") },
                leadingIcon = {
                    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(16.dp))
                },
            )
            if (ui.courseCandidates.isNotEmpty()) {
                FilterChip(
                    selected = showCourseNames,
                    onClick = { showCourseNames = !showCourseNames },
                    label = { Text(if (showCourseNames) "收起课名" else "从课表选") },
                )
            }
        }
        if (showCourseNames) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ui.courseCandidates.forEach { name ->
                    FilterChip(
                        selected = ui.tagName == name,
                        onClick = { onToggle(name) },
                        label = { Text(name) },
                    )
                }
            }
        }
        if (ui.tagName.isNotBlank()) {
            Text(
                text = "本次记录会记到「${ui.tagName}」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 标签管理：改名 / 删除。删除是归档 —— 历史记录里的标签名照旧显示 */
@Composable
private fun TagManageDialog(
    tags: List<FocusTagEntity>,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var editingId by remember { mutableStateOf<Long?>(null) }
    var editingText by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<FocusTagEntity?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理标签") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (tags.isEmpty()) {
                    Text("还没有标签，先新建一个。", style = MaterialTheme.typography.bodyMedium)
                }
                tags.forEach { tag ->
                    if (editingId == tag.id) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedTextField(
                                value = editingText,
                                onValueChange = { editingText = it.take(16) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    onRename(tag.id, editingText)
                                    editingId = null
                                },
                                enabled = editingText.trim().isNotEmpty(),
                            ) { Text("保存") }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = tag.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                editingId = tag.id
                                editingText = tag.name
                            }) { Text("改名") }
                            TextButton(onClick = { pendingDelete = tag }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )

    pendingDelete?.let { tag ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除标签「${tag.name}」？") },
            text = {
                Text(
                    text = "以后不再出现在选择列表里。已经记下的专注记录不受影响，" +
                        "那些记录里还会显示这个名字。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(tag.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

// ── 今日统计 ──────────────────────────────────────────────────────────────

@Composable
private fun TodayCard(ui: FocusUiState, onManual: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("今天", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onManual) {
                    Icon(Icons.Filled.EditNote, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("补录")
                }
            }
            Row(Modifier.fillMaxWidth()) {
                StatCell("专注时长", FocusStats.humanDuration(ui.summary.today.seconds), Modifier.weight(1f))
                StatCell("番茄", "${ui.summary.today.sessions} 个", Modifier.weight(1f))
                StatCell("本周", FocusStats.humanDuration(ui.summary.week.seconds), Modifier.weight(1f))
            }
            Text(
                text = "本月 ${FocusStats.humanDuration(ui.summary.month.seconds)} · " +
                    "本月番茄 ${ui.summary.month.sessions} 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "统计按凌晨 4 点划分一天：学到凌晨 1 点算前一天晚上。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium)
    }
}

// ── 今天的记录 ────────────────────────────────────────────────────────────

@Composable
private fun TodayRecords(records: List<FocusRecord>, onDelete: (Long) -> Unit) {
    val focusRecords = records.filter { it.phase == Phase.FOCUS }
    if (focusRecords.isEmpty()) {
        Text(
            text = "今天还没有专注记录。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val fmt = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val zone = remember { ZoneId.systemDefault() }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "今天的记录",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        focusRecords.sortedByDescending { it.startedAt }.forEach { r ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = Instant.ofEpochMilli(r.startedAt).atZone(zone).format(fmt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(48.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = r.tagName.ifBlank { FocusStats.UNTAGGED },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = FocusStats.humanDuration(r.actualSeconds) +
                            if (r.completed) "" else " · 中断",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onDelete(r.id) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除这条记录",
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

// ── 弹窗 ──────────────────────────────────────────────────────────────────

@Composable
private fun TimerSettingsDialog(
    ui: FocusUiState,
    onDismiss: () -> Unit,
    onPreset: (TimerPreset) -> Unit,
    onSave: (Int, Int, Int, Int, Boolean, Boolean) -> Unit,
) {
    var focus by remember(ui.prefs.focusMinutes) { mutableStateOf(ui.prefs.focusMinutes.toString()) }
    var short by remember(ui.prefs.shortBreakMinutes) { mutableStateOf(ui.prefs.shortBreakMinutes.toString()) }
    var long by remember(ui.prefs.longBreakMinutes) { mutableStateOf(ui.prefs.longBreakMinutes.toString()) }
    var every by remember(ui.prefs.longBreakEvery) { mutableStateOf(ui.prefs.longBreakEvery.toString()) }
    var autoBreak by remember(ui.prefs.autoStartBreak) { mutableStateOf(ui.prefs.autoStartBreak) }
    var autoFocus by remember(ui.prefs.autoStartFocus) { mutableStateOf(ui.prefs.autoStartFocus) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("计时设置") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("预设", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    TimerPreset.entries.forEachIndexed { index, preset ->
                        SegmentedButton(
                            selected = ui.prefs.preset == preset,
                            onClick = { onPreset(preset) },
                            shape = SegmentedButtonDefaults.itemShape(index, TimerPreset.entries.size),
                        ) { Text(preset.label.substringBefore(' '), style = MaterialTheme.typography.labelSmall) }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("专注", focus, Modifier.weight(1f)) { focus = it }
                    NumberField("短休", short, Modifier.weight(1f)) { short = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("长休", long, Modifier.weight(1f)) { long = it }
                    NumberField("长休间隔", every, Modifier.weight(1f)) { every = it }
                }
                Text(
                    text = "单位：分钟。长休间隔 = 每完成几个番茄进一次长休息。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SwitchRow("专注结束自动开始休息", autoBreak) { autoBreak = it }
                SwitchRow("休息结束自动开始专注", autoFocus) { autoFocus = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    focus.toIntOrNull() ?: ui.prefs.focusMinutes,
                    short.toIntOrNull() ?: ui.prefs.shortBreakMinutes,
                    long.toIntOrNull() ?: ui.prefs.longBreakMinutes,
                    every.toIntOrNull() ?: ui.prefs.longBreakEvery,
                    autoBreak,
                    autoFocus,
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> onChange(raw.filter(Char::isDigit).take(3)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NewTagDialog(
    existing: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val duplicate = name.trim().isNotEmpty() && existing.any { it.equals(name.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建标签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(16) },
                    label = { Text("标签名") },
                    singleLine = true,
                    isError = duplicate,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (duplicate) {
                    Text(
                        text = "已经有同名标签了，直接在上面选它就行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.trim().isNotEmpty() && !duplicate,
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualRecordDialog(
    onDismiss: () -> Unit,
    onConfirm: (minutes: Int, daysBack: Int) -> Unit,
) {
    var minutes by remember { mutableStateOf("25") }
    var daysBack by remember { mutableStateOf(0) }
    val dayLabels = listOf("今天", "昨天", "前天")
    val presets = listOf(15, 25, 45, 60, 90)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("补录专注") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("哪一天", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    dayLabels.forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = daysBack == index,
                            onClick = { daysBack = index },
                            shape = SegmentedButtonDefaults.itemShape(index, dayLabels.size),
                        ) { Text(label) }
                    }
                }

                Text("多久（分钟）", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { m ->
                        FilterChip(
                            selected = minutes == m.toString(),
                            onClick = { minutes = m.toString() },
                            label = { Text("$m") },
                        )
                    }
                }
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { raw -> minutes = raw.filter(Char::isDigit).take(4) },
                    label = { Text("分钟") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "会记成一条已完成的专注，时长按你填的算。标签用当前选中的那个。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(minutes.toIntOrNull() ?: 0, daysBack) },
                enabled = (minutes.toIntOrNull() ?: 0) > 0,
            ) { Text("补录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
