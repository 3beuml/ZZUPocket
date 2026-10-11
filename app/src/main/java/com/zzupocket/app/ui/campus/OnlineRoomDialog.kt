package com.zzupocket.app.ui.campus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import com.zzupocket.app.data.ecard.EcardClient

/** 层级名 → 界面上的说法 */
private fun levelTitle(type: String): String = when (type.lowercase()) {
    "bigarea" -> "大区"
    "area" -> "校区"
    "building" -> "楼栋"
    "unit" -> "单元"
    "level" -> "表计（照明 / 空调）"
    "room" -> "房间"
    "subarea" -> "分区"
    else -> type.ifBlank { "房间" }
}

/**
 * **在线选房间**：直接问学校的一卡通房间树（区域 → 楼栋 → 表计 → 房间），
 * 所以静态房间表里没有的楼栋也能选 —— 典型就是**亿达学生公寓（area 106）**：
 * 上游那份房间表从来没导出过它，主校区文件里也没有那些楼，只能这样选。
 *
 * 深度不写死：跟着服务端返回的 `nextLocationType` 走，走到 `end=true` 就是房间。
 * 面包屑可点「返回」退回上一层。
 */
@Composable
fun OnlineRoomDialog(
    ui: OnlinePickerUi,
    onPick: (EcardClient.LocationNode) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(ui.currentType) { mutableStateOf("") }

    val title = if (ui.steps.isEmpty()) {
        "在线选房间 · ${levelTitle(ui.currentType)}"
    } else {
        ui.steps.last().name
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "这份列表是从学校服务器现取的，静态房间表里没有的楼栋（例如亿达学生公寓）" +
                        "在这里能选到。当前层级：${levelTitle(ui.currentType)}" +
                        if (ui.leaf) "（就是房间，点一下即选中）" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 面包屑：已经走过的层级
                if (ui.steps.isNotEmpty()) {
                    Text(
                        text = "已选：" + ui.steps.joinToString(" › ") { it.name },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (ui.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = "  正在读取…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    return@Column
                }

                ui.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    return@Column
                }

                // 只有非叶子层才需要筛（房间层也可以筛，列表可能上千条）
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(if (ui.leaf) "输入房号筛一下" else "输入名字筛一下") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (ui.leaf) KeyboardType.Number else KeyboardType.Text,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                val nodes = ui.nodes.filter { query.isBlank() || it.name.contains(query.trim()) }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(nodes, key = { it.id + it.name }) { node ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(node) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = node.name.ifBlank { node.id },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (query.isNotBlank() && node.name == query.trim()) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (ui.leaf) "选这个" else "›",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (nodes.isEmpty()) {
                        item {
                            Text(
                                text = "这一层没有匹配的项。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            TextButton(onClick = onBack) { Text("返回上一层") }
        },
    )
}
