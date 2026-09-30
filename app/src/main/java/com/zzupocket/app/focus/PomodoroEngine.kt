package com.zzupocket.app.focus

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.zzupocket.app.data.prefs.FocusPrefs
import com.zzupocket.app.data.prefs.FocusPrefsStore
import com.zzupocket.app.data.repo.FocusRepository
import com.zzupocket.app.domain.Phase
import com.zzupocket.app.domain.PhaseEvent
import com.zzupocket.app.domain.PomodoroClock
import com.zzupocket.app.domain.PomodoroFlow
import com.zzupocket.app.domain.TimerSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 界面直接消费的状态：快照 + 每半秒刷新的剩余时间 */
data class TimerUi(
    val snapshot: TimerSnapshot = TimerSnapshot(),
    val remainingSeconds: Int = 0,
    val progress: Float = 0f,
    /** 计划时长已经走完，等用户处理 */
    val reachedZero: Boolean = false,
) {
    val idle: Boolean get() = snapshot.idle
    val running: Boolean get() = !snapshot.idle && snapshot.running
    val paused: Boolean get() = snapshot.paused
    val phase: Phase get() = snapshot.phase
}

/**
 * 番茄钟引擎（单例）。
 *
 * 设计要点：
 *  1. **唯一状态源是一份持久化快照**（开始时刻 + 累计暂停），剩余时间用墙钟算。
 *     所以进程被杀、手机深度休眠、系统省电限频都不会让计时走偏。
 *  2. 所有协程都跑在主线程上（`Dispatchers.Main.immediate`），天然串行，不用锁。
 *  3. 计时器到点时由 [ticker] 触发段落流转；如果进程在到点前就被杀了，
 *     恢复后第一帧就会算出"早就到点了"，于是立刻补上这次流转。
 */
class PomodoroEngine(
    private val context: Context,
    private val prefs: FocusPrefsStore,
    private val repo: FocusRepository,
    private val notifier: PomodoroNotifications,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(TimerUi())
    val state: StateFlow<TimerUi> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PhaseEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PhaseEvent> = _events.asSharedFlow()

    @Volatile
    private var cachedPrefs: FocusPrefs = FocusPrefs()

    private var ticker: Job? = null
    private var restored = false

    init {
        scope.launch {
            prefs.flow.collect { cachedPrefs = it }
        }
    }

    /** 给前台服务用（非挂起，通知里要显示轮次） */
    fun longBreakEvery(): Int = cachedPrefs.longBreakEvery

    fun prefsSnapshot(): FocusPrefs = cachedPrefs

    // ── 恢复 ────────────────────────────────────────────────────────────

    /**
     * 进程启动时调用一次。
     *
     * 上次进程被杀时，进行中的那条数据库记录 endedAt 还是 0；
     * 如果它还对应着快照里那一段，就留着继续用；对不上的残留记录直接收尾封存。
     */
    suspend fun restore() {
        if (restored) return
        restored = true
        cachedPrefs = prefs.current()
        val nowMs = now()
        val snapshot = prefs.snapshot()

        // 上次进程被杀时留下的"进行中"记录：对不上快照的一律封存，
        // 能对上快照的那一条留着接着跑。
        repo.closeUnfinished(nowMs, exceptId = snapshot.sessionId)

        if (snapshot.idle) {
            _state.value = idleUi(Phase.FOCUS, round = 0, tagId = null, tagName = "", contextRef = "")
            prefs.clearSnapshot()
            return
        }

        if (snapshot.sessionId > 0 && !repo.exists(snapshot.sessionId)) {
            // 记录已经不存在了（比如用户清理过数据），干净地收场
            _state.value = idleUi(Phase.FOCUS, snapshot.focusRound, snapshot.tagId, snapshot.tagName, snapshot.contextRef)
            prefs.clearSnapshot()
            return
        }

        applySnapshot(snapshot)
        startTicker()
        ensureService()
    }

    // ── 外部操作 ────────────────────────────────────────────────────────

    fun start(
        phase: Phase = Phase.FOCUS,
        tagId: Long? = null,
        tagName: String = "",
        contextRef: String = "",
    ) {
        if (!_state.value.idle) return
        scope.launch {
            beginPhase(
                phase = phase,
                tagId = tagId,
                tagName = tagName,
                contextRef = contextRef,
                round = _state.value.snapshot.focusRound,
            )
        }
    }

    /** 从"刚刚结束一段"的提示里直接开始下一段 */
    fun startNext() {
        if (!_state.value.idle) return
        val s = _state.value.snapshot
        start(phase = s.phase, tagId = s.tagId, tagName = s.tagName, contextRef = s.contextRef)
    }

    fun pause() {
        val s = _state.value.snapshot
        if (s.idle || !s.running) return
        val nowMs = now()
        val updated = s.copy(running = false, pausedAt = nowMs)
        applySnapshot(updated)
        scope.launch { prefs.saveSnapshot(updated) }
    }

    fun resume() {
        val s = _state.value.snapshot
        if (s.idle || s.running) return
        val nowMs = now()
        val extra = if (s.pausedAt > 0) (nowMs - s.pausedAt).coerceAtLeast(0L) else 0L
        val updated = s.copy(
            running = true,
            pausedTotalMs = s.pausedTotalMs + extra,
            pausedAt = 0L,
        )
        applySnapshot(updated)
        scope.launch {
            prefs.saveSnapshot(updated)
            ensureService()
        }
        startTicker()
    }

    /** 跳过当前这一段：记录成"没走完"，直接开始下一段 */
    fun skip() {
        val s = _state.value.snapshot
        if (s.idle) return
        scope.launch {
            val nowMs = now()
            repo.finishSession(
                id = s.sessionId,
                endedAt = nowMs,
                actualSeconds = PomodoroClock.elapsedSeconds(s, nowMs),
                completed = false,
            )
            val plan = PomodoroFlow.afterFinish(s.phase, s.focusRound, cachedPrefs.longBreakEvery)
            beginPhase(plan.next, s.tagId, s.tagName, s.contextRef, plan.nextRound)
        }
    }

    /** 放弃当前这一段：记录成中断，回到空闲 */
    fun stop() {
        val s = _state.value.snapshot
        if (s.idle) return
        scope.launch {
            val nowMs = now()
            val actual = PomodoroClock.elapsedSeconds(s, nowMs)
            repo.finishSession(s.sessionId, nowMs, actual, completed = false)
            _events.tryEmit(PhaseEvent(s.phase, completed = false, next = null))
            val plan = PomodoroFlow.afterFinish(s.phase, s.focusRound, cachedPrefs.longBreakEvery)
            goIdle(plan.next, plan.nextRound, s.tagId, s.tagName, s.contextRef)
        }
    }

    /** 临时加时（"我再多写 5 分钟"） */
    fun extend(minutes: Int) {
        val s = _state.value.snapshot
        if (s.idle || minutes <= 0) return
        val updated = s.copy(plannedSeconds = s.plannedSeconds + minutes * 60)
        applySnapshot(updated)
        scope.launch { prefs.saveSnapshot(updated) }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private suspend fun beginPhase(
        phase: Phase,
        tagId: Long?,
        tagName: String,
        contextRef: String,
        round: Int,
    ) {
        val startedAt = now()
        val planned = cachedPrefs.plannedSeconds(phase)
        // 从课表/日程直接开计时的话只有名字、还没有标签 —— 在这里补建一次，
        // 免得统计里出现一堆"有名字但没标签"的记录。
        val resolvedTagId = tagId ?: tagName.takeIf { it.isNotBlank() }
            ?.let { repo.ensureTag(it)?.id }
        val sessionId = repo.startSession(
            phase = phase,
            tagId = resolvedTagId,
            tagName = tagName,
            plannedSeconds = planned,
            startedAt = startedAt,
            contextRef = contextRef,
        )
        val snapshot = TimerSnapshot(
            phase = phase,
            running = true,
            startedAt = startedAt,
            plannedSeconds = planned,
            tagId = resolvedTagId,
            tagName = tagName,
            sessionId = sessionId,
            focusRound = round,
            contextRef = contextRef,
        )
        applySnapshot(snapshot)
        prefs.saveSnapshot(snapshot)
        if (phase == Phase.FOCUS && tagName.isNotBlank()) {
            prefs.rememberTag(resolvedTagId, tagName)
        }
        notifier.cancelAlarm()
        startTicker()
        ensureService()
    }

    private fun applySnapshot(snapshot: TimerSnapshot) {
        val nowMs = now()
        _state.value = TimerUi(
            snapshot = snapshot,
            remainingSeconds = PomodoroClock.remainingSeconds(snapshot, nowMs),
            progress = PomodoroClock.progress(snapshot, nowMs),
            reachedZero = PomodoroClock.isFinished(snapshot, nowMs),
        )
    }

    private fun idleUi(
        next: Phase,
        round: Int,
        tagId: Long?,
        tagName: String,
        contextRef: String,
    ): TimerUi {
        val planned = cachedPrefs.plannedSeconds(next)
        return TimerUi(
            snapshot = TimerSnapshot(
                phase = next,
                running = false,
                startedAt = 0L,
                plannedSeconds = planned,
                tagId = tagId,
                tagName = tagName,
                focusRound = round,
                contextRef = contextRef,
            ),
            remainingSeconds = planned,
            progress = 0f,
            reachedZero = false,
        )
    }

    private suspend fun goIdle(
        next: Phase,
        round: Int,
        tagId: Long?,
        tagName: String,
        contextRef: String,
    ) {
        _state.value = idleUi(next, round, tagId, tagName, contextRef)
        prefs.clearSnapshot()
        ticker?.cancel()
        ticker = null
        stopService()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val ui = _state.value
                if (ui.idle) break
                applySnapshot(ui.snapshot)
                val fresh = _state.value
                if (fresh.running && fresh.reachedZero) {
                    onPhaseComplete()
                }
                delay(TICK_MS)
            }
            ticker = null
        }
    }

    /**
     * 计划时长走完。
     *
     * 这里**按计划时长结算**（而不是按墙钟实际经过），因为走了 25 分钟就是走了 25 分钟，
     * 中途进程被杀过也不该多算。
     */
    private suspend fun onPhaseComplete() {
        val s = _state.value.snapshot
        if (s.idle) return
        val nowMs = now()
        repo.finishSession(s.sessionId, nowMs, s.plannedSeconds, completed = true)

        val plan = PomodoroFlow.afterFinish(s.phase, s.focusRound, cachedPrefs.longBreakEvery)
        _events.tryEmit(PhaseEvent(s.phase, completed = true, next = plan.next))
        notifier.notifyPhaseDone(s.phase, plan.next, s.tagName)

        val autoNext = if (s.phase == Phase.FOCUS) cachedPrefs.autoStartBreak else cachedPrefs.autoStartFocus
        if (autoNext) {
            beginPhase(plan.next, s.tagId, s.tagName, s.contextRef, plan.nextRound)
        } else {
            // 停下来等用户点一下，界面上的按钮会变成"开始休息"
            goIdle(plan.next, plan.nextRound, s.tagId, s.tagName, s.contextRef)
        }
    }

    private fun ensureService() {
        if (_state.value.idle) return
        val intent = Intent(context, PomodoroService::class.java).setAction(PomodoroService.ACTION_SYNC)
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    private fun stopService() {
        runCatching { context.stopService(Intent(context, PomodoroService::class.java)) }
    }

    private companion object {
        const val TICK_MS = 500L
    }
}
