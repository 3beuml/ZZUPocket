package com.zzupocket.app.ui.diag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.BuildConfig
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.auth.AuthRepository
import com.zzupocket.app.data.lib.LibProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FULL_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

data class DiagState(
    val status: AuthRepository.TokenStatus? = null,
    val lightRoomId: String = "",
    val acRoomId: String = "",
    val readingCount: Int = 0,
    val probing: Boolean = false,
    /** 一卡通接口自检的结果（原始文本，含字段结构），空串 = 还没跑过 */
    val probeReport: String = "",
    val probeError: String = "",
    val probingRoom: Boolean = false,
    /** 教务教室接口自检结果 */
    val roomReport: String = "",
    val roomError: String = "",
    val probingLib: Boolean = false,
    /** 图书馆传输层自检结果 */
    val libReport: String = "",
    val libError: String = "",
    val probingEasAuth: Boolean = false,
    /** 教务鉴权自检结果（令牌形态 + 各接口状态码 + 换鉴权形状的对照） */
    val easAuthReport: String = "",
    val easAuthError: String = "",
)

class DiagnosticsViewModel : ViewModel() {
    private val auth: AuthRepository = AppGraph.authRepository
    private val repo = AppGraph.electricityRepository

    private val _state = MutableStateFlow(DiagState())
    val state: StateFlow<DiagState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = repo.settings()
            // 房间号要显示**实际生效**的那两个：用户可能在房间表里选过（写在 DataStore 的
            // roomLabel / 偏好里），而 settings 里那两串是更早的手填值，可能一直是空的。
            // 直接用 settings 会出现「照明房间号：」后面空着、但电费页明明查得到读数这种怪状态。
            val (light, ac) = repo.effectiveRooms()
            _state.value = DiagState(
                status = auth.status(settings.account),
                lightRoomId = light.ifBlank { settings.lightRoomId.orEmpty() },
                acRoomId = ac.ifBlank { settings.acRoomId.orEmpty() },
                readingCount = repo.readingCount(),
            )
        }
    }

    /**
     * 一卡通新接口自检：把电费明细、交易流水、消费汇总这些接口挨个调一遍，
     * 打出状态码和**字段结构**，用来确认响应字段名（不打印任何令牌/密码）。
     */
    fun runEcardProbe() {
        if (_state.value.probing) return
        _state.update { it.copy(probing = true, probeReport = "", probeError = "") }
        viewModelScope.launch {
            val result = runCatching { AppGraph.ecardProbe.run() }
            _state.update {
                it.copy(
                    probing = false,
                    probeReport = result.getOrDefault(""),
                    probeError = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() }
                        .orEmpty(),
                )
            }
        }
    }

    fun clearProbe() = _state.update { it.copy(probeReport = "", probeError = "") }

    /**
     * 教务「教室」接口自检：定位"空教室 / 教室字典"的真实路径。
     * 目的是做"教室占用表"（一行一间教室、一排节次格子、有课涂色）。
     */
    fun runRoomProbe() {
        if (_state.value.probingRoom) return
        _state.update { it.copy(probingRoom = true, roomReport = "", roomError = "") }
        viewModelScope.launch {
            val result = runCatching { AppGraph.roomProbe.run() }
            _state.update {
                it.copy(
                    probingRoom = false,
                    roomReport = result.getOrDefault(""),
                    roomError = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() }
                        .orEmpty(),
                )
            }
        }
    }

    /**
     * 图书馆「传输层」自检：校外只能走明文 HTTP:80，先确认手机上通不通，
     * 以及需登录的接口是不是 cookie 会话（决定 WebView 登录器方案能否落地）。
     */
    fun runLibProbe() {
        if (_state.value.probingLib) return
        _state.update { it.copy(probingLib = true, libReport = "", libError = "") }
        viewModelScope.launch {
            val result = runCatching { LibProbe.run() }
            _state.update {
                it.copy(
                    probingLib = false,
                    libReport = result.getOrDefault(""),
                    libError = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() }
                        .orEmpty(),
                )
            }
        }
    }

    /**
     * 教务「鉴权」自检：定位"一卡通正常、唯独教务 401/403"。
     *
     * 起因是 2026-10-14 的用户报告 —— 统一身份认证与一卡通都正常，课表却始终
     * 「教务登录态已失效，请重新登录」，重新登录仍复现。教务网关拒绝时响应体是**空的**
     * （实测五种鉴权写法一律 401 + content-length 0），本地看不出原因，所以这里把原始事实摊开：
     * 令牌形态（长度 / exp / alg，**不含令牌值**）、每个在用接口的状态码与响应头、
     * 以及把同一条令牌换几种鉴权形状时的对照。**结果可以整段复制发给开发者。**
     */
    fun runEasAuthProbe() {
        if (_state.value.probingEasAuth) return
        _state.update { it.copy(probingEasAuth = true, easAuthReport = "", easAuthError = "") }
        viewModelScope.launch {
            // 课表接口要学期 id —— 那正是用户报告里失败的那个接口
            val semesterId = runCatching {
                AppGraph.scheduleRepository.semesters().firstOrNull()?.id
            }.getOrNull()
            val result = runCatching { AppGraph.easAuthProbe.run(semesterId) }
            _state.update {
                it.copy(
                    probingEasAuth = false,
                    easAuthReport = result.getOrDefault(""),
                    easAuthError = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() }
                        .orEmpty(),
                )
            }
        }
    }

    fun logout(onDone: () -> Unit) {
        auth.logout()
        onDone()
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    vm: DiagnosticsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val status = state.status

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录状态") },
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            StatusRow(
                label = "统一身份认证（CAS）",
                ok = status?.loggedIn == true,
                detail = when {
                    status == null -> "读取中…"
                    status.loggedIn -> "有效期至 ${status.casExpiresAt?.let { FULL_FMT.format(Date(it)) } ?: "未知"}"
                    else -> "未登录或令牌已过期，需要重新登录"
                },
            )
            StatusRow(
                label = "一卡通",
                ok = (status?.ecardTokenAt ?: 0L) > 0L,
                detail = if ((status?.ecardTokenAt ?: 0L) > 0L) {
                    "令牌获取于 ${FULL_FMT.format(Date(status!!.ecardTokenAt))}（每 45 分钟自动刷新）"
                } else {
                    "尚未获取，首次查询电费时自动换取"
                },
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("数据", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "照明房间号：${state.lightRoomId.ifBlank { "未设置" }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "空调房间号：${state.acRoomId.ifBlank { "未设置" }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("已存电量读数：${state.readingCount} 条", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "本机设备的统一认证标识：${AppGraph.deviceId}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "登录后在统一认证「安全中心」把这个设备设为可信设备，以后就不再需要短信验证码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "本 App 只保存登录令牌（加密存储），不保存你的密码。\n" +
                        "代价是令牌过期（约 1 个月）后需要重新登录一次。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }

            // ── 开发用自检：只在 debug 包里出现 ─────────────────────────────
            // 这三块是开发期用来摸接口字段结构的（会把状态码/字段名打出来），普通用户用不到，
            // 还会把内部接口名摊在界面上，所以 release 包不显示。
            // 结论都已整理进 docs/interfaces/，需要时用 debug 包跑一遍即可。
            if (BuildConfig.DEBUG) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("一卡通接口自检", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "P4 的电费图形化和校园卡消费要用几个新接口（用电明细、交易流水、" +
                                "逐日消费汇总）。这里把它们挨个调一遍，把状态码和字段结构打出来 —— " +
                                "用来确认响应字段名。结果不含任何令牌或密码。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.runEcardProbe() },
                                enabled = !state.probing,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (state.probing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.size(8.dp))
                                }
                                Text(if (state.probing) "自检中…" else "开始自检")
                            }
                            if (state.probeReport.isNotEmpty()) {
                                val clipboard = LocalClipboardManager.current
                                OutlinedButton(onClick = {
                                    clipboard.setText(AnnotatedString(state.probeReport))
                                }) { Text("复制结果") }
                            }
                        }
                        if (state.probeError.isNotEmpty()) {
                            Text(
                                text = "自检出错：${state.probeError}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (state.probeReport.isNotEmpty()) {
                            Text(
                                "结果（也可以直接说一声，我从 logcat 里取，tag = EcardProbe）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SelectionContainer {
                                Text(
                                    text = state.probeReport,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                )
                            }
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("教务教室接口自检", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "P5 要做「教室占用表」（一行一间教室、一排节次格子、有课涂色），" +
                                "需要两个接口：教室字典（校区→楼栋→教室）和某日期某节次的空闲教室列表。" +
                                "这两个路径目前未知，这里用已登录的教务令牌挨个试一批候选路径 —— " +
                                "200 = 命中，405 = 路径存在但要用 POST，404 = 不存在。" +
                                "第一项是对照组（课表在用的节次接口，必须成功）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.runRoomProbe() },
                                enabled = !state.probingRoom,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (state.probingRoom) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.size(8.dp))
                                }
                                Text(if (state.probingRoom) "自检中…" else "开始教室自检")
                            }
                            if (state.roomReport.isNotEmpty()) {
                                val clipboard = LocalClipboardManager.current
                                OutlinedButton(onClick = {
                                    clipboard.setText(AnnotatedString(state.roomReport))
                                }) { Text("复制结果") }
                            }
                        }
                        if (state.roomError.isNotEmpty()) {
                            Text(
                                text = "自检出错：${state.roomError}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (state.roomReport.isNotEmpty()) {
                            Text(
                                "结果（也可以直接说一声，我从 logcat 取，tag = RoomProbe）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SelectionContainer {
                                Text(
                                    text = state.roomReport,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                )
                            }
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("教务鉴权自检", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "课表报「教务登录态已失效」、而一卡通正常、重新登录也没用时，跑这个。" +
                                "它会报告令牌形态（长度 / exp / alg，不含令牌值）、教务各接口的状态码与响应头，" +
                                "再把同一条令牌换成几种鉴权写法各试一次 —— 只要有一种能过，" +
                                "就说明问题在请求形态而不是账号或令牌。结果可以整段复制发出来。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.runEasAuthProbe() },
                                enabled = !state.probingEasAuth,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (state.probingEasAuth) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.size(8.dp))
                                }
                                Text(if (state.probingEasAuth) "自检中…" else "开始教务鉴权自检")
                            }
                            if (state.easAuthReport.isNotEmpty()) {
                                val clipboard = LocalClipboardManager.current
                                OutlinedButton(onClick = {
                                    clipboard.setText(AnnotatedString(state.easAuthReport))
                                }) { Text("复制结果") }
                            }
                        }
                        if (state.easAuthError.isNotEmpty()) {
                            Text(
                                text = "自检出错：${state.easAuthError}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (state.easAuthReport.isNotEmpty()) {
                            Text(
                                "结果（也可以直接说一声，我从 logcat 取，tag = EasAuthProbe）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SelectionContainer {
                                Text(
                                    text = state.easAuthReport,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                )
                            }
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("图书馆接口自检（传输层）", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "图书馆座位系统校外只能走明文 HTTP:80（443 被校园网拒绝）。" +
                                "这里用 3 个只读请求确认：手机上能不能访问、明文放行有没有生效、" +
                                "需登录的接口是不是 cookie 会话（决定能不能用「WebView 登录器 + 原生查询」）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.runLibProbe() },
                                enabled = !state.probingLib,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (state.probingLib) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.size(8.dp))
                                }
                                Text(if (state.probingLib) "自检中…" else "开始图书馆自检")
                            }
                            if (state.libReport.isNotEmpty()) {
                                val clipboard = LocalClipboardManager.current
                                OutlinedButton(onClick = {
                                    clipboard.setText(AnnotatedString(state.libReport))
                                }) { Text("复制结果") }
                            }
                        }
                        if (state.libError.isNotEmpty()) {
                            Text(
                                text = "自检出错：${state.libError}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (state.libReport.isNotEmpty()) {
                            Text(
                                "结果（也可以直接说一声，我从 logcat 取，tag = LibProbe）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SelectionContainer {
                                Text(
                                    text = state.libReport,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { vm.logout(onLoggedOut) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("退出登录并清除本地令牌")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
