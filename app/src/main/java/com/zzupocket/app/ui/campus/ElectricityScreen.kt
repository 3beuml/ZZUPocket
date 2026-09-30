package com.zzupocket.app.ui.campus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.domain.DayUsage
import com.zzupocket.app.domain.EnergyStats
import com.zzupocket.app.ui.components.RelativeTime
import java.time.format.DateTimeFormatter

private val DAY_FMT = DateTimeFormatter.ofPattern("M月d日")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElectricityScreen(
    onBack: () -> Unit,
    vm: ElectricityViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showPrice by remember { mutableStateOf(false) }
    var showRooms by remember { mutableStateOf(false) }

    // 房间号是每个宿舍自己的，没有默认值：没设置就先引导填
    val roomsMissing = state.light?.roomId.isNullOrBlank() || state.ac?.roomId.isNullOrBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("电费") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showRooms = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置宿舍房间")
                    }
                    IconButton(onClick = { showPrice = true }) {
                        Icon(Icons.Filled.Bolt, contentDescription = "电价设置")
                    }
                    IconButton(onClick = vm::refresh) {
                        if (state.loading) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            if (state.error != null) {
                Text(
                    text = state.error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (roomsMissing) {
                RoomSetupCard(onOpen = { showRooms = true })
            }

            // 口径说明：学校不给历史用电量，这里全是本机采样推出来的
            Text(
                text = "学校不提供历史用电量，以下数据由本机采样推算" +
                    "（打开 App 时自动采一次，另外约每 4 小时一次）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.lastSampleAt?.let {
                Text(
                    text = "最近一次采样：${RelativeTime.ago(it)}（共 ${state.sampleCount} 条读数）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                state.light?.let { MeterCard(it, state.price, state.roomLabel, Modifier.weight(1f)) }
                state.ac?.let { MeterCard(it, state.price, state.roomLabel, Modifier.weight(1f)) }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        BigStat(
                            label = "昨日消耗",
                            value = "%.2f 度".format(state.yesterday.total),
                            hint = "≈ %.2f 元".format(EnergyStats.cost(state.yesterday.total, state.price)),
                            modifier = Modifier.weight(1f),
                        )
                        BigStat(
                            label = "今日（截至现在）",
                            value = "%.2f 度".format(state.today.total),
                            hint = "≈ %.2f 元".format(state.todayCost),
                            modifier = Modifier.weight(1f),
                        )
                        BigStat(
                            label = "日均",
                            value = "%.2f 度".format(state.avgDaily),
                            hint = if (state.avgDaily > 0) {
                                "≈ %.2f 元/天".format(EnergyStats.cost(state.avgDaily, state.price))
                            } else {
                                "—"
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = "电价 %.3f 元/度（可在右上角改）".format(state.price),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "每日用电量",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        listOf(7, 30, 90).forEach { days ->
                            FilterChip(
                                selected = state.rangeDays == days,
                                onClick = { vm.setRange(days) },
                                label = { Text("$days 天") },
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LegendItem("照明", MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        LegendItem("空调", MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "柱顶 = 当天总量",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StackedUsageBarChart(
                        bars = state.bars,
                        selected = state.selectedDay,
                        onSelect = { vm.selectDay(it) },
                    )
                    DateAxisLabels(state.bars.map { it.day })
                    state.selectedDay?.let { day ->
                        val usage = state.selectedUsage ?: DayUsage()
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${day.monthValue} 月 ${day.dayOfMonth} 日",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "照明 %.2f 度 · ¥%.2f　空调 %.2f 度 · ¥%.2f".format(
                                    usage.light,
                                    EnergyStats.cost(usage.light, state.price),
                                    usage.ac,
                                    EnergyStats.cost(usage.ac, state.price),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "合计 %.2f 度 · ¥%.2f".format(
                                    usage.total,
                                    EnergyStats.cost(usage.total, state.price),
                                ) + if (day in state.estimatedDays) "（采样跨天，为估算值）" else "",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    Text(
                        text = "虚线柱 = 那天只有跨天或间隔过长的采样，数值是推算的；" +
                            "点柱子看当天明细，下面列表里点某天也会选中它。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "余额趋势",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    MeterBalanceChart(
                        series = state.lightBalance,
                        label = "照明",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    MeterBalanceChart(
                        series = state.acBalance,
                        label = "空调",
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        text = "两张图各自量纲（照明和空调差不多差 80 度，画在一起会把波动压平）。" +
                            "圆圈 + 竖虚线 = 那天余额往上跳，也就是充值。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.dayRows.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "按日明细（点一行可在上图高亮）",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        state.dayRows.forEach { row ->
                            val isSelected = row.day == state.selectedDay
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.selectDay(row.day) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = row.day.format(DAY_FMT),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.width(70.dp),
                                )
                                Text(
                                    text = "照明 %.2f · 空调 %.2f".format(row.usage.light, row.usage.ac),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                if (row.usage.estimated) {
                                    Text(
                                        text = "估算 ",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                                Text(
                                    text = "%.2f 度".format(row.usage.total),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "  ≈%.2f 元".format(row.cost),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPrice) {
        PriceDialog(
            current = state.price,
            onDismiss = { showPrice = false },
            onConfirm = { vm.setPrice(it); showPrice = false },
        )
    }

    if (showRooms) {
        RoomPickerDialog(
            catalog = state.roomCatalog,
            catalogError = state.roomCatalogError,
            currentLight = state.light?.roomId.orEmpty(),
            currentAc = state.ac?.roomId.orEmpty(),
            onDismiss = { showRooms = false },
            onConfirm = { label, light, ac ->
                vm.saveRooms(light, ac, label)
                showRooms = false
            },
        )
    }
}

@Composable
private fun MeterCard(
    meter: MeterUi,
    price: Double,
    roomLabel: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = meter.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = meter.kwh?.let { "%.2f 度".format(it) } ?: "—",
                style = MaterialTheme.typography.titleLarge,
            )
            meter.kwh?.let {
                Text(
                    text = "≈ %.2f 元".format(EnergyStats.cost(it, price)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            meter.daysLeft?.let {
                Text(
                    // 把口径写出来：桌面小组件上那句「可用约 N 天」是同一个数的取整，
                    // 而上面那张卡片的「日均」是**合计**用量（含更长的统计窗口），两者不是一回事
                    text = "按最近 ${EnergyStats.DAYS_LEFT_WINDOW_DAYS} 天用量还能用 %.1f 天".format(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = when {
                    roomLabel.isNotBlank() -> roomLabel
                    meter.roomId.isBlank() -> "未设置房间"
                    else -> "房间 ${meter.roomId}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, hint: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium)
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendDot(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * 没设置宿舍房间时的引导卡片。
 *
 * 房间号是每个宿舍自己的（照明、空调还是两套独立表计），所以 App 不预置任何默认值；
 * 内置房间表支持"选楼栋 → 选房号"，选完自动带出两套表计，不用手抄内部 ID。
 */
@Composable
private fun RoomSetupCard(onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("还没有设置宿舍房间", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "电费是按房间查的，每个人不一样，所以需要你选一次：\n" +
                    "选宿舍楼 → 选你的房号（如 101），照明和空调两套表计会自动带出来。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpen) { Text("选择宿舍房间") }
        }
    }
}

/** 填写/修改宿舍房间号（旧的手填入口，已并入 RoomPickerDialog 的"手动填房间号"分支） */

@Composable
private fun PriceDialog(current: Double, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var text by remember { mutableStateOf("%.3f".format(current)) }
    val parsed = text.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("电价") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { raw ->
                        text = raw.filter { it.isDigit() || it == '.' }.take(6)
                    },
                    label = { Text("元/度") },
                    singleLine = true,
                    isError = parsed == null || parsed <= 0.0,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "默认 0.568 元/度（河南居民合表电价）。学校的接口虽然有个" +
                        "\"电费单价\"字段，但实测值是空的，所以只能手填 —— " +
                        "填错只会影响\"折合多少钱\"，不影响度数统计。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = parsed != null && parsed > 0.0,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
