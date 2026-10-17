package com.zzupocket.app.ui.diag

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
import com.zzupocket.app.core.net.HttpClients
import com.zzupocket.app.data.auth.AuthRepository
import com.zzupocket.app.data.gms.GmsProbe
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

/**
 * 正式包（release）是否也显示下面那批「自检」卡片。
 *
 * 为什么要有这个开关：这些卡片原来只挂在 `BuildConfig.DEBUG` 上，于是**用户装的正式包里
 * 根本看不到它们** —— 而它们恰恰是给用户报障时用的（2026-10-15 踩过：给用户发了个"带自检
 * 入口"的包，结果他打开诊断页什么自检都没有）。这些都是只读诊断，不会改任何数据，
 * 正式包显示是安全的；哪天不想给用户看，把这里改回 false 即可。
 */
private const val SHOW_PROBES_IN_RELEASE = true

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
    val probingGms: Boolean = false,
    /** 研究生系统探测报告（登录器记录到的接口）或身份类型自检结果 */
    val gmsReport: String = "",
    val gmsError: String = "",
    /** 研究生系统登录器是否打开（状态放这儿，转屏/重组都不丢） */
    val gmsDialogOpen: Boolean = false,
    // ── 研究生教务会话（固定登录位那张卡片）────────────────────────────
    val gmsLoggedIn: Boolean = false,
    /** 上次登录研究生系统的时间（0 = 没登录过） */
    val gmsLoginAt: Long = 0L,
    /** null = 还没检查；true/false = 「检查会话」的结果 */
    val gmsAlive: Boolean? = null,
    /** 自检工具是否展开（默认收起，别挡住日常信息） */
    val probesOpen: Boolean = false,
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
                gmsLoggedIn = AppGraph.gmsScheduleSource.hasSession(),
                gmsLoginAt = AppGraph.gmsScheduleSource.loginAt(),
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

    /**
     * 身份类型自检：「按学籍自动路由数据源」的前提 —— 先确认学校认证服务会不会告诉我们
     * 这个账号是本科还是研究生。四种鉴权写法都试一遍，哪种能用一目了然。
     */
    fun runIdentityCheck() {
        if (_state.value.probingGms) return
        _state.update { it.copy(probingGms = true, gmsReport = "", gmsError = "") }
        viewModelScope.launch {
            val result = runCatching {
                val token = auth.requireCasToken()
                // ⚠️ 必须在 IO 线程：GmsProbe.identityCheck 里是同步 OkHttp 调用，
                // 放在主线程会抛 NetworkOnMainThreadException（2026-10-15 踩过：
                // 六种写法全报"网络失败"，其实是这个）。
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    GmsProbe.identityCheck(token, HttpClients.eas)
                }
            }
            _state.update {
                it.copy(
                    probingGms = false,
                    gmsReport = result.getOrDefault(""),
                    gmsError = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() }
                        .orEmpty(),
                )
            }
        }
    }

    fun openGmsDialog() = _state.update { it.copy(gmsDialogOpen = true) }

    fun closeGmsDialog() = _state.update { it.copy(gmsDialogOpen = false) }

    // ── 研究生教务会话（固定登录位）────────────────────────────────────

    /** 自检工具展开/收起（默认收起，避免挡住日常信息） */
    fun toggleProbes() = _state.update { it.copy(probesOpen = !it.probesOpen) }

    /**
     * 采用 WebView 登录后的会话（与课表页同一套逻辑）。
     *
     * 从 WebView 的 CookieManager 取会话 → 交给数据源落盘。密码始终输在学校页面上，我们不接触。
     * @param onDone 回调结果（true = 真的拿到了会话）
     */
    fun adoptGmsSession(onDone: (Boolean) -> Unit = {}) {
        _state.update { it.copy(gmsDialogOpen = false, gmsError = "") }
        viewModelScope.launch {
            val cookie = runCatching {
                android.webkit.CookieManager.getInstance()
                    .getCookie("https://" + com.zzupocket.app.data.gms.GmsScheduleSource.HOST)
            }.getOrNull()
            val ok = AppGraph.gmsScheduleSource.onWebViewLogin(cookie)
            refreshGmsState()
            onDone(ok)
        }
    }

    /** 刷新卡片上的会话状态（是否已登录 + 登录时间） */
    fun refreshGmsState() {
        val src = AppGraph.gmsScheduleSource
        _state.update {
            it.copy(gmsLoggedIn = src.hasSession(), gmsLoginAt = src.loginAt(), gmsAlive = null)
        }
    }

    /** 「检查会话」：真发一次请求确认还有效 */
    fun checkGmsSession() {
        viewModelScope.launch {
            val alive = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    AppGraph.gmsScheduleSource.sessionAlive()
                }
            }.getOrDefault(false)
            _state.update { it.copy(gmsAlive = alive) }
        }
    }

    /** 只退出研究生教务会话，不动统一认证登录 */
    fun logoutGms() {
        AppGraph.gmsScheduleSource.clear()
        refreshGmsState()
    }

    /**
     * WebView 登录器把报告交回来。
     *
     * 报告由 [GmsProbe.Recorder] 生成：只有请求路径、参数名、响应字段名与类型，
     * **不含任何字段值与令牌** —— 因为这份东西是要贴到公开 issue 上的。
     */
    fun acceptGmsReport(text: String) = _state.update {
        it.copy(gmsDialogOpen = false, gmsReport = text, gmsError = "")
    }

    fun logout(onDone: () -> Unit) {
        // ⚠️ 统一认证退出时，研究生教务的会话必须一起清 ——
        // 否则会出现"已经退出登录、但研究生课表还能看"的怪状态。
        runCatching { AppGraph.gmsScheduleSource.clear() }
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
            // ── 研究生教务：固定的登录位（研究生课表要单独登录一次研究生院系统）──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "研究生教务",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (state.gmsLoggedIn) "已登录" else "未登录",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.gmsLoggedIn) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Text(
                        "研究生课表在研究生院那套系统里，需要单独登录一次（本科课表用不着）。" +
                            "登录走学校统一身份认证，密码输在学校自己的页面上，我们不接触、也不保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.gmsLoginAt > 0L) {
                        Text(
                            "上次登录：" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                .format(java.util.Date(state.gmsLoginAt)),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    state.gmsAlive?.let { alive ->
                        Text(
                            if (alive) "会话有效 ✓" else "会话已失效，需要重新登录",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (alive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.openGmsDialog() },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (state.gmsLoggedIn) "重新登录" else "登录研究生系统") }
                        OutlinedButton(
                            onClick = { vm.checkGmsSession() },
                            enabled = state.gmsLoggedIn,
                            modifier = Modifier.weight(1f),
                        ) { Text("检查会话") }
                    }
                    if (state.gmsLoggedIn) {
                        OutlinedButton(onClick = { vm.logoutGms() }) { Text("退出研究生教务") }
                    }
                    if (state.gmsError.isNotEmpty()) {
                        Text(
                            state.gmsError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (state.gmsDialogOpen) {
                        GmsProbeDialog(
                            onDismiss = { vm.closeGmsDialog() },
                            onReport = { vm.adoptGmsSession() },
                            loginMode = true,
                        )
                    }
                }
            }

            // ── 自检工具：默认收起，别挡住日常信息（用户反馈：不要太影响使用）──
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { vm.toggleProbes() }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.probesOpen) "收起自检工具" else "自检工具（排障用，点开）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (state.probesOpen) "▲" else "▼", style = MaterialTheme.typography.titleSmall)
                }
            }

            if (state.probesOpen && (BuildConfig.DEBUG || SHOW_PROBES_IN_RELEASE)) {
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

                // 研究生系统探测卡片。放在图书馆自检上面一张。
                // 背景：研究生课表不在本科教务里，而我们没有研究生账号，只能借一次真实会话摸接口。
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("研究生系统自检（探测）", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "研究生课表不在本科教务里，而在研究生院那套系统（它走学校统一身份认证跳转）。" +
                                "我们这边没有研究生账号，所以只能借一次真实会话来摸接口：" +
                                "点「打开研究生系统」会在 App 内打开它，用你自己的账号登录" +
                                "（密码在学校自己的页面上输，我们不接触），登录后进课表页翻一下，" +
                                "再点「生成报告」。报告里只有接口路径与字段名，不含任何令牌、姓名、学号。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.openGmsDialog() },
                                modifier = Modifier.weight(1f),
                            ) { Text("打开研究生系统") }
                            OutlinedButton(
                                onClick = { vm.runIdentityCheck() },
                                enabled = !state.probingGms,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (state.probingGms) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.size(8.dp))
                                }
                                Text("学籍探测")
                            }
                        }
                        if (state.gmsError.isNotEmpty()) {
                            Text(
                                text = "自检出错：${state.gmsError}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (state.gmsReport.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "结果（可直接复制；也可以说一声，我从 logcat 取，tag = GmsProbe）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                val clipboard = LocalClipboardManager.current
                                OutlinedButton(onClick = {
                                    clipboard.setText(AnnotatedString(state.gmsReport))
                                }) { Text("复制结果") }
                            }
                            SelectionContainer {
                                Text(
                                    text = state.gmsReport,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                )
                            }
                        }
                        if (state.gmsDialogOpen) {
                            GmsProbeDialog(
                                onDismiss = { vm.closeGmsDialog() },
                                onReport = { vm.acceptGmsReport(it) },
                            )
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
