package com.zzupocket.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.core.AppError
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.repo.LibRecordRepository
import com.zzupocket.app.domain.LibRecord
import com.zzupocket.app.domain.LibRecordStats
import com.zzupocket.app.ui.components.GlassCard
import com.zzupocket.app.ui.focus.FocusHeatmap
import com.zzupocket.app.ui.focus.HeatmapLegend
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 图书馆「我的预约记录」（原生）：座位 + 空间 + 违约，顶部打卡热力图按天聚合预约次数。
 *
 * 设计对齐"番茄钟 → 学习统计"页：GlassCard + FocusHeatmap（同一组件，分档换成次数）。
 * 数据进页面现拉（v1 不落库）：6 个请求串行 ~2 秒，够快；会话过期给出"去图书馆页"指引
 * （WebView 打开会自动完成登录并抓回 token，用户无感）。
 */
data class LibRecordsState(
    val loading: Boolean = false,
    val snapshot: LibRecordRepository.Snapshot? = null,
    /** 本次现拉失败（snapshot 若非空则是**缓存兜底**：内容可看，但要提示"这是旧数据"） */
    val loadFailed: Boolean = false,
    /** 现拉失败的原因（缓存兜底时用于横幅文案区分"会话过期"与"网络不通"） */
    val loadCause: Throwable? = null,
    val showReneges: Boolean = false,
    val selectedDay: LocalDate? = null,
) {
    /** 整页没内容可看（无缓存且现拉失败）→ 换"去登录"指引 */
    val needRelogin: Boolean get() = snapshot == null && loadFailed
}

class LibRecordsViewModel : ViewModel() {

    private val repo = LibRecordRepository(AppGraph.libClient, AppGraph.db, AppGraph.json)

    private val _state = MutableStateFlow(LibRecordsState())
    val state: StateFlow<LibRecordsState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, loadFailed = false, loadCause = null) }
        viewModelScope.launch {
            // 先把本地缓存摆出来（有就先显示，网络回来再被新数据顶掉）——
            // 这就是"不登录也能看历史记录"：记录是历史数据，拉过一次就一直可看。
            runCatching { repo.cachedSnapshot() }.getOrNull()?.let { cached ->
                _state.update { if (it.snapshot == null) it.copy(snapshot = cached) else it }
            }
            runCatching { repo.fetchAllWithCache() }
                .onSuccess { (snap, fromCache, cause) ->
                    _state.update {
                        it.copy(
                            loading = false,
                            snapshot = snap,
                            loadFailed = fromCache,
                            loadCause = cause,
                        )
                    }
                }
                .onFailure { e ->
                    // 走到这里 = 现拉失败**且**没有缓存：要么引导登录（会话失效），
                    // 要么报网络错误
                    _state.update {
                        it.copy(loading = false, loadFailed = true, loadCause = e)
                    }
                }
        }
    }

    fun toggleTab(reneges: Boolean) = _state.update { it.copy(showReneges = reneges, selectedDay = null) }

    fun selectDay(day: LocalDate?) = _state.update { it.copy(selectedDay = day) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibRecordsScreen(
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
    vm: LibRecordsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的预约记录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading && state.snapshot == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.needRelogin -> ReloginPane(
                padding = padding,
                cause = state.loadCause,
                onOpenLibrary = onOpenLibrary,
            )

            state.snapshot == null -> ErrorPane(
                padding = padding,
                message = state.loadCause?.message ?: "加载失败",
                onRetry = { vm.refresh() },
            )

            else -> RecordsBody(
                padding = padding,
                state = state,
                today = today,
                onSelectDay = { vm.selectDay(it) },
                onToggleTab = { vm.toggleTab(it) },
                onOpenLibrary = onOpenLibrary,
            )
        }
    }
}

@Composable
private fun RecordsBody(
    padding: androidx.compose.foundation.layout.PaddingValues,
    state: LibRecordsState,
    today: LocalDate,
    onSelectDay: (LocalDate?) -> Unit,
    onToggleTab: (Boolean) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val snap = state.snapshot ?: return
    val list = if (state.showReneges) snap.reneges else snap.records
    val counts = snap.countsByDay

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 缓存横幅：显示的是本地缓存时如实告知 + 给更新入口（不隐瞒数据新旧）
        if (state.loadFailed) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "显示的是 ${java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(snap.fetchedAt))} 拉取的记录",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            when (state.loadCause) {
                                is AppError.ReLoginRequired ->
                                    "图书馆会话已过期（实测半天到两天就会过期）。点下面去图书馆页打开一次（会自动登录），回来刷新即可更新。"
                                else ->
                                    "刚才没能连上图书馆系统。换个网络或稍后再点右上角刷新。"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onOpenLibrary, modifier = Modifier.padding(top = 4.dp)) {
                            Text("去图书馆页更新")
                        }
                    }
                }
            }
        }

        // 热力图卡片（与学习统计页同款）
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "预约热力图",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    FocusHeatmap(
                        secondsByDay = counts,
                        today = today,
                        selectedDay = state.selectedDay,
                        onDayClick = { day -> onSelectDay(if (day == state.selectedDay) null else day) },
                        tierOf = LibRecordStats::heatTier,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    state.selectedDay?.let { day ->
                        Text(
                            text = "${day.format(DateTimeFormatter.ISO_LOCAL_DATE)}：预约 ${counts[day] ?: 0} 次",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    HeatmapLegend(
                        Modifier.padding(horizontal = 16.dp),
                        unitText = "1 格 = 1 次预约",
                    )
                }
            }
        }

        // tab 切换
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !state.showReneges,
                    onClick = { onToggleTab(false) },
                    label = { Text("预约记录（${snap.records.size}）") },
                )
                FilterChip(
                    selected = state.showReneges,
                    onClick = { onToggleTab(true) },
                    label = { Text("违约记录（${snap.reneges.size}）") },
                )
            }
        }

        if (list.isEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(
                        if (state.showReneges) {
                            "没有违约记录。保持住：累计 3 次违约会被暂停预约权限 7 天。"
                        } else {
                            "还没有预约记录 —— 去图书馆约一次座位，回来刷新就能看到。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        items(list) { record -> RecordCard(record) }

        item {
            Text(
                text = "共 ${snap.records.size} 条预约、${snap.reneges.size} 条违约 · 数据来自图书馆预约系统",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun RecordCard(record: LibRecord) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.title.ifBlank { "（无地点信息）" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(record.statusName)
            }
            Text(
                listOfNotNull(
                    record.kind,
                    record.range,
                    record.signInTime?.let { "签到 $it" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 状态徽章：文本里带"违约/异常"的标红，进行中的标主色，其余中性 */
@Composable
private fun StatusBadge(statusName: String) {
    if (statusName.isBlank()) return
    val (bg, fg) = when {
        statusName.contains("违约") || statusName.contains("异常") ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        statusName.contains("使用中") || statusName.contains("待") || statusName.contains("预约成功") ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        else ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(statusName, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

@Composable
private fun ReloginPane(
    padding: androidx.compose.foundation.layout.PaddingValues,
    cause: Throwable?,
    onOpenLibrary: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "图书馆会话已失效",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "图书馆的登录有效期很短（实测半天到两天就会过期），而且本地还没有缓存过记录。\n" +
                "点下面打开图书馆页 —— 会自动完成登录并抓回会话，\n登录成功后回来，记录会拉取一次并存到本地，以后不登录也能看。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        cause?.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(onClick = onOpenLibrary, modifier = Modifier.padding(top = 16.dp)) {
            Text("打开图书馆页重新登录")
        }
    }
}

@Composable
private fun ErrorPane(
    padding: androidx.compose.foundation.layout.PaddingValues,
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("重试") }
    }
}
