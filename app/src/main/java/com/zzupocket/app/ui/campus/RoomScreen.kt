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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.data.eas.EasClient
import com.zzupocket.app.domain.RoomRow
import com.zzupocket.app.ui.components.RelativeTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val ROOM_DATE_FMT = DateTimeFormatter.ofPattern("M月d日")

/** 只显示房间号（101 这种），所以列很窄，省下的宽度留给格子 */
private const val CODE_COL_WIDTH = 58

/**
 * 教室占用表。
 *
 * 一行一间教室，一排格子对应各节次：**填色 = 那节有课，空白 = 可以用**。
 * 按楼分组、楼内按教室号升序（低楼层自然在前）、不显示楼层标题。
 *
 * 工具栏刻意压到两行：日期（可点开日历）+ 筛选按钮 / 节次筛选 chips。
 * 校区不常换，收进筛选面板了。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RoomScreen(
    onBack: () -> Unit,
    vm: RoomViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val currentUnit = vm.currentUnitNo()

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
                IconButton(onClick = { vm.shiftDate(-1) }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "前一天")
                }
                Row(
                    Modifier
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
                    )
                }
                IconButton(onClick = { vm.shiftDate(1) }) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "后一天")
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = vm::openFilter) {
                    Icon(Icons.Filled.FilterList, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (state.selectedBuildings.isEmpty()) "筛选" else "筛选·${state.selectedBuildings.size}")
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

            Text(
                text = "${state.campusName} · ${state.buildingSummary} · ${state.unitSummary} · " +
                    "显示 ${state.visibleCount} 间" +
                    (state.lastFetchedAt?.let { " · 数据截至 ${RelativeTime.timeOfDay(it)}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            // 来自本地快照时的提示：一行灰字 + 时间。空教室是"此刻是否有课"的数据，
            // 必须让人知道这是什么时候抓的，但不用红字（红字像在说账号出问题了）。
            state.notice?.let { message ->
                Text(
                    text = "$message · 点此刷新",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.refresh() }
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            UnitHeaderRow(state.periods.map { it.indexNo }, currentUnit)

            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                state.groups.forEach { (buildingName, rooms) ->
                    item(key = "b-$buildingName") { BuildingHeader(buildingName, rooms.size) }
                    items(rooms, key = { "r-${it.id}" }) { room ->
                        RoomRowView(
                            room = room,
                            unitCount = state.periods.size,
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
                    Text(
                        text = "填色 = 那节有课；空白 = 那节空着可以用。" +
                            (if (state.hiddenVirtual > 0) "虚拟教室（${state.hiddenVirtual}）未显示。" else "") +
                            "长按教室号可看接口原始字段。教室借用请走学校流程，这里只做查询。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
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
                Text(
                    text = "房间号优先取 nameZh 的最后一段数字；code 不稳定（实测可能是 12 位内部编码），" +
                        "只在 nameZh 取不出时才用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
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

/** 表头：教室号列 + 各节次序号；当前时间的节次高亮 */
@Composable
private fun UnitHeaderRow(indexNos: List<Int>, currentUnit: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "教室",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(CODE_COL_WIDTH.dp),
        )
        indexNos.forEach { no ->
            Text(
                text = "$no",
                style = MaterialTheme.typography.labelSmall,
                color = if (no == currentUnit) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (no == currentUnit) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
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
    unitCount: Int,
    currentUnit: Int,
    onLongPress: () -> Unit,
) {
    val occupiedColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
    val cells = if (room.occupied.size >= unitCount) {
        room.occupied
    } else {
        room.occupied + List(unitCount - room.occupied.size) { false }
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
