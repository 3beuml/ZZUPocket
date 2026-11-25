package com.zzupocket.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.reminder.ClassReminderPlan
import com.zzupocket.app.reminder.ClassReminderScheduler
import com.zzupocket.app.ui.components.DescriptionText
import com.zzupocket.app.ui.components.GlassCard
import com.zzupocket.app.ui.components.HintText
import com.zzupocket.app.ui.today.TodayTimeline
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 「更多 → 学习空间 → 课程提醒」设置页。
 *
 * 只弹普通通知（默认提示音）
 * 开关默认关；打开时按需申请通知权限，并提示定时权限的授权状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassReminderSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by AppGraph.uiPrefsStore.flow.collectAsStateWithLifecycle(initialValue = UiPrefs())

    var nextPlan by remember { mutableStateOf<ClassReminderPlan?>(null) }
    var hasNotif by remember { mutableStateOf(ClassReminderScheduler.hasNotificationPermission(context)) }
    var canExact by remember { mutableStateOf(ClassReminderScheduler.canScheduleExact(context)) }

    // 提前分钟：表盘拖动 / 输入框共用的草稿，停下来后才写盘 + 重排（避免拖动时反复扫课表）
    val savedLead = prefs.classReminderLeadMinutes
    var leadText by remember { mutableStateOf(savedLead.toString()) }
    val draftLead = leadText.toIntOrNull()?.coerceIn(1, 60) ?: savedLead
    val savedLate = prefs.classReminderLateMinutes
    var lateText by remember { mutableStateOf(savedLate.toString()) }

    fun refreshState() {
        hasNotif = ClassReminderScheduler.hasNotificationPermission(context)
        canExact = ClassReminderScheduler.canScheduleExact(context)
        scope.launch {
            nextPlan = runCatching {
                AppGraph.uiPrefsStore.classReminderLastKey().let { last ->
                    ClassReminderScheduler.peekNext(leadMinutes = prefs.classReminderLeadMinutes, excludeKey = last)
                }
            }.getOrNull()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshState() }
    LaunchedEffect(prefs.classReminderEnabled, savedLead) { refreshState() }
    // 外部改了提前分钟（比如设置页重进）→ 同步回输入框
    LaunchedEffect(savedLead) {
        if (leadText.toIntOrNull() != savedLead) leadText = savedLead.toString()
    }
    // 本地草稿稳定 350ms 后落盘 + 重排
    LaunchedEffect(leadText) {
        val v = leadText.toIntOrNull()?.coerceIn(1, 60) ?: return@LaunchedEffect
        if (v == savedLead) return@LaunchedEffect
        delay(350)
        AppGraph.uiPrefsStore.setClassReminderLeadMinutes(v)
        ClassReminderScheduler.reschedule(context)
    }
    // 迟到容忍草稿稳定后落盘（只影响 Receiver 的丢弃阈值，不必重排闹钟）
    LaunchedEffect(lateText) {
        val v = lateText.toIntOrNull()?.coerceIn(0, 60) ?: return@LaunchedEffect
        if (v == savedLate) return@LaunchedEffect
        delay(350)
        AppGraph.uiPrefsStore.setClassReminderLateMinutes(v)
    }
    LaunchedEffect(savedLate) {
        if (lateText.toIntOrNull() != savedLate) lateText = savedLate.toString()
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasNotif = ClassReminderScheduler.hasNotificationPermission(context)
        if (it) scope.launch { ClassReminderScheduler.reschedule(context) }
        else Toast.makeText(context, "没有通知权限，提醒不会显示", Toast.LENGTH_SHORT).show()
    }

    fun setEnabled(v: Boolean) {
        scope.launch {
            AppGraph.uiPrefsStore.setClassReminderEnabled(v)
            ClassReminderScheduler.reschedule(context)
        }
        if (v && !hasNotif) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课程提醒") },
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
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("上课前提醒") },
                    supportingContent = { DescriptionText("在上课前弹出一条通知") },
                    trailingContent = {
                        Switch(checked = prefs.classReminderEnabled, onCheckedChange = { setEnabled(it) })
                    },
                )
            }

            Text("提前时间", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 表盘：拖动指针选分钟（1..60，精确到每一分钟）
                    LeadMinutesDial(minutes = draftLead) { leadText = it.toString() }
                    // 自定义输入：直接键入分钟数
                    OutlinedTextField(
                        value = leadText,
                        onValueChange = { s -> if (s.isEmpty() || s.all { it.isDigit() }) leadText = s },
                        placeholder = { Text("15") },
                        suffix = { Text("分钟") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DescriptionText("1–60 分钟任意设定；拖动表盘或直接输入，默认 15 分钟。")
                    OutlinedTextField(
                        value = lateText,
                        onValueChange = { s -> if (s.isEmpty() || s.all { it.isDigit() }) lateText = s },
                        label = { Text("迟到多久不再提醒") },
                        placeholder = { Text("5") },
                        suffix = { Text("分钟") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DescriptionText("系统定时延迟到上课后才触发时，超过这个时长就丢弃；0 表示课一开始就不提醒，默认 5 分钟。")
                }
            }

            // 开关关闭时，"下次提醒"整块（标题 + 卡片）都不显示
            if (prefs.classReminderEnabled) {
                Text("下次提醒", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp))
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        if (nextPlan == null) {
                            HintText("未来两周没有找到课程")
                        } else {
                            val p = nextPlan!!
                            Text(p.title, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${TodayTimeline.clock(timeOf(p.startAt))} · ${p.subtitle}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(4.dp))
                            HintText("将提前 ${prefs.classReminderLeadMinutes} 分钟弹通知")
                        }
                    }
                }
            }

            Text("权限", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    ListItem(
                        headlineContent = { Text("通知权限") },
                        supportingContent = { Text(if (hasNotif) "已开启" else "未开启") },
                        trailingContent = {
                            if (!hasNotif) {
                                TextButton(onClick = {
                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    runCatching { context.startActivity(intent) }
                                }) { Text("去开启") }
                            }
                        },
                    )
                    ListItem(
                        headlineContent = { Text("准时提醒权限") },
                        supportingContent = {
                            DescriptionText(
                                if (canExact) "已开启 · 提醒会准时弹出"
                                else "未开启 · 仍会提醒，但省电时系统可能延后几分钟"
                            )
                        },
                        trailingContent = {
                            if (!canExact) {
                                TextButton(onClick = {
                                    runCatching {
                                        context.startActivity(ClassReminderScheduler.exactAlarmSettingsIntent(context))
                                    }.onFailure {
                                        // 少数 ROM 没有这个设置页：退到应用详情
                                        runCatching {
                                            context.startActivity(
                                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                                    .setData(Uri.parse("package:${context.packageName}"))
                                            )
                                        }
                                    }
                                }) { Text("去开启") }
                            }
                        },
                    )
                }
            }

            DescriptionText(
                "提醒只依赖本机课表缓存，不联网、不常驻后台。上课时刻由系统定时唤醒，"
                    + "到点弹一条通知后自动排下一节；国产 ROM 若做了严格的省电限制，"
                    + "可能仍会延迟，建议把本 App 加入电池白名单。"
            )
        }
    }
}

/**
 * 提前分钟的圆形表盘：拖动指针选 1–60 分钟。
 * 顶部为 60/0 分位置，顺时针递增；中间显示当前值。
 */
@Composable
private fun LeadMinutesDial(minutes: Int, onMinutesChange: (Int) -> Unit) {
    val dialSize = 200.dp
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val activeColor = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(dialSize)
                .pointerInput(Unit) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    fun toMinutes(pos: Offset): Int {
                        var deg = Math.toDegrees(atan2((pos.y - cy).toDouble(), (pos.x - cx).toDouble())).toFloat()
                        deg = (deg + 90f + 360f) % 360f // 0° 落在顶部，顺时针
                        return (deg / 6f).roundToInt().coerceIn(1, 60)
                    }
                    detectDragGestures(
                        onDragStart = { onMinutesChange(toMinutes(it)) },
                        onDrag = { change, _ ->
                            change.consume()
                            onMinutesChange(toMinutes(change.position))
                        },
                    )
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.minDimension / 2f - 16.dp.toPx()
            val stroke = 12.dp.toPx()

            // 底盘圆环
            drawCircle(color = trackColor, radius = r, center = Offset(cx, cy), style = Stroke(stroke))
            // 已选进度弧（从顶部顺时针）
            drawArc(
                color = activeColor,
                startAngle = -90f,
                sweepAngle = minutes / 60f * 360f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            // 每分钟一个刻度，每 5 分钟加长
            for (m in 1..60) {
                val a = Math.toRadians((m / 60f * 360f - 90f).toDouble())
                val tickInner = r - 20.dp.toPx()
                val tickOuter = r - (if (m % 5 == 0) 30.dp.toPx() else 24.dp.toPx())
                drawLine(
                    color = if (m <= minutes) activeColor else tickColor,
                    start = Offset(cx + tickInner * cos(a).toFloat(), cy + tickInner * sin(a).toFloat()),
                    end = Offset(cx + tickOuter * cos(a).toFloat(), cy + tickOuter * sin(a).toFloat()),
                    strokeWidth = (if (m % 5 == 0) 2f else 1f),
                )
            }
            // 指针端点
            val a = Math.toRadians((minutes / 60f * 360f - 90f).toDouble())
            drawCircle(
                color = activeColor,
                radius = 8.dp.toPx(),
                center = Offset(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat()),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$minutes", style = MaterialTheme.typography.displaySmall)
            Text("分钟前", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** epoch 毫秒 → 当天第几分钟（仅用于把"下次提醒"格式化成 HH:mm） */
private fun timeOf(epochMs: Long): Int {
    val t = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime()
    return t.hour * 60 + t.minute
}
