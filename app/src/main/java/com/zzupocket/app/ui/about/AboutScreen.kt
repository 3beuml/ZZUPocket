package com.zzupocket.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.zzupocket.app.BuildConfig

/** ⚠️ 发布前把这里改成你自己的仓库地址 */
private const val REPO_URL = "https://github.com/3beuml/ZZUPocket"

/** 作者主页 */
private const val AUTHOR_URL = "https://github.com/3beuml"

/**
 * 「关于」页：**这个 App 是什么、数据从哪来、怎么用你的数据、开源与致谢**。
 *
 * 之前这里顶着一块「非官方项目」的声明（按用户要求已删除）——
 * 同样的边界说明保留在仓库 README 与 [数据与隐私] 卡里，产品内不再重复。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ZZUPocket", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "把学校分散的网页系统（课表、教室占用、电费、校园卡、图书馆座位）" +
                            "整理成一个原生 Android App。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            AboutCard("数据与隐私") {
                Bullet("不保存你的密码。登录只走学校统一身份认证，本机仅保存加密后的登录令牌。")
                Bullet("课表、专注记录、电费采样等数据只存在本机数据库，没有自建服务器，也不会上传。")
                Bullet("退出登录会清除本机令牌；卸载 App 会一并清掉所有本地数据。")
                Bullet("只读接口：不做电费充值、不做校园卡转账、不做自动抢座。")
            }

            AboutCard("数据从哪来") {
                Bullet("课表 / 教室占用：教务系统（按真实日期匹配，调休调课也能对上）。")
                Bullet("电费：一卡通平台。学校不提供历史用电量接口，所以历史曲线由本机定时采样推算。")
                Bullet("校园卡：一卡通平台消费流水（本地留存，只增不改）。")
                Bullet("图书馆座位：内嵌学校原版网页。")
            }

            AboutCard("开源与致谢") {
                Text(
                    text = "本项目以 MIT 许可证开源，接口协议参考/对齐了以下社区项目：",
                    style = MaterialTheme.typography.bodySmall,
                )
                LinkRow("Illustar0/ZZU.Py（MIT）", "https://github.com/Illustar0/ZZU.Py", uriHandler)
                LinkRow(
                    "Elykia093/ZZU-Electricity-Monitor（MIT）",
                    "https://github.com/Elykia093/ZZU-Electricity-Monitor",
                    uriHandler,
                )
                LinkRow(
                    "rimrose/ZZU-Dorm-Electricity-Balance-Base（MIT）",
                    "https://github.com/rimrose/ZZU-Dorm-Electricity-Balance-Base",
                    uriHandler,
                )
                TextButton(onClick = { uriHandler.openUri(AUTHOR_URL) }) { Text("作者主页（GitHub）") }
                TextButton(onClick = { uriHandler.openUri(REPO_URL) }) { Text("项目仓库") }
            }

            Text(
                text = "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Text(
        text = "· $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LinkRow(label: String, url: String, uriHandler: androidx.compose.ui.platform.UriHandler) {
    TextButton(onClick = { uriHandler.openUri(url) }) { Text(label) }
}
