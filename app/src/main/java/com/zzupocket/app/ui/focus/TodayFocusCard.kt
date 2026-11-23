package com.zzupocket.app.ui.focus

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.domain.FocusStats
import com.zzupocket.app.focus.TimerUi
import com.zzupocket.app.ui.components.GlassCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TodayFocusUi(
    val timer: TimerUi = TimerUi(),
    val todaySeconds: Int = 0,
    val todaySessions: Int = 0,
    val plannedSeconds: Int = 25 * 60,
)

/**
 * 首页专注卡的状态：只订阅计时引擎、今日记录和时长设置。
 *
 * 不复用完整 [FocusViewModel]：那里还会加载标签、400 天统计和课名，
 * 首页只需要"现在能不能开始/暂停、今天累计多久"。
 */
class TodayFocusViewModel : ViewModel() {
    private val engine = AppGraph.pomodoroEngine
    private val prefs = AppGraph.focusPrefsStore
    private val repo = AppGraph.focusRepository

    private val todayStart = FocusStats.dayStart(
        FocusStats.dayKey(System.currentTimeMillis()),
    )

    val state = combine(
        engine.state,
        prefs.flow,
        repo.observeRecords(todayStart),
    ) { timer, focusPrefs, records ->
        val totals = FocusStats.totals(records)
        TodayFocusUi(
            timer = timer,
            todaySeconds = totals.seconds,
            todaySessions = totals.sessions,
            plannedSeconds = focusPrefs.plannedSeconds(timer.phase),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayFocusUi())

    fun start() {
        val phase = state.value.timer.phase
        viewModelScope.launch {
            val name = prefs.current().lastTagName.trim()
            val tag = if (name.isEmpty()) null else repo.ensureTag(name)
            engine.start(
                phase = phase,
                tagId = tag?.id,
                tagName = tag?.name ?: name,
            )
        }
    }

    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun startNext() = engine.startNext()
    fun stop() = engine.stop()
}

/**
 * 首页的番茄钟快捷卡。
 *
 * 专注已从底栏移出，这里是唯一入口：开始/暂停/继续都在卡片上完成，
 * 点卡片进完整页（标签、统计、手动补录）。
 */
@Composable
fun TodayFocusCard(
    onOpenFocus: () -> Unit,
    modifier: Modifier = Modifier,
    vm: TodayFocusViewModel = viewModel(),
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Android 13+ 的常驻通知权限；不给也能计时，只是看不到通知。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val startSafely = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.start()
    }

    GlassCard(modifier.fillMaxWidth().clickable(onClick = onOpenFocus)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("专注", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "今日 ${formatFocusMinutes(ui.todaySeconds)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }

            if (ui.timer.idle) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${ui.plannedSeconds / 60} 分钟 · 今日完成 ${ui.todaySessions} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = startSafely) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (ui.timer.phase.isBreak) "开始休息" else "开始专注")
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = when {
                                ui.timer.reachedZero -> "本段已结束"
                                ui.timer.paused -> "${ui.timer.phase.label}已暂停"
                                else -> "${ui.timer.phase.label}中"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = clock(ui.timer.remainingSeconds),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (ui.timer.reachedZero) {
                        FilledTonalButton(onClick = vm::startNext) { Text("开始下一段") }
                    } else {
                        IconButton(onClick = { if (ui.timer.paused) vm.resume() else vm.pause() }) {
                            Icon(
                                if (ui.timer.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                contentDescription = if (ui.timer.paused) "继续" else "暂停",
                            )
                        }
                        IconButton(onClick = vm::stop) {
                            Icon(Icons.Filled.Stop, contentDescription = "结束本段")
                        }
                    }
                }
                LinearProgressIndicator(
                    progress = { ui.timer.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun formatFocusMinutes(seconds: Int): String {
    val minutes = seconds.coerceAtLeast(0) / 60
    return when {
        minutes >= 60 -> "${minutes / 60} 小时 ${minutes % 60} 分"
        minutes > 0 -> "$minutes 分钟"
        else -> "0 分钟"
    }
}

private fun clock(seconds: Int): String =
    "%d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)
