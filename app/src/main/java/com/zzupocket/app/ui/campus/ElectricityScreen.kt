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
import com.zzupocket.app.ui.components.GlassCard
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
import com.zzupocket.app.data.room.decodeRoomId
import com.zzupocket.app.domain.DayUsage
import com.zzupocket.app.domain.EnergyStats
import com.zzupocket.app.ui.components.RelativeTime
import java.time.format.DateTimeFormatter

private val DAY_FMT = DateTimeFormatter.ofPattern("M月d日")

/**
 * 两路表计是不是**不在同一栋楼**（可以这样，但要提醒一句）。
 *
 * 返回 null = 不用提醒：任一路没设、或两路解析出来是同一栋（同一个 area + building）。
 */
private fun differentBuildings(lightId: String?, acId: String?): Pair<String, String>? {
    val light = decodeRoomId(lightId) ?: return null
    val ac = decodeRoomId(acId) ?: return null
    return if (light.area == ac.area && light.building == ac.building) null else light.id to ac.id
}

/** 卡片上的房间名；没有就退回房间 ID，至少让人知道查的是哪一间 */
private fun lightLabelOf(meter: MeterUi?): String =
    meter?.let { if (it.roomLabel.isNotBlank()) it.roomLabel else it.roomId.ifBlank { "未设置" } }.orEmpty()

/**
 * 只有一路表计时显示哪一张卡：**哪一路设了房间号就用哪一路**（亿达填的是照明那一路）。
 */
private fun singleMeterOf(state: ElectricityUiState): MeterUi? =
    state.light?.takeIf { it.roomId.isNotBlank() } ?: state.ac?.takeIf { it.roomId.isNotBlank() }

/** 单表计模式下的图例/折线颜色：跟着实际有数据的那一路走（照明=主色、空调=第三色） */
@Composable
private fun singleMeterColor(state: ElectricityUiState): androidx.compose.ui.graphics.Color =
    if (state.light?.roomId?.isNotBlank() == true) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElectricityScreen(
    onBack: () -> Unit,
    vm: ElectricityViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showPrice by remember { mutableStateOf(false) }
    var showRooms by remember { mutableStateOf(false) }

    // 两路都空才算"还没设置"：有些楼栋只有一套表（留学生公寓那种整间一表、亿达学生公寓），
    // 而学校只返回一个绑定房间，另一路本来就填不上 —— 不能因此一直提示"还没设置"。
    val roomsMissing = state.light?.roomId.isNullOrBlank() && state.ac?.roomId.isNullOrBlank()

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
                RoomSetupCard(
                    reading = state.roomReading,
                    onOpen = { showRooms = true },
                    onReadFromSchool = vm::readRoomFromSchool,
                )
            }

            // 「从学校账户读取房间」的结果：成功/失败各一句，不做 toast（用户可能就在看这页）
            state.roomRead?.let { read ->
                Text(
                    text = read.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (read.ok) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            // 房间号口径：为什么不是"填一次就完事"，为什么有时要手抄
            Text(
                text = if (state.singleMeter) {
                    // 亿达那种整间一个表：照明/空调那套说法在它这里不适用，别拿来说
                    "房间号：这栋楼只有一个电表，所以这里只显示一个「电量」，没有照明/空调之分。" +
                        "房间号从学校电费页复制，或在设置里用「在线选房间」逐层选（房表里没有的楼栋只能这样选）。"
                } else {
                    "房间号：照明与空调是两套独立电表（余额不能互抵），不同楼栋的房号规律也不一样 —— " +
                        "App 按每栋楼的真实规律带出空调号（不是只有加 8 那一种），" +
                        "个别楼带不准（如荷园部分楼一间房有多个空调户、每人一个表），请以学校电费页显示的编号为准。" +
                        "房表里没有的楼栋（如亿达学生公寓）点齿轮进设置，用「从学校账户读取」或「在线选房间」。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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

            if (state.singleMeter) {
                // 单表计的楼（**只有亿达**那种"整间一个表"）：**保持两格骨架** ——
                // 左格放电量数字、右格放这一路的房间名/可用天数/数据截至。
                // 与双表计那一行长得一样，不突兀；也不会摆一个空的空调格。
                val meter = singleMeterOf(state)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    meter?.let {
                        MeterCard(it, state.price, title = "电量", modifier = Modifier.weight(1f))
                    }
                    MeterRoomCard(meter, Modifier.weight(1f))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.light?.let { MeterCard(it, state.price, Modifier.weight(1f)) }
                    state.ac?.let { MeterCard(it, state.price, Modifier.weight(1f)) }
                }
            }

            // 两路指向不同楼栋时说清楚 —— 这是允许的（换校区试查就是这样），
            // 但不说的话很容易看成"怎么查都显示我自己的寝室"
            differentBuildings(state.light?.roomId, state.ac?.roomId)?.let { (light, ac) ->
                Text(
                    text = "⚠️ 这两路现在不在同一栋楼：照明 ${lightLabelOf(state.light)}（$light），" +
                        "空调 ${lightLabelOf(state.ac)}（$ac）。如果只是查一下没问题；" +
                        "要改成自己宿舍，点右上角齿轮重选一次。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            GlassCard(Modifier.fillMaxWidth()) {
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

            GlassCard(Modifier.fillMaxWidth()) {
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
                        if (state.singleMeter) {
                            LegendItem("用电量", singleMeterColor(state))
                        } else {
                            LegendItem("照明", MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(14.dp))
                            LegendItem("空调", MaterialTheme.colorScheme.tertiary)
                        }
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
                                text = if (state.singleMeter) {
                                    "用电量 %.2f 度 · ¥%.2f".format(
                                        usage.total,
                                        EnergyStats.cost(usage.total, state.price),
                                    )
                                } else {
                                    "照明 %.2f 度 · ¥%.2f　空调 %.2f 度 · ¥%.2f".format(
                                        usage.light,
                                        EnergyStats.cost(usage.light, state.price),
                                        usage.ac,
                                        EnergyStats.cost(usage.ac, state.price),
                                    )
                                },
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

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "余额趋势",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (state.singleMeter) {
                        MeterBalanceChart(
                            series = state.lightBalance.ifEmpty { state.acBalance },
                            label = "电量",
                            color = singleMeterColor(state),
                        )
                        Text(
                            text = "圆圈 + 竖虚线 = 那天余额往上跳，也就是充值。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
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
            }

            if (state.dayRows.isNotEmpty()) {
                GlassCard(Modifier.fillMaxWidth()) {
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
                                    // 只有一路表计时右边已经写着总量了，这里再拆"照明/空调"是废话
                                    text = if (state.singleMeter) {
                                        ""
                                    } else {
                                        "照明 %.2f · 空调 %.2f".format(row.usage.light, row.usage.ac)
                                    },
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
            // 读完就关掉对话框：结果会显示在电费页上（成功与失败都在那儿说清楚）
            onReadFromSchool = {
                showRooms = false
                vm.readRoomFromSchool()
            },
            // 在线选房间：静态表里没有的楼栋（亿达学生公寓 area 106）只有这条路能选到
            onPickOnline = {
                showRooms = false
                vm.openOnlinePicker()
            },
        )
    }

    // 在线房间树（现从学校服务器取），逐层选到房间
    state.online?.let { online ->
        OnlineRoomDialog(
            ui = online,
            onPick = vm::onlineChoose,
            onBack = vm::onlineBack,
            onDismiss = vm::closeOnlinePicker,
        )
    }
}

@Composable
private fun MeterCard(
    meter: MeterUi,
    price: Double,
    modifier: Modifier = Modifier,
    /** 卡上的标题；只有一路表计时传「电量」（那栋楼只有一个表，叫照明/空调都不对） */
    title: String = meter.label,
) {
    GlassCard(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
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
                    // 用**这一路自己**的房间名，别拿两路共用的（见 MeterUi.roomLabel 的说明）
                    meter.roomLabel.isNotBlank() -> meter.roomLabel
                    meter.roomId.isBlank() -> "未设置房间"
                    else -> "房间 ${meter.roomId}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 单表计模式下右格那张卡：**这一路自己的信息**（房间名 / 可用天数 / 数据截至）。
 *
 * 为什么这么摆：单表计的楼（亿达）没有照明/空调两路，但**版式要与双表计那一行保持一致**
 * （两格并排），所以左格放数字、右格放信息；直接一张宽卡会显得空、突兀。
 */
@Composable
private fun MeterRoomCard(meter: MeterUi?, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "房间",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = meter?.roomLabel?.takeIf { it.isNotBlank() }
                    ?: meter?.roomId?.takeIf { it.isNotBlank() }
                    ?: "未设置",
                style = MaterialTheme.typography.bodyMedium,
            )
            meter?.daysLeft?.let {
                Text(
                    // 与左卡同一个口径（最近 N 天日均），只是这里没有数字干扰
                    text = "按最近 ${EnergyStats.DAYS_LEFT_WINDOW_DAYS} 天用量还能用 %.1f 天".format(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            meter?.fetchedAt?.let {
                Text(
                    text = "数据截至 ${RelativeTime.ago(it)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
 * 房间号是每个宿舍自己的（照明、空调还是两套独立表计），所以 App 不预置任何默认值。
 * 两条路：**从学校账户读取**（读账号当前绑定的房间，房表里没有的楼栋也能用 ——
 * 亿达学生公寓就是这样设的），或按内置房间表"选楼栋 → 选房号"。
 */
@Composable
private fun RoomSetupCard(
    reading: Boolean,
    onOpen: () -> Unit,
    onReadFromSchool: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("还没有设置宿舍房间", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "电费是按房间查的，每个人不一样，所以需要你选一次：\n" +
                    "① 最省事：从学校账户读取（就是学校电费页上显示的那个房间）；\n" +
                    "② 或者选宿舍楼 → 选你的房号（如 101），照明和空调两套表计会自动带出来。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onReadFromSchool, enabled = !reading) {
                    Text(if (reading) "读取中…" else "从学校账户读取")
                }
                TextButton(onClick = onOpen) { Text("选择宿舍房间") }
            }
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
