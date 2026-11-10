package com.zzupocket.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.prefs.HomeModule
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.domain.EnergyStats
import com.zzupocket.app.ui.components.GlassCard
import com.zzupocket.app.ui.components.LocalShowDescriptions
import com.zzupocket.app.ui.nav.DOCK_CLEARANCE
import java.util.Locale

/**
 * 「首页」—— 今天的时间轴（上课 / 已完成的专注）+ 可开关、可拖动排序的功能卡片。
 * 时间轴只做"我这一天长什么样"，不做成就/打卡那套通用番茄钟的东西。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenLogin: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenElectricity: () -> Unit,
    onOpenCard: () -> Unit,
    onOpenLibrary: () -> Unit = {},
    onOpenRooms: () -> Unit = {},
    vm: TodayViewModel = viewModel(),
    timelineVm: TodayTimelineViewModel = viewModel(),
    cardVm: TodayCardViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val timeline by timelineVm.state.collectAsStateWithLifecycle()
    val card by cardVm.state.collectAsStateWithLifecycle()
    val prefs by AppGraph.uiPrefsStore.flow.collectAsStateWithLifecycle(initialValue = UiPrefs())

    // 从登录页返回、或从后台切回来时重新确认登录态并补一次刷新。
    // 少了这一步，登录成功回到首页仍会显示"未登录"——因为 ViewModel 不会重建。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.onResume()
        timelineVm.reload()
        cardVm.reload()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("首页") },
                actions = {
                    IconButton(onClick = onOpenDiagnostics) {
                        Icon(Icons.Filled.MoreHoriz, contentDescription = "登录状态")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                // 悬浮 dock 是覆盖层：内容从胶囊底下穿过，这里垫出尾部余量
                .padding(bottom = DOCK_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            if (!state.loggedIn && state.light.kwh == null && state.ac.kwh == null) {
                LoginPromptCard(onOpenLogin)
            }

            TodayTimelineCard(state = timeline, onOpenSchedule = onOpenSchedule)

            TodayCustomScheduleCard(
                entries = timeline.customEntries,
                onOpenSchedule = onOpenSchedule,
            )

            // 功能卡片：顺序与显隐由「设置 → 界面 → 首页内容」决定
            prefs.homeModuleOrder.forEach { module ->
                if (module in prefs.homeModulesHidden) return@forEach
                when (module) {
                    HomeModule.LIBRARY -> LibraryEntryCard(
                        icon = Icons.Filled.LocalLibrary,
                        title = "图书馆 · 座位预约",
                        subtitle = "学校原版网页",
                        onOpen = onOpenLibrary,
                    )
                    HomeModule.ROOMS -> LibraryEntryCard(
                        icon = Icons.Filled.MeetingRoom,
                        title = "空教室查询",
                        subtitle = "按楼看每间教室哪节有课",
                        onOpen = onOpenRooms,
                    )
                    HomeModule.ELECTRICITY -> ElectricityCard(
                        state = state,
                        onRefresh = vm::refresh,
                        onOpenLogin = onOpenLogin,
                        onOpen = onOpenElectricity,
                    )
                    HomeModule.CARD -> TodayCardBalanceCard(state = card, onOpen = onOpenCard)
                }
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun LoginPromptCard(onOpenLogin: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("未登录", style = MaterialTheme.typography.titleMedium)
            Text("登录后可以查看电费、校园卡、课表与图书馆。", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpenLogin) {
                Icon(Icons.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("登录")
            }
        }
    }
}

@Composable
private fun ElectricityCard(
    state: TodayUiState,
    onRefresh: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpen: () -> Unit,
) {
    val allEmpty = state.light.kwh == null && state.ac.kwh == null

    GlassCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text("宿舍电量", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                }
            }

            if (allEmpty && state.light.error == null && state.ac.error == null) {
                Text(
                    if (state.loggedIn) "还没有读数，点右上角刷新试试" else "登录后查看",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!state.loggedIn) {
                    OutlinedButton(onClick = onOpenLogin) { Text("去登录") }
                }
                return@Column
            }

            // 只有一路表计的楼就显示一行「电量」，别摆一个空的空调行
            val single = (state.light.roomId.isNotBlank() && state.ac.roomId.isBlank()) ||
                (state.light.roomId.isBlank() && state.ac.roomId.isNotBlank())
            if (single) {
                val meter = state.light.takeIf { it.roomId.isNotBlank() } ?: state.ac
                MeterRow(icon = Icons.Filled.Bolt, label = "电量", meter = meter)
            } else {
                MeterRow(icon = Icons.Filled.Lightbulb, label = "照明", meter = state.light)
                MeterRow(icon = Icons.Filled.AcUnit, label = "空调", meter = state.ac)
            }

            val avgParts = buildList {
                if (state.light.roomId.isNotBlank()) add("照明 ${formatKwh(state.lightAvgDaily)} 度")
                if (state.ac.roomId.isNotBlank()) add("空调 ${formatKwh(state.acAvgDaily)} 度")
            }
            if (avgParts.isNotEmpty()) {
                Text(
                    text = "最近平均每天 " + avgParts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            listOfNotNull(state.light.error, state.ac.error).distinct().forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun MeterRow(icon: ImageVector, label: String, meter: MeterUi) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(48.dp))

        if (meter.kwh == null) {
            Text(
                text = if (meter.error != null) "查询失败" else "—",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "${formatKwh(meter.kwh)} 度",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.width(8.dp))
            if (meter.kwh < 0) {
                Text(
                    "已欠费",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (meter.kwh < EnergyStats.LOW_KWH) {
                Text(
                    "电量偏低",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun formatKwh(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.2f", v)

/** 快速入口卡（首页）：一行紧凑卡，「设置」页 Entry 的首页变体。 */
@Composable
private fun LibraryEntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onOpen: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (LocalShowDescriptions.current) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
