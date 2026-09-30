package com.zzupocket.app.ui.campus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 「校园」= 分组入口页（空教室已按需求从「课表」移到这里）。
 *
 * 只放用户功能入口；开发期的接口自检收在「登录状态」页里，且只在 debug 包显示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusScreen(
    onOpenLogin: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenElectricity: () -> Unit = {},
    onOpenCard: () -> Unit = {},
    onOpenRooms: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("更多") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            GroupTitle("账号")
            EntryCard {
                Entry(Icons.Filled.Login, "登录 / 切换账号", "使用统一身份认证", onOpenLogin)
                Entry(Icons.Filled.Settings, "登录状态", "令牌有效期、本机数据统计", onOpenDiagnostics)
                Entry(Icons.Filled.Info, "关于", "数据来源、隐私与致谢", onOpenAbout)
            }

            GroupTitle("学习空间")
            EntryCard {
                Entry(Icons.Filled.MeetingRoom, "教室占用", "按楼看每间教室哪一节有课", onOpenRooms)
                Entry(Icons.Filled.EventSeat, "图书馆座位预约", "学校原版网页（需联网）", onOpenLibrary)
            }

            GroupTitle("生活服务")
            EntryCard {
                Entry(Icons.Filled.Bolt, "电费", "照明 + 空调 · 每日用量与电费", onOpenElectricity)
                Entry(Icons.Filled.CreditCard, "校园卡", "余额、日周月年消费、流水明细", onOpenCard)
            }

            GroupTitle("外观")
            EntryCard {
                Entry(Icons.Filled.Palette, "配色与深色模式", "跟随壁纸动态取色，或选内置配色", onOpenAppearance)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun EntryCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(content = { content() })
    }
}

@Composable
private fun Entry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
) {
    val enabled = onClick != null
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        },
        modifier = if (enabled) Modifier.clickable(onClick = onClick!!) else Modifier,
    )
}
