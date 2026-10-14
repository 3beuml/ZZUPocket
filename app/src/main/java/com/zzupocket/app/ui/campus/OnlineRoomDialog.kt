package com.zzupocket.app.ui.campus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import com.zzupocket.app.data.ecard.EcardClient

/** 层级名 → 界面上的说法 */
private fun levelTitle(type: String): String = when (type.lowercase()) {
    "bigarea" -> "大区"
    "area" -> "校区"
    "building" -> "楼栋"
    "unit" -> "单元"
    // 主校区这一层是"照明房间/空调用电"，但亿达、洛阳是**按楼层**分的（2层/3层…），
    // 所以标题不能写死成照明/空调
    "level" -> "表计 / 楼层"
    "room" -> "房间"
    "subarea" -> "分区"
    else -> type.ifBlank { "房间" }
}

/**
 * 列表区固定高度。
 *
 * ⚠️ 为什么必须固定：以前用 `heightIn(max = 360.dp)`，而且"载入时把整块内容换成一行
 * '正在读取…'"，于是**每换一层对话框都要缩放一次、位置还上下跳**（实测：楼栋层外框高 1820px、
 * 楼层/房间层 2210px，顶部差 195px）——用户看到的就是"这个界面一直在跳"。
 * 现在列表区高度恒定、转圈在同一块里占位、面包屑常驻一行、说明固定两行：
 * 换层时只有列表内容在换，外框一动不动。
 */
private val LIST_HEIGHT = 360.dp

/**
 * **在线选房间**：直接问学校的一卡通房间树（区域 → 楼栋 → 表计/楼层 → 房间），
 * 所以静态房间表里没有的楼栋也能选 —— 典型就是**亿达学生公寓（area 106）**。
 *
 * 交互要求（用户明确提过）：**点一下就是一次普通的选中**，界面不要跳。
 * 为此这里刻意把所有尺寸钉死：标题固定、面包屑常驻、说明固定两行、列表区固定 [LIST_HEIGHT]。
 */
@Composable
fun OnlineRoomDialog(
    ui: OnlinePickerUi,
    onPick: (EcardClient.LocationNode) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(ui.currentType) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("在线选房间") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 面包屑：常驻一行（第一层也占位），避免"往里走突然冒出来"的高度变化
                Text(
                    text = if (ui.steps.isEmpty()) {
                        "已选：（还没选）"
                    } else {
                        "已选：" + ui.steps.joinToString(" › ") { it.name }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                // 说明：固定两行，换层不改变行数（层级名放进正文，标题不再跟着变）
                Text(
                    text = "列表取自学校服务器（含静态表里没有的楼栋）。" +
                        "当前层级：${levelTitle(ui.currentType)}" +
                        if (ui.leaf) "，点一行就选中。" else "，点一行往里走。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 2,
                    maxLines = 2,
                )

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

                // 列表区：高度固定；载入时转圈也占这一整块（不再"缩一下再长回来"）
                Box(
                    modifier = Modifier.fillMaxWidth().height(LIST_HEIGHT),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        ui.loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        ui.error != null -> Unit
                        else -> {
                            val nodes = ui.nodes.filter {
                                query.isBlank() || it.name.contains(query.trim())
                            }
                            LazyColumn(Modifier.fillMaxWidth().height(LIST_HEIGHT)) {
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
