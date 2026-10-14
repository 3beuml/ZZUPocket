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
import androidx.compose.material3.FilterChip
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
import com.zzupocket.app.data.room.decodeRoomId

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
    /** 在线选房间（学校房间树，覆盖静态表没有的楼栋，如亿达学生公寓）；null = 不显示 */
    onPickOnline: (() -> Unit)? = null,
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
    // 一间房有多个空调户时，用户自己挑的那一个（荷园那种每人一个表）
    var acChoice by remember { mutableStateOf<String?>(null) }

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
                        onPickOnline = onPickOnline,
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
                        onPick = { room = it; acChoice = null },
                    )
                    else -> ConfirmStep(
                        building = selectedBuilding,
                        room = room!!,
                        acChoice = acChoice,
                        onAcChoice = { acChoice = it },
                    )
                }
            }
        },
        confirmButton = {
            when {
                selectedBuilding != null && room != null -> {
                    val lightId = selectedBuilding.lightRoomId(room!!)
                    val acNumbers = selectedBuilding.acRoomNumbersFor(room!!)
                    // 多个空调户时必须让用户选一个，不能默认取第一个（那是别人的表）
                    val needChoice = acNumbers.size > 1
                    val pickedAcNumber = if (needChoice) acChoice else acNumbers.firstOrNull()
                    val acId = selectedBuilding.acRoomIds(room!!)
                        .firstOrNull { it.endsWith("-$pickedAcNumber") }
                    TextButton(
                        enabled = (lightId != null || acId != null) && (!needChoice || pickedAcNumber != null),
                        onClick = {
                            onConfirm(
                                selectedBuilding.shortLabel(room!!),
                                lightId.orEmpty(),
                                acId ?: currentAc,
                            )
                        },
                    ) {
                        Text(if (needChoice && pickedAcNumber == null) "先选空调户" else "保存")
                    }
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
    onPickOnline: (() -> Unit)? = null,
) {
    if (onReadFromSchool != null || onPickOnline != null) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (onReadFromSchool != null) {
                TextButton(onClick = onReadFromSchool) { Text("从学校账户读取我的房间") }
            }
            if (onPickOnline != null) {
                TextButton(onClick = onPickOnline) { Text("在线选房间（含亿达公寓等）") }
            }
            Text(
                text = "两招都不用挑楼栋：前者读一次学校电费页上那个绑定房间；" +
                    "后者按「区域 → 楼栋 → 照明/空调 → 房间」从学校服务器现取，" +
                    "下面这份内置表里没有的楼栋（亿达学生公寓）就用它。",
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
private fun ConfirmStep(
    building: RoomBuilding,
    room: String,
    acChoice: String?,
    onAcChoice: (String) -> Unit,
) {
    val lightId = building.lightRoomId(room)
    val acNumbers = building.acRoomNumbersFor(room)
    val acIds = building.acRoomIds(room)
    // 数据里查不出空调号（这栋楼的编号没有可靠规律，或这间确实没有空调户）→ 如实说，
    // 并指路「在线选房间」（那里是学校服务器给的**确切**房间号），而不是让人手抄
    val noAc = building.acUnit <= 0 || acNumbers.isEmpty()
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

        when {
            noAc -> Text(
                text = "空调：" + if (building.acUnit <= 0) {
                    "这栋楼没有空调表"
                } else {
                    "这栋楼的空调表里没有这个房号"
                } + "（会保留当前的空调房间号）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            acNumbers.size > 1 -> {
                Text(
                    text = "这间房有 ${acNumbers.size} 个空调户（每人一个表），选你自己的那个 —— " +
                        "选错了就是查到别人的电表：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    acNumbers.forEachIndexed { i, n ->
                        val selected = acChoice == n
                        FilterChip(
                            selected = selected,
                            onClick = { onAcChoice(n) },
                            label = { Text(if (i == 0) "$n（第 1 户）" else "$n（第 ${i + 1} 户）") },
                        )
                    }
                }
                acChoice?.let {
                    Text(
                        text = "空调房间 ID：${acIds.firstOrNull { id -> id.endsWith("-$it") } ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            else -> Text(
                text = "空调：${acIds.first()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (noAc && building.acUnit > 0) {
            Text(
                text = "要查空调，用「在线选房间」：那是一条条问学校服务器拿到的确切房间号，" +
                    "不用自己抄；或者照学校电费页显示的编号用「手动填房间号」填进来。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = "同一栋楼里照明与空调是两套独立账户，余额不能互相抵用。",
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
    val lightBad = light.isNotBlank() && decodeRoomId(light) == null
    val acBad = ac.isNotBlank() && decodeRoomId(ac) == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动填房间号") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "格式（两个短横线在中间，别写成三个或一个）：\n" +
                        "    区域-楼栋--层级-房间\n" +
                        "例：照明 99-16--31-101　空调 99-16--32-8101\n" +
                        "同一栋楼里照明与空调的层级号不同（这个例子 31 对 32），" +
                        "房间号也可能不同（空调常多一个前导 8，但不是所有楼都这样）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "最稳的抄法：学校电费页上那两行编号直接复制。\n" +
                        "房表里没有的楼栋（如亿达学生公寓）建议回上一页用「在线选房间」——" +
                        "那是问学校服务器要的确切房间号，不用抄。\n" +
                        "只有一套表的楼栋（如留学生公寓）只填一路就行。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = light,
                    onValueChange = { onLightChange(it.take(40)) },
                    label = { Text("照明房间 ID") },
                    singleLine = true,
                    isError = lightBad,
                    supportingText = if (lightBad) {
                        { Text(roomIdHint(light)) }
                    } else {
                        null
                    },
                )
                OutlinedTextField(
                    value = ac,
                    onValueChange = { onAcChange(it.take(40)) },
                    label = { Text("空调房间 ID") },
                    singleLine = true,
                    isError = acBad,
                    supportingText = if (acBad) {
                        { Text(roomIdHint(ac)) }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = (light.isNotBlank() || ac.isNotBlank()) && !lightBad && !acBad,
                onClick = onConfirm,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 房间 ID 哪里不对 —— 直接把原因说出来。
 *
 * 写代码时"格式错"最常见的就是**双横线的数量**（写成 `99-16-31-101` 或 `99-16---31-101`），
 * 光说"格式不正确"用户没法自查，所以这里逐条说。
 */
private fun roomIdHint(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return "还没填"
    if (t.count { it == '-' } < 3) return "短横线太少：应该是 区域-楼栋--层级-房间（例 99-16--31-101）"
    if (!t.contains("--")) return "少了中间的双横线：楼栋和层级之间是两个短横线（例 99-16--31-101）"
    if (t.contains("---")) return "中间多了一个短横线：楼栋和层级之间只要两个"
    val head = t.substringBefore("--")
    val tail = t.substringAfter("--")
    if (head.split("-").size != 2) return "前半段应该是 区域-楼栋 两段数字（例 99-16）"
    if (tail.split("-").size != 2) return "后半段应该是 层级-房间 两段（例 31-101）"
    if (!head.all { it.isDigit() || it == '-' }) return "区域和楼栋只能填数字"
    if (tail.substringBefore("-").toIntOrNull() == null) return "层级只能填数字（例 31）"
    return "房间 ID 不合法，照 99-16--31-101 这种格式填"
}
