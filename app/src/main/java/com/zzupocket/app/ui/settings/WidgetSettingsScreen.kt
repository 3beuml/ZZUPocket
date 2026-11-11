package com.zzupocket.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.ui.components.DescriptionText
import com.zzupocket.app.ui.widget.WidgetSchedule
import com.zzupocket.app.ui.widget.Widgets
import kotlinx.coroutines.launch

/**
 * 「更多 → 小组件设置」：桌面各小组件显示内容的集中调整页。
 *
 * 每个开关写入后立即 `Widgets.refreshAll` 重画所有组件——设置页就摆在用户眼前，
 * "改了没变化"比多一次重画更伤。所有项都是显示层偏好：原始课表数据不动，
 * 关掉的地点/时刻随时可以再打开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by AppGraph.uiPrefsStore.flow.collectAsStateWithLifecycle(initialValue = UiPrefs())

    fun set(block: suspend () -> Unit) {
        scope.launch {
            block()
            Widgets.refreshAll(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("小组件设置") },
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
            GroupTitle("通用")
            SettingsCard {
                ListItem(
                    headlineContent = { Text("时间显示方式") },
                    supportingContent = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            FilterChip(
                                selected = !prefs.widgetTimeAsPeriod,
                                onClick = { set { AppGraph.uiPrefsStore.setWidgetTimeAsPeriod(false) } },
                                label = { Text("时刻") },
                            )
                            FilterChip(
                                selected = prefs.widgetTimeAsPeriod,
                                onClick = { set { AppGraph.uiPrefsStore.setWidgetTimeAsPeriod(true) } },
                                label = { Text("节次") },
                            )
                        }
                    },
                )
                DescriptionItem(
                    title = "显示地点",
                    description = "关掉后 2×2、4×2 的行里不再显示教室",
                    checked = prefs.widgetShowPlace,
                    onChange = { v -> set { AppGraph.uiPrefsStore.setWidgetShowPlace(v) } },
                )
                DescriptionItem(
                    title = "显示时间段",
                    description = "关掉后行里不再显示时间（时刻或节次）",
                    checked = prefs.widgetShowTime,
                    onChange = { v -> set { AppGraph.uiPrefsStore.setWidgetShowTime(v) } },
                )
            }

            GroupTitle("2×2 下一节课")
            SettingsCard {
                ListItem(
                    headlineContent = { Text("行样式") },
                    supportingContent = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            listOf(
                                WidgetSchedule.ROW_MODE_AUTO to "自动",
                                WidgetSchedule.ROW_MODE_STACKED to "满档",
                                WidgetSchedule.ROW_MODE_COMPACT to "紧凑",
                                WidgetSchedule.ROW_MODE_MINI to "单行",
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = prefs.widgetRowMode == value,
                                    onClick = { set { AppGraph.uiPrefsStore.setWidgetRowMode(value) } },
                                    label = { Text(label) },
                                )
                            }
                        }
                    },
                )
                DescriptionText(
                    "自动 = 按画布高度选档，空间不足自动降级，两节课永不消失；" +
                        "满档 = 大字号竖排三行（画布矮时回退自动）；" +
                        "紧凑 = 小字号三行（课名/地点/时间各一行）；单行 = 课名 + 时间，最省高度",
                )
            }

            GroupTitle("4×2 今明课表")
            SettingsCard {
                ListItem(
                    headlineContent = { Text("行样式") },
                    supportingContent = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            listOf(
                                WidgetSchedule.ROW_MODE_AUTO to "标准",
                                WidgetSchedule.ROW_MODE_STACKED to "竖排",
                                WidgetSchedule.ROW_MODE_MINI to "单行",
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = prefs.widgetTwoDayStyle == value,
                                    onClick = { set { AppGraph.uiPrefsStore.setWidgetTwoDayStyle(value) } },
                                    label = { Text(label) },
                                )
                            }
                        }
                    },
                )
                DescriptionText(
                    "标准 = 课名 + 「地点｜时间」同行，空间不足自动少画一行；" +
                        "竖排 = 课名/地点/时间各占一行；单行 = 课名 + 时间，最省高度。" +
                        "上方的「显示地点 / 显示时间段」开关对这一列同样生效",
                )
            }

            GroupTitle("周课表（4×5）")
            SettingsCard {
                DescriptionItem(
                    title = "格子内显示地点",
                    description = "关掉后格子里只显示课名（和时刻）",
                    checked = prefs.widgetWeekShowPlace,
                    onChange = { v -> set { AppGraph.uiPrefsStore.setWidgetWeekShowPlace(v) } },
                )
                DescriptionItem(
                    title = "格子内显示开始时刻",
                    description = "关掉后格子里不显示「10:10」这类开始时刻",
                    checked = prefs.widgetWeekShowTime,
                    onChange = { v -> set { AppGraph.uiPrefsStore.setWidgetWeekShowTime(v) } },
                )
                DescriptionItem(
                    title = "周末明细条",
                    description = "周六/日有课时，网格底部补一行完整时间地点；关掉后周末仍是网格空格子",
                    checked = prefs.widgetWeekendStrip,
                    onChange = { v -> set { AppGraph.uiPrefsStore.setWidgetWeekendStrip(v) } },
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun DescriptionItem(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { DescriptionText(description) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onChange)
        },
    )
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}
