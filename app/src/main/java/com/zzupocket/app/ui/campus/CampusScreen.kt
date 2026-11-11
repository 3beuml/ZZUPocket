package com.zzupocket.app.ui.campus

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import com.zzupocket.app.ui.components.GlassCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.ui.components.DescriptionText
import com.zzupocket.app.ui.components.LocalShowDescriptions
import com.zzupocket.app.data.update.AppRelease
import com.zzupocket.app.data.update.AppUpdate
import com.zzupocket.app.data.update.UpdateCenter
import com.zzupocket.app.ui.nav.DOCK_CLEARANCE
import kotlinx.coroutines.launch

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
    onOpenWidgetSettings: () -> Unit = {},
) {
    var loggedIn by remember { mutableStateOf(AppGraph.authRepository.isLoggedIn()) }
    // 从登录页返回时重新读一次：这一页没有自己的 ViewModel，否则会一直显示"登录 / 切换账号"
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        loggedIn = AppGraph.authRepository.isLoggedIn()
    }

    // ── 检测更新：状态存放在 data.update.UpdateCenter（开屏已静默查过一次）──
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 「显示描述」是全局偏好：改一下 AppNav 根部的 CompositionLocal 就会全树重组
    val prefs by AppGraph.uiPrefsStore.flow.collectAsStateWithLifecycle(initialValue = UiPrefs())
    val currentVersion = remember { AppUpdate.localVersion(context) }
    val remoteRelease by UpdateCenter.release.collectAsState(initial = null)
    val mutedVersion by UpdateCenter.mutedVersion.collectAsState(initial = null)
    val showDot = AppUpdate.shouldShowDot(currentVersion, remoteRelease?.version, mutedVersion)
    var showUpdateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("更多") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                // 悬浮 dock 是覆盖层：垫出尾部余量，末尾条目能完整滚出胶囊
                .padding(bottom = DOCK_CLEARANCE),
        ) {
            GroupTitle("账号")
            EntryCard {
                // 登录态直接写进副标题，一个入口两种状态 ——
                // 之前"已登录 / 登录状态"两个条目登录后都指向同一个页，纯属重复。
                // 已登录时别把用户丢到登录表单上（issue #2 报告者提出）。
                Entry(
                    Icons.Filled.Person,
                    "账号",
                    // 登录态本身就是全部信息，页面里自然有「查看/退出」的动作，不必在副标题复述
                    if (loggedIn) "已登录" else "未登录 · 点这里登录",
                    if (loggedIn) onOpenDiagnostics else onOpenLogin,
                    // 登录态是状态，不是描述：关掉「显示描述」也必须留着
                    alwaysShowSubtitle = true,
                )
                Entry(Icons.Filled.Info, "关于", "数据来源、隐私与致谢", onOpenAbout)
            }

            GroupTitle("学习空间")
            EntryCard {
                Entry(Icons.Filled.MeetingRoom, "教室占用", "按楼查空教室", onOpenRooms)
                Entry(Icons.Filled.EventSeat, "图书馆座位预约", "学校原版网页", onOpenLibrary)
            }

            GroupTitle("生活服务")
            EntryCard {
                Entry(Icons.Filled.Bolt, "电费", "照明 + 空调用量", onOpenElectricity)
                Entry(Icons.Filled.CreditCard, "校园卡", "余额与流水", onOpenCard)
            }

            GroupTitle("外观")
            EntryCard {
                Entry(Icons.Filled.Palette, "配色与深色模式", "配色与深浅色", onOpenAppearance)
            }

            GroupTitle("界面")
            EntryCard {
                ListItem(
                    headlineContent = { Text("显示描述") },
                    // 开关自己这行说明也受控：关掉后整行只留标题 + 开关，不占副标题高度
                    supportingContent = if (prefs.showDescriptions) {
                        { DescriptionText("开启时显示原理、例外与错误原因") }
                    } else {
                        null
                    },
                    trailingContent = {
                        Switch(
                            checked = prefs.showDescriptions,
                            onCheckedChange = {
                                scope.launch { AppGraph.uiPrefsStore.setShowDescriptions(it) }
                            },
                        )
                    },
                )
            }

            GroupTitle("小组件")
            EntryCard {
                Entry(
                    Icons.Filled.SettingsInputAntenna,
                    "小组件设置",
                    "时间 / 地点 / 行样式 · 各桌面组件",
                    onOpenWidgetSettings,
                )
            }

            GroupTitle("系统")
            EntryCard {
                // 有新 release 时副标题直接给出结论 + 图标红点；否则留一句"手动检查"的引导。
                Entry(
                    Icons.Filled.SystemUpdate,
                    "检测更新",
                    if (showDot) {
                        "发现新版本 v${remoteRelease?.version} · 点击查看"
                    } else {
                        "当前 v$currentVersion · 点这里手动检查"
                    },
                    { showUpdateDialog = true },
                    // 版本号 / 有无新版是状态，不是描述
                    alwaysShowSubtitle = true,
                    badge = showDot,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showUpdateDialog) {
        UpdateCheckDialog(
            current = currentVersion,
            onDismiss = { showUpdateDialog = false },
            onMute = { version ->
                scope.launch { UpdateCenter.setUpdateMutedVersion(version) }
            },
        )
    }
}

@Composable
private fun GroupTitle(text: String) {
    // 与卡片的屏幕边距（16dp）对齐；今天/课表页的页边距也是 16dp，全局一个节奏
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun EntryCard(content: @Composable () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(content = { content() })
    }
}

@Composable
private fun Entry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    /** 登录态、版本号这类状态文案：即使关掉「显示描述」也要常显 */
    alwaysShowSubtitle: Boolean = false,
    /** 「有新东西」角标：图标右上角一枚小圆点（检测更新用） */
    badge: Boolean = false,
) {
    val enabled = onClick != null
    // 描述性副标题受开关控制；关掉时整个 supportingContent 为 null，
    // ListItem 收成单行高度，不是留一块空白。
    val shownSubtitle = subtitle.takeIf { alwaysShowSubtitle || LocalShowDescriptions.current }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (shownSubtitle != null) {
            {
                Text(
                    shownSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        } else {
            null
        },
        leadingContent = {
            Box {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
                if (badge) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-2).dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    )
                }
            }
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

/**
 * 「检测更新」弹窗：结果三个分支 —— 有新版 / 已是最新 / 网络不通。
 *
 * 权衡过不做的事：
 *  · 不在 App 内下 APK 再装包 —— 那要 REQUEST_INSTALL_PACKAGES 权限和安装确认流，先做到"跳浏览器"；
 *  · 下载直链优先用 release assets 的 .apk（GitHub CDN），没有或打不开就退 release 页。
 */
@Composable
private fun UpdateCheckDialog(
    current: String,
    onDismiss: () -> Unit,
    onMute: (String) -> Unit,
) {
    val context = LocalContext.current
    val release by UpdateCenter.release.collectAsState(initial = null)
    val phase by UpdateCenter.phase.collectAsState(initial = UpdateCenter.Phase.Idle)

    // 弹窗判断不受「不再提醒」影响：进来了就把当前最新版说完，红点是否还亮由入口条自己管。
    val hasNew = AppUpdate.shouldShowDot(current, release?.version, muted = null)

    fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(context, "没有找到可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    hasNew -> "发现新版本"
                    phase == UpdateCenter.Phase.Checking -> "正在检查更新"
                    else -> "检测更新"
                },
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    hasNew && release != null -> {
                        val r = release!!
                        Text(
                            text = "当前 v$current → 最新 v${r.version}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = r.notes.takeIf { it.isNotBlank() }
                                ?: "（这次 release 没写更新说明）",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(10.dp))
                        DescriptionText("下载需能访问 GitHub")
                        Spacer(Modifier.height(120.dp))
                    }

                    phase == UpdateCenter.Phase.Checking -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Text("正在连接 GitHub……", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(24.dp))
                    }

                    release != null -> {
                        // 查到了但不是更新（比当前版本旧/等）
                        Text(
                            "当前已是最新版本 v$current",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(24.dp))
                    }

                    else -> {
                        Text(
                            text = if (phase == UpdateCenter.Phase.Done) {
                                "连不上 GitHub"
                            } else {
                                "点「检查更新」检查一次"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (phase == UpdateCenter.Phase.Done) {
                            DescriptionText("校园网可能拦截，可换移动数据再试")
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        },
        confirmButton = {
            if (hasNew && release != null) {
                TextButton(onClick = { open(release!!.downloadUrl ?: release!!.pageUrl) }) {
                    Text("去下载")
                }
            } else {
                TextButton(
                    onClick = { UpdateCenter.checkExplicit() },
                    enabled = phase != UpdateCenter.Phase.Checking,
                ) {
                    Text(if (phase == UpdateCenter.Phase.Checking) "检查中…" else "检查更新")
                }
            }
        },
        dismissButton = {
            if (hasNew && release != null) {
                Row {
                    TextButton(onClick = {
                        onMute(release!!.version)
                        onDismiss()
                    }) {
                        Text("不再提醒此版本")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        },
    )
}
