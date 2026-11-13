package com.zzupocket.app.ui.campus

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.data.eas.EasClient
import com.zzupocket.app.domain.Period
import com.zzupocket.app.domain.RoomRow
import com.zzupocket.app.ui.components.DescriptionText
import com.zzupocket.app.ui.components.HintText
import com.zzupocket.app.ui.components.RelativeTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val ROOM_DATE_FMT = DateTimeFormatter.ofPattern("M月d日")

/** 只显示房间号（101 这种），所以列很窄，省下的宽度留给格子 */
private const val CODE_COL_WIDTH = 58

/** 两节连排分界分隔带的宽度，内嵌一道竖向虚线 */
private val UNIT_PAIR_GAP = 6.dp

/** 上/下午/晚上分界分隔带的宽度，内嵌一道竖向实线（比连排虚线高一级） */
private val UNIT_SESSION_GAP = 10.dp

/**
 * 节次分界的两种强度：PAIR = 两节连排（1-2 | 3-4 | …），SESSION = 上/下午/晚上。
 */
private enum class UnitDivider { PAIR, SESSION }

/** "800 表示 08:00" 存的是 HHmm，先换算成分钟再算时间差 */
private fun minutesOf(hhmm: Int) = hhmm / 100 * 60 + hhmm % 100

/**
 * 节次列分界从数据推导，不写死"上午 4 + 下午 4 + 晚上 2"：
 * dayPart（MORNING/AFTERNOON/EVENING，来自 /room/system/course-units）相邻变了
 * 就是会话分界；dayPart 缺失（接口没给）时兜底看时间差——课间撑死半小时，
 * 午休/晚饭 ≥ 1 小时。时段分界会重置两两配对的计数，保证 1-2 | 3-4 不会
 * 跨过时段边界（比如上午只有 3 节时，第 3 节独自贴着时段线，而不是被配进
 * 下午）。
 */
private fun unitDividers(periods: List<Period>): List<UnitDivider?> {
    val marks = MutableList<UnitDivider?>(periods.size) { null }
    var sessionStart = 0
    for (i in 1 until periods.size) {
        val prev = periods[i - 1]
        val curr = periods[i]
        val newSession =
            (prev.dayPart.isNotBlank() && curr.dayPart.isNotBlank() && prev.dayPart != curr.dayPart) ||
                minutesOf(curr.startTime) - minutesOf(prev.endTime) >= 60
        if (newSession) {
            marks[i] = UnitDivider.SESSION
            sessionStart = i
        } else if ((i - sessionStart) % 2 == 0) {
            marks[i] = UnitDivider.PAIR
        }
    }
    return marks
}

/** 时段标签；dayPart 缺失时返回空串，标签行留空即可 */
private fun dayPartLabel(dayPart: String) = when (dayPart) {
    "MORNING" -> "上午"
    "AFTERNOON" -> "下午"
    "EVENING" -> "晚上"
    else -> ""
}

/** 会话分组（标签, 列数）：相邻节次之间没有 SESSION 分界即归同组 */
private fun sessionGroups(periods: List<Period>, marks: List<UnitDivider?>): List<Pair<String, Int>> {
    val groups = mutableListOf<Pair<String, Int>>()
    periods.forEachIndexed { index, period ->
        if (index == 0 || marks[index] == UnitDivider.SESSION) {
            groups += dayPartLabel(period.dayPart) to 1
        } else {
            val (label, count) = groups.last()
            groups[groups.lastIndex] = label to count + 1
        }
    }
    return groups
}

/**
 * 教室占用表。
 *
 * 一行一间教室，一排格子对应各节次：**填色 = 那节有课，空白 = 可以用**。
 * 列之间有两级分界线：两节之间（1-2 | 3-4 | …）一道窄虚线同一节次的判断把
 * "同一次课"圈在一起；上/下午/晚上之间一道更宽的竖向实线切段落（表头顶上
 * 也按组标出时段）。分界从节次表的 dayPart/时间差推导，不写死 4+4+2。
 * 按楼分组、楼内按教室号升序（低楼层自然在前）、不显示楼层标题。
 *
 * 工具栏刻意压到两行：日期（可点开日历）+ 筛选按钮 / 节次筛选 chips。
 * 校区不常换，收进筛选面板了。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RoomScreen(
    onBack: () -> Unit,
    /** 网关认证提示的"去补录密码"入口（已登录老用户没有登出功能，靠它才能到登录表单） */
    onOpenLogin: () -> Unit = {},
    vm: RoomViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val currentUnit = vm.currentUnitNo()
    // 节次分界（两节连排/时段）从 periods 推导：表头和数据行共用一份，行列不错位
    val unitMarks = remember(state.periods) { unitDividers(state.periods) }

    val shouldLoadMore by remember { derivedLoadMore(listState) }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) vm.loadMore() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("教室占用") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ── 第一行：日期（点开日历）+ 筛选 ──────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 顶部紧凑化：翻日键 48→40、图标 24→20（触摸面积保持 40+，省 ~16dp 高度）
                IconButton(onClick = { vm.shiftDate(-1) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "前一天", Modifier.size(20.dp))
                }
                Row(
                    // weight(fill=false)：空间富余时只占内容宽；紧张时**先收缩这一项**，
                    // 保住后面的「筛选」按钮不被挤成竖排（#11②，大字体机型实测）
                    Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { vm.openDatePicker() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.CalendarMonth,
                        contentDescription = "选择日期",
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = state.date.format(ROOM_DATE_FMT) +
                            if (state.date == LocalDate.now()) "（今天）" else "",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { vm.shiftDate(1) }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "后一天", Modifier.size(20.dp))
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = vm::openFilter) {
                    Icon(Icons.Filled.FilterList, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (state.selectedBuildings.isEmpty()) "筛选" else "筛选·${state.selectedBuildings.size}",
                        maxLines = 1,
                    )
                }
            }

            // ── 第二行：节次筛选（横向可滚）+ 汇总 ──────────────────────
            LazyRow(
                Modifier.fillMaxWidth().padding(start = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item {
                    FilterChip(
                        selected = state.selectedUnits.isEmpty(),
                        onClick = vm::clearUnits,
                        label = { Text("全部") },
                    )
                }
                items(state.periods.map { it.indexNo }) { no ->
                    FilterChip(
                        selected = no in state.selectedUnits,
                        onClick = { vm.toggleUnit(no) },
                        label = { Text("$no") },
                    )
                }
                item {
                    TextButton(onClick = vm::selectAllUnits) { Text("整天空") }
                }
            }

            // 顶部紧凑化：汇总与「来自本地快照」提示合并成一行（提示出现时整行可点刷新）。
            // 空教室是"此刻是否有课"的数据，得让人知道这是什么时候抓的，但不值得为它
            // 单开一行（原两行 ~32dp）——提示合进来，时间信息一条不少。
            Text(
                text = buildString {
                    state.notice?.let { append(it).append(" · ") }
                    append("${state.campusName} · ${state.buildingSummary} · ${state.unitSummary} · ")
                    append("显示 ${state.visibleCount} 间")
                    state.lastFetchedAt?.let { append(" · 数据截至 ").append(RelativeTime.timeOfDay(it)) }
                    if (state.notice != null) append(" · 点此刷新")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (state.notice == null) Modifier else Modifier.clickable { vm.refresh() })
                    .padding(horizontal = 12.dp),
            )

            state.error?.let { message ->
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    // 网关认证类错误：给"去补录密码"直达按钮——已登录老用户没有登出功能，
                    // 这是他们走到登录表单、让自动认证拿到密码的唯一路径
                    if (state.errorNeedsRelogin) {
                        TextButton(onClick = onOpenLogin, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text("去补录密码（登录一次即可，之后自动恢复）")
                        }
                    }
                }
            }

            UnitHeaderRow(state.periods, unitMarks, currentUnit)

            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                state.groups.forEach { (buildingName, rooms) ->
                    item(key = "b-$buildingName") { BuildingHeader(buildingName, rooms.size) }
                    items(rooms, key = { "r-${it.id}" }) { room ->
                        RoomRowView(
                            room = room,
                            unitMarks = unitMarks,
                            currentUnit = currentUnit,
                            onLongPress = { vm.openRaw(room) },
                        )
                    }
                }
                if (state.loading || state.loadingMore) {
                    item { LoadingRow() }
                }
                if (state.visibleCount == 0 && !state.loading) {
                    item {
                        Text(
                            text = if (state.selectedUnits.isEmpty()) {
                                "没有数据。可能是所选楼栋/日期没有排课信息。"
                            } else {
                                "${state.unitSummary} 全都空着的教室暂时没找到（试试少选几节，或换一天）。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                item {
                    Column(Modifier.padding(16.dp)) {
                        // 图例只留一眼能对上的那句；分界线含义、原始字段入口进描述层。
                        // 「借用走学校流程」有防误用价值，常显。
                        HintText("填色 = 有课，空白 = 可用 · 教室借用请走学校流程")
                        DescriptionText(
                            "宽实线 = 上/下午/晚上分界，窄虚线 = 两节连排分界；长按教室号可看接口原始字段" +
                                (if (state.hiddenVirtual > 0) "；虚拟教室（${state.hiddenVirtual}）未显示" else "")
                        )
                    }
                }
            }
        }
    }

    if (state.showFilter) {
        FilterSheet(
            campuses = state.campuses,
            campusId = state.campusId,
            buildings = state.buildings,
            selected = state.selectedBuildings,
            onCampus = vm::setCampus,
            onApply = { vm.setBuildings(it); vm.closeFilter() },
            onDismiss = vm::closeFilter,
        )
    }

    if (state.showDatePicker) {
        RoomDatePickerDialog(
            initial = state.date,
            range = state.semesterRange,
            onPick = vm::setDate,
            onDismiss = vm::closeDatePicker,
        )
    }

    state.rawRoom?.let { room ->
        RawRoomDialog(room = room, onDismiss = vm::closeRaw)
    }
}

/** 长按教室号：看接口原始字段（排查"显示成一串数字"这类问题时一眼定位） */
@Composable
private fun RawRoomDialog(room: RoomRow, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("接口原始字段") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RawLine("教室 id", room.id.toString())
                RawLine("nameZh", room.rawNameZh.ifBlank { "（空）" })
                RawLine("code", room.rawCode.ifBlank { "（空）" })
                RawLine("buildingId", room.buildingId.toString())
                RawLine("楼栋（字典）", room.buildingName)
                RawLine("校区", room.campusName.ifBlank { "（空）" })
                RawLine("当天占用条数", room.occupiedCount.toString())
                RawLine("显示的房间号", room.code)
                // 「nameZh 优先、code 兜底」是解析口径的备忘，写给开发者看的，不进用户界面
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun RawLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            ),
        )
    }
}

/** 日历：只允许在当前学期内选（翻到假期会看到空表，容易误以为坏了） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomDatePickerDialog(
    initial: LocalDate,
    range: Pair<LocalDate, LocalDate>?,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectable = remember(range) {
        if (range == null) {
            DatePickerDefaults.AllDates
        } else {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val day = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !day.isBefore(range.first) && !day.isAfter(range.second)
                }

                override fun isSelectableYear(year: Int): Boolean =
                    year >= range.first.year && year <= range.second.year
            }
        }
    }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = selectable,
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    onDismiss()
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DatePicker(state = pickerState, title = null)
    }
}

/** 表头：教室号列 + 各节次序号；序号上方按会话组标出上午/下午/晚上；当前时间的节次高亮 */
@Composable
private fun UnitHeaderRow(periods: List<Period>, marks: List<UnitDivider?>, currentUnit: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        if (periods.isNotEmpty()) {
            // ── 时段标签行：上午/下午/晚上按会话组居中，列数越多权重越大；
            // 组间垫实线分隔带同宽的空隙。组宽用纯 weight 对齐，与格子行相差的
            // 1-2dp 花在小标签的居中上，肉眼可忽略。
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(CODE_COL_WIDTH.dp))
                sessionGroups(periods, marks).forEachIndexed { index, (label, columns) ->
                    if (index > 0) Spacer(Modifier.width(UNIT_SESSION_GAP))
                    Text(
                        text = label,
                        // 行高压到字高（labelSmall 默认 16sp 行高）：表头收 ~3dp
                        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(columns.toFloat()),
                    )
                }
            }
        }
        // ── 节次序号行：与占格行同构（同权重格子 + 同宽分隔带），列列对齐 ──
        Row {
            Text(
                text = "教室",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(CODE_COL_WIDTH.dp),
            )
            periods.forEachIndexed { index, period ->
                marks.getOrNull(index)?.let { UnitDividerMark(height = 14.dp, kind = it) }
                Text(
                    text = "${period.indexNo}",
                    style = MaterialTheme.typography.labelSmall.copy(lineHeight = 13.sp),
                    color = if (period.indexNo == currentUnit) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (period.indexNo == currentUnit) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 节次列分界处的一道竖线，两级强度（视觉口径沿用课表网格：
 * outlineVariant + Compose 没有内置虚线、drawBehind + dashPathEffect 自绘这同一套）：
 * - SESSION（上/下午/晚上）：更宽的缝 + 实线，重一点的笔画才像"换段落"；
 * - PAIR（两节连排）：窄缝 + 虚线，把"同一次课"的两节圈在一起。
 * dash 用 3/3 比课表的 4/4 密一点——20dp 高的短线密节距才读得出"虚线"而非两点。
 */
@Composable
private fun UnitDividerMark(height: Dp, kind: UnitDivider) {
    val session = kind == UnitDivider.SESSION
    val lineColor = MaterialTheme.colorScheme.outlineVariant
        .copy(alpha = if (session) 0.75f else 0.45f)
    Box(
        Modifier
            .width(if (session) UNIT_SESSION_GAP else UNIT_PAIR_GAP)
            .height(height)
            .drawBehind {
                if (session) {
                    drawLine(lineColor, Offset(center.x, 0f), Offset(center.x, size.height), strokeWidth = 1.dp.toPx())
                } else {
                    drawLine(
                        lineColor,
                        Offset(center.x, 0f),
                        Offset(center.x, size.height),
                        strokeWidth = 0.7.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                    )
                }
            },
    )
}

@Composable
private fun BuildingHeader(name: String, count: Int) {
    Text(
        text = "$name（$count 间）",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomRowView(
    room: RoomRow,
    unitMarks: List<UnitDivider?>,
    currentUnit: Int,
    onLongPress: () -> Unit,
) {
    val occupiedColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
    val cells = if (room.occupied.size >= unitMarks.size) {
        room.occupied
    } else {
        room.occupied + List(unitMarks.size - room.occupied.size) { false }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = room.code,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier
                .width(CODE_COL_WIDTH.dp)
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
        )
        cells.forEachIndexed { index, occupied ->
            // 两节连排 = 窄虚线；上/下午/晚上 = 宽实线
            unitMarks.getOrNull(index)?.let { UnitDividerMark(height = 20.dp, kind = it) }
            Box(Modifier.weight(1f).height(20.dp).padding(horizontal = 1.dp)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (occupied) occupiedColor else emptyColor),
                )
                // 当前节次那一列盖一层浅色，扫一眼就知道"现在"
                if (index == currentUnit - 1) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(3.dp))
                            .background(highlight),
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}

/** 筛选面板：校区（单选）+ 楼栋（多选，北1–5/南1–5 置顶） */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    campuses: List<EasClient.CampusDto>,
    campusId: Long?,
    buildings: List<EasClient.BuildingDto>,
    selected: Set<Long>,
    onCampus: (Long) -> Unit,
    onApply: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember(selected, buildings) { mutableStateOf(selected) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
        ) {
            // ── 固定头部：应用 / 清空 永远看得见（61 栋楼时不用再滑到底）──
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("筛选", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onApply(picked) }, modifier = Modifier.weight(1f)) {
                        Text(if (picked.isEmpty()) "应用（全部）" else "应用（${picked.size} 栋）")
                    }
                    OutlinedButton(onClick = { picked = emptySet() }) { Text("清空") }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            // ── 以下可滚动 ──
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (campuses.size > 1) {
                    Text(
                        text = "校区",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        campuses.forEach { campus ->
                            FilterChip(
                                selected = campusId == campus.id,
                                onClick = {
                                    picked = emptySet()
                                    onCampus(campus.id)
                                },
                                label = { Text(campus.nameZh.ifBlank { campus.code }) },
                            )
                        }
                    }
                }

                Text(
                    text = "楼栋（北1–5、南1–5 已置顶；不选=全部 ${buildings.size} 栋）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    buildings.forEach { building ->
                        FilterChip(
                            selected = building.id in picked,
                            onClick = {
                                picked = if (building.id in picked) {
                                    picked - building.id
                                } else {
                                    picked + building.id
                                }
                            },
                            label = { Text(building.nameZh.ifBlank { building.code }) },
                        )
                    }
                }
            }
        }
    }
}

/** 滑到接近底部就请求下一页 */
private fun derivedLoadMore(state: LazyListState) = derivedStateOf {
    val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
    val total = state.layoutInfo.totalItemsCount
    total > 0 && last >= total - 3
}
