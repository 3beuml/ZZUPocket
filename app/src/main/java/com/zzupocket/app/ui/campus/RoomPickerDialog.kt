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
import com.zzupocket.app.data.room.LocatedRoom
import com.zzupocket.app.data.room.RoomBuilding
import com.zzupocket.app.data.room.RoomCampus
import com.zzupocket.app.data.room.RoomCatalogData

/**
 * 宿舍房间选择器：**选校区 → 选楼栋 → 选房号 → 确认**，而不是让人手抄 `99-16--31-101`。
 *
 * 房号列表来自内置的房间表（assets/rooms.json，社区项目 ZZU-Electricity-Monitor 整理，MIT）：
 * 选中一个寝室号后，自动带出**照明**与**空调**两套表计的房间 ID
 * （同一栋楼里它们是两个独立的 unit，空调的房号通常还要多一个前导 8）。
 *
 * ⚠️ 内置表覆盖 **6 个校区**（主校区 + 北/东/南/护理学院/洛阳），而"空调号 = 照明号加前导 8"
 * 只是**主校区的多数派规律**：
 *  - 荷园2–9号楼是"照明房号 + 一位住户序号"，**一间房每人一个空调户**（`302` → `3021/3022/3023`）；
 *  - 松园8/9/10/11/13号楼、北校区、东校区、南校区各有各的偏移。
 * 所以 [RoomBuilding.acGuessIsReliable] 为假时，确认页会提醒用户去学校电费页核对。
 * 表里没有的楼栋（例如**亿达学生公寓** area 106 —— 上游六个区域文件里没有它）用
 * "从学校账户读取"或手动填房间号。
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
    /** 从学校账户读取绑定房间（表里没有的楼栋靠它）；null = 不显示这个入口 */
    onReadFromSchool: (() -> Unit)? = null,
) {
    // 能从当前房间 ID 反查出校区/楼栋/房号，就在列表里标粗。**但不跳过"选校区"这一步** ——
    // 校区是这版新加的一层，要让它在界面上看得见（这一层也是给用户自测其他校区用的）。
    val current = remember(currentLight, catalog) { catalog?.locate(currentLight) }

    // 步骤：null = 选校区；选中校区 = 选楼栋；选中楼栋 = 选房号；选中房号 = 确认
    var campus by remember { mutableStateOf<RoomCampus?>(null) }
    var building by remember { mutableStateOf<RoomBuilding?>(null) }
    var room by remember { mutableStateOf<String?>(null) }
    var campusQuery by remember { mutableStateOf("") }
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

    val selectedCampus = campus
    val selectedBuilding = building
    val title = when {
        selectedCampus == null -> "选择校区"
        selectedBuilding == null -> selectedCampus.name
        room == null -> selectedBuilding.name
        else -> "确认房间"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    selectedCampus == null -> CampusStep(
                        catalog = catalog,
                        catalogError = catalogError,
                        currentCampusCode = current?.campus?.code,
                        query = campusQuery,
                        onQueryChange = { campusQuery = it },
                        onPick = { campus = it },
                        onReadFromSchool = onReadFromSchool,
                    )
                    selectedBuilding == null -> BuildingStep(
                        campus = selectedCampus,
                        current = current,
                        query = buildingQuery,
                        onQueryChange = { buildingQuery = it },
                        onPick = { building = it },
                    )
                    room == null -> RoomStep(
                        building = selectedBuilding,
                        query = roomQuery,
                        highlight = current
                            ?.takeIf { it.building.name == selectedBuilding.name }
                            ?.room,
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
                selectedCampus != null -> TextButton(onClick = { campus = null }) { Text("返回") }
                else -> TextButton(onClick = { manual = true }) { Text("手动填房间号") }
            }
        },
    )
}

/** 第一步：选校区。[RoomCampus.code] 就是房间 ID 的 area 段（99 / 101 / …）。 */
@Composable
private fun CampusStep(
    catalog: RoomCatalogData?,
    catalogError: String?,
    currentCampusCode: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (RoomCampus) -> Unit,
    onReadFromSchool: (() -> Unit)? = null,
) {
    if (onReadFromSchool != null) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = onReadFromSchool) { Text("从学校账户读取我的房间") }
            Text(
                text = "不用挑校区和楼栋：读一次学校电费页上那个绑定房间，直接填进来。" +
                    "房表里没有的楼栋（如亿达学生公寓）也能这样设。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
        text = "先选校区（房间号第一段就是它）：共 ${catalog.campuses.size} 个校区、" +
            "${catalog.buildings.size} 栋楼。\n" +
            "照明与空调是两套独立电表（余额不能互抵），房间号不一样；带出来的空调号请核对一遍。" +
            "表里没有的楼栋用上面的「从学校账户读取」。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SearchField(
        value = query,
        onValueChange = onQueryChange,
        label = "搜校区（如 北 / 洛阳）",
        numeric = false,
    )
    val filtered = catalog.campuses.filter { query.isBlank() || it.name.contains(query.trim()) }
    LazyColumn(Modifier.heightIn(max = 340.dp)) {
        items(filtered, key = { it.code }) { c ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(c) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = c.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (c.code == currentCampusCode) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${c.buildings.size} 栋 · ${c.roomCount} 间",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (filtered.isEmpty()) {
            item {
                Text(
                    text = "没找到这个校区，可以手动填房间号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun BuildingStep(
    campus: RoomCampus,
    current: LocatedRoom?,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (RoomBuilding) -> Unit,
) {
    Text(
        text = "${campus.name} · 共 ${campus.buildings.size} 栋楼。输入楼栋名或数字可以快速筛。" +
            "洛阳校区是按楼层分的（「1号宿舍楼 2层」），选自己那层。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SearchField(
        value = query,
        onValueChange = onQueryChange,
        label = "搜楼栋（如 松园 / 15）",
        numeric = false,
    )
    val filtered = campus.buildings.filter { b ->
        query.isBlank() || b.name.contains(query.trim())
    }
    LazyColumn(Modifier.heightIn(max = 340.dp)) {
        items(filtered, key = { it.name }) { b ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(b) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = b.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (current?.building?.name == b.name) {
                        FontWeight.Bold
                    } else {
                        FontWeight.Normal
                    },
                    modifier = Modifier.weight(1f),
                )
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
            (highlight?.let { "（当前：$it）" } ?: "") +
            "\n注意：有些楼栋（如荷园部分楼）一间房有多个空调户、每人一个表，" +
            "这里只能带出其中一个 —— 请到学校电费页核对你自己的编号。",
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
    LazyColumn(Modifier.heightIn(max = 340.dp)) {
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
    // 空调号不是"照明号加前导 8"对出来的 —— 说明这栋楼的编号规律不一样，
    // 提醒去学校电费页核对（见 RoomBuilding.acGuessIsReliable 的说明）
    val needsVerify = acId != null && !building.acGuessIsReliable(room)
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
        if (needsVerify) {
            Text(
                text = "⚠️ 这栋楼的空调编号不是常见的「照明号前加 8」，上面这个空调号是 App 按房号后缀猜的，" +
                    "可能对到别人的房间。请到学校电费页复制你自己的空调房间号，" +
                    "再用「手动填房间号」填进来；或者先只填照明。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
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
                        "area-building--unit-room。\n" +
                        "在学校电费页能看到属于自己的编号，照抄即可；房表里没有的楼栋" +
                        "（如亿达学生公寓）也可以上一页点「从学校账户读取」。\n" +
                        "不确定就不要手填，回到上一页选楼栋更稳。",
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
