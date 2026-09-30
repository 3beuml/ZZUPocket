package com.zzupocket.app.ui.campus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zzupocket.app.data.room.RoomBuilding
import com.zzupocket.app.data.room.RoomCatalogData
import com.zzupocket.app.data.room.decodeRoomId

/**
 * 宿舍房间选择器：**选楼栋 → 选房号 → 确认**，而不是让人手抄 `99-16--31-101`。
 *
 * 房号列表来自内置的房间表（assets/rooms.json，社区项目 ZZU-Electricity-Monitor 整理，MIT）：
 * 选中一个寝室号后，自动带出**照明**与**空调**两套表计的房间 ID
 * （同一栋楼里它们是两个独立的 unit，空调的房号通常还要多一个前导 8）。
 *
 * 房间表读不出来、或者表里没有你的楼栋时，右下角可以切到"手动填房间号"。
 */
@Composable
fun RoomPickerDialog(
    catalog: RoomCatalogData?,
    catalogError: String?,
    currentLight: String,
    currentAc: String,
    onDismiss: () -> Unit,
    onConfirm: (label: String, lightId: String, acId: String) -> Unit,
) {
    // 能从当前房间 ID 反查出楼栋与房号，就直接定位过去：打开就是你那栋楼的房号列表，
    // 当前房号标粗，省得每次从 59 栋楼里重新找一遍。
    val currentRoom = remember(currentLight, catalog) {
        decodeRoomId(currentLight)?.let { (area, bld, unit, room) ->
            catalog?.buildings
                ?.firstOrNull { it.area == area && it.building == bld && it.lightUnit == unit }
                ?.let { it to room }
        }
    }

    // 步骤：null = 选楼栋；选中楼栋 = 选房号；选中房号 = 确认
    var building by remember(currentRoom) { mutableStateOf(currentRoom?.first) }
    var room by remember { mutableStateOf<String?>(null) }
    var buildingQuery by remember { mutableStateOf("") }
    var roomQuery by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var manualLight by remember { mutableStateOf(currentLight) }
    var manualAc by remember { mutableStateOf(currentAc) }

    if (manual) {
        ManualRoomDialog(
            light = manualLight,
            ac = manualAc,
            onLightChange = { manualLight = it },
            onAcChange = { manualAc = it },
            onDismiss = onDismiss,
            onConfirm = { onConfirm("", manualLight.trim(), manualAc.trim()) },
        )
        return
    }

    val selectedBuilding = building
    val title = when {
        selectedBuilding == null -> "选择宿舍楼"
        room == null -> selectedBuilding.name
        else -> "确认房间"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    selectedBuilding == null -> BuildingStep(
                        catalog = catalog,
                        catalogError = catalogError,
                        query = buildingQuery,
                        onQueryChange = { buildingQuery = it },
                        onPick = { building = it },
                    )
                    room == null -> RoomStep(
                        building = selectedBuilding,
                        query = roomQuery,
                        highlight = currentRoom?.takeIf { it.first.name == selectedBuilding.name }?.second,
                        onQueryChange = { roomQuery = it },
                        onPick = { room = it },
                    )
                    else -> ConfirmStep(building = selectedBuilding, room = room!!)
                }
            }
        },
        confirmButton = {
            when {
                selectedBuilding != null && room != null -> {
                    val lightId = selectedBuilding.lightRoomId(room!!)
                    val acId = selectedBuilding.acRoomId(room!!)
                    TextButton(
                        enabled = lightId != null || acId != null,
                        onClick = {
                            onConfirm(
                                selectedBuilding.shortLabel(room!!),
                                lightId.orEmpty(),
                                acId ?: currentAc,
                            )
                        },
                    ) { Text("保存") }
                }
                else -> TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        dismissButton = {
            when {
                room != null -> TextButton(onClick = { room = null }) { Text("返回") }
                selectedBuilding != null -> TextButton(onClick = { building = null }) { Text("返回") }
                else -> TextButton(onClick = { manual = true }) { Text("手动填房间号") }
            }
        },
    )
}

@Composable
private fun BuildingStep(
    catalog: RoomCatalogData?,
    catalogError: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (RoomBuilding) -> Unit,
) {
    if (catalogError != null) {
        Text(
            text = "房间表读不出来：$catalogError\n可以点右下角手动填房间号。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    if (catalog == null) {
        Text(
            text = "读取房间表…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(
        text = "${catalog.campus} · 共 ${catalog.buildings.size} 栋楼。输入楼栋名或数字可以快速筛。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SearchField(
        value = query,
        onValueChange = onQueryChange,
        label = "搜楼栋（如 松园 / 15）",
        numeric = false,
    )
    val filtered = catalog.buildings.filter { b ->
        query.isBlank() || b.name.contains(query.trim())
    }
    LazyColumn(Modifier.heightIn(max = 380.dp)) {
        items(filtered, key = { it.name }) { b ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(b) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(b.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    text = "${b.roomCount} 间",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (filtered.isEmpty()) {
            item {
                Text(
                    text = "没找到这个楼栋，可以手动填房间号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun RoomStep(
    building: RoomBuilding,
    query: String,
    highlight: String?,
    onQueryChange: (String) -> Unit,
    onPick: (String) -> Unit,
) {
    Text(
        text = "选你宿舍的门牌号。选完会自动带出照明与空调两套表计。" +
            (highlight?.let { "（当前：$it）" } ?: ""),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SearchField(
        value = query,
        onValueChange = onQueryChange,
        label = "输入房号（如 101）",
        numeric = true,
    )
    val rooms = building.roomNumbers.filter { query.isBlank() || it.contains(query.trim()) }
    LazyColumn(Modifier.heightIn(max = 380.dp)) {
        // 一行 4 个，方便快速扫
        items(rooms.chunked(4)) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { number ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onPick(number) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = number,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (number == query.trim() || number == highlight) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                        )
                    }
                }
                // 补齐一行不足 4 个时的占位，保持列宽一致
                repeat(4 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        if (rooms.isEmpty()) {
            item {
                Text(
                    text = "这栋楼里没找到这个房号，可以点右下角手动填房间号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ConfirmStep(building: RoomBuilding, room: String) {
    val lightId = building.lightRoomId(room)
    val acId = building.acRoomId(room)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "${building.name} $room",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "照明：${lightId ?: "这栋楼的照明表里没有这个房号"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "空调：${acId ?: "这栋楼的空调表里没有这个房号（会保留当前设置）"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "同一栋楼里照明与空调是两套独立账户，余额不能互相抵用，所以两个都要有。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    numeric: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 兜底：直接填两串内部房间 ID（房间表失效、或楼栋不在表里时用） */
@Composable
private fun ManualRoomDialog(
    light: String,
    ac: String,
    onLightChange: (String) -> Unit,
    onAcChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动填房间号") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "房间 ID 形如 99-16--31-101（照明）与 99-16--32-8101（空调）：" +
                        "area-building--unit-room。不确定就不要手填，回到上一页选楼栋更稳。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = light,
                    onValueChange = { onLightChange(it.take(40)) },
                    label = { Text("照明房间 ID") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = ac,
                    onValueChange = { onAcChange(it.take(40)) },
                    label = { Text("空调房间 ID") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = light.isNotBlank() || ac.isNotBlank(),
                onClick = onConfirm,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
