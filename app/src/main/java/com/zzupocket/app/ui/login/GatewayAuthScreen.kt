package com.zzupocket.app.ui.login

import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.auth.NoxGatewayClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * 网关认证管理页（更多 → 网关认证）。
 *
 * 背景：学校调整了教务系统的访问方式，新增统一身份验证；会话过期会拦下所有教务
 * 请求（详见 [NoxGatewayClient]）。app 已能在有凭据时自动认证，这里提供：
 *  - 状态一览：凭据是否已存 / 最后自动认证时间 / 教务当前是否被拦
 *  - 「立即认证」：自动认证失败后的手动兜底（也是排查工具）
 *  - 「在 app 内打开认证网页」：走学校真实认证页（静默认证覆盖不了的场景）
 *  - 「去补录密码」：跳登录页（老用户已登录且无登出功能，这是唯一入口）
 *
 * 为什么不做成全自动不可见：门户要求图形验证码、设备绑定等分支仍可能出现，
 * 用户需要一个能自己动手的地方。
 */
class GatewayAuthViewModel : ViewModel() {
    private val gateway: NoxGatewayClient = AppGraph.noxGateway

    fun hasCredentials(): Boolean = gateway.hasStoredCredentials()

    fun lastAuthAt(): Long = AppGraph.tokenStore.noxAuthAt

    /** 探测教务是否被网关拦截（suspend：内部走 IO） */
    suspend fun probe(): NoxGatewayClient.GatewayProbe = gateway.probeJwxt()

    /**
     * 手动跑一次自动认证链；返回给界面的结果文案。
     *
     * ⚠️ 必须先探测再动手：设备已被信任时，网关会对重复登录直接 401——
     * 那是"无需认证"而不是失败（2026-11-13 真机实测：第一次登录 200 成功，
     * 随后的重跑全部 401，被误报成"认证未成功"）。
     */
    suspend fun authNow(): String {
        if (!gateway.hasStoredCredentials()) {
            return "还没有凭据：请先点「去补录密码」在 app 里登录一次"
        }
        return when (gateway.probeJwxt()) {
            NoxGatewayClient.GatewayProbe.OPEN ->
                "教务当前未被拦截（网关信任有效），无需认证"
            NoxGatewayClient.GatewayProbe.UNREACHABLE ->
                "当前网络连不上教务系统（非网关问题，请先检查网络后重试）"
            NoxGatewayClient.GatewayProbe.BLOCKED -> {
                val ok = gateway.authNow()
                if (!ok) {
                    "认证未成功（门户返回异常），稍后再试；详情见 logcat NoxGateway"
                } else {
                    when (gateway.probeJwxt()) {
                        NoxGatewayClient.GatewayProbe.OPEN -> "认证成功，已验证教务恢复可达"
                        else -> "登录请求已被网关接受，但教务仍不可达（网络波动，稍后自动重试）"
                    }
                }
            }
        }
    }
}

private val TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewayAuthScreen(
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
    vm: GatewayAuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val scope = rememberCoroutineScope()
    var hasCredentials by remember { mutableStateOf(vm.hasCredentials()) }
    var lastAuthAt by remember { mutableStateOf(vm.lastAuthAt()) }
    var probe by remember { mutableStateOf<NoxGatewayClient.GatewayProbe?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // 返回键防连点：popBackStack 是异步的，连点会弹两层（掉到别的 tab/空白）
    var backHandled by remember { mutableStateOf(false) }
    val goBack = {
        if (!backHandled) {
            backHandled = true
            onBack()
        }
        Unit
    }
    // 内嵌认证网页（WebView 兜底层）
    var showWebView by remember { mutableStateOf(false) }
    var webViewDone by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("网关认证") },
                navigationIcon = {
                    IconButton(onClick = goBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text("学校在教务系统前新增了一层统一身份验证（安全网关），验证过期后教务请求会被拦截。", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "app 已支持自动认证：登录过一次后，网关过期会自动恢复，无需手动处理。本页用于查看状态与手动兜底。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // ── 状态 ──
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusRow("自动认证凭据", if (hasCredentials) "已保存（登录时自动记录）" else "未保存——先点下方「去补录密码」")
                StatusRow(
                    "最后自动认证",
                    if (lastAuthAt > 0L) {
                        Instant.ofEpochMilli(lastAuthAt).atZone(ZoneId.systemDefault()).format(TIME_FMT)
                    } else {
                        "从未"
                    },
                )
                StatusRow(
                    "教务当前状态",
                    when (probe) {
                        null -> "点「检测」查看"
                        NoxGatewayClient.GatewayProbe.OPEN -> "可达（未被拦截）"
                        NoxGatewayClient.GatewayProbe.BLOCKED -> "被网关拦截（需要认证）"
                        NoxGatewayClient.GatewayProbe.UNREACHABLE -> "网络不可达"
                    },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            probe = vm.probe()
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("检测") }
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            message = vm.authNow()
                            hasCredentials = vm.hasCredentials()
                            lastAuthAt = vm.lastAuthAt()
                            probe = vm.probe()
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("立即认证") }
            }

            OutlinedButton(
                onClick = onOpenLogin,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (hasCredentials) "重新登录（更新自动认证用的密码）" else "去补录密码（登录一次即可）") }

            // ── 内嵌认证网页：静默认证的最后兜底 ──
            // 静默自动认证覆盖不了的场景（图形验证码、设备绑定、协议改版），
            // 在 app 里打开学校真实的认证页走一遍——页面本身天然兼容所有分支。
            // 认证完成的标志：页面跳回教务域名（jwxt.zzu.edu.cn）。
            OutlinedButton(
                onClick = { showWebView = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("在 app 内打开认证网页（自动认证失败时用）") }

            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(16.dp))
                    Spacer(Modifier.height(4.dp))
                    Text("  处理中…", style = MaterialTheme.typography.bodySmall)
                }
            }

            Text(
                "说明：自动认证失败的场景（如网关要求图形验证码、设备绑定、密码已修改）会在这里留痕；" +
                    "届时用上方「在 app 内打开认证网页」走一遍学校认证页，或重新登录 app 更新密码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // ── 内嵌认证网页层 ──
    // 从干净状态加载（清掉 WebView 自己的过期会话）；登录成功 = 页面跳回教务域名。
    // 成功后关闭并重新探测——此时设备信任已由网关标记，原生请求直接恢复。
    if (showWebView) {
        BackHandler(enabled = true) { showWebView = false }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("完成学校统一身份验证") },
                    navigationIcon = {
                        IconButton(onClick = { showWebView = false }) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭")
                        }
                    },
                )
            },
        ) { wpadding ->
            if (webViewDone) {
                Column(
                    Modifier.fillMaxSize().padding(wpadding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("认证完成 ✓", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "正在验证教务状态…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                scope.launch {
                    probe = vm.probe()
                    message = if (probe == NoxGatewayClient.GatewayProbe.OPEN) {
                        "网页认证生效：教务已恢复可达，回课表/空教室刷新即可"
                    } else {
                        "网页认证已提交，但教务仍未可达（网络波动，稍后再点「检测」）"
                    }
                    showWebView = false
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize().padding(wpadding),
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true // 登录页依赖 localStorage
                            // 清掉 WebView 自己可能残留的过期会话，从干净状态走
                            CookieManager.getInstance().removeAllCookies(null)
                            webViewClient = object : WebViewClient() {
                                override fun doUpdateVisitedHistory(
                                    view: WebView,
                                    url: String?,
                                    isReload: Boolean,
                                ) {
                                    // 认证成功的标志：被重定向回教务域名
                                    if (url != null && url.startsWith("https://jwxt.zzu.edu.cn")) {
                                        webViewDone = true
                                    }
                                }
                            }
                            loadUrl(NoxGatewayClient.DEFAULT_PORTAL_URL)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
