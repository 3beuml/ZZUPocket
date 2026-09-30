package com.zzupocket.app.domain

import kotlinx.serialization.Serializable

/** 番茄钟的三种段落 */
enum class Phase(val dbValue: String, val label: String) {
    FOCUS("FOCUS", "专注"),
    SHORT_BREAK("SHORT_BREAK", "短休息"),
    LONG_BREAK("LONG_BREAK", "长休息"),
    ;

    val isBreak: Boolean get() = this != FOCUS

    companion object {
        fun fromDb(value: String?): Phase =
            entries.firstOrNull { it.dbValue == value } ?: FOCUS
    }
}

/**
 * 计时器快照 —— **唯一的状态源**，同时用于界面和通知。
 *
 * 刻意不存"剩余秒数"：只存"开始时刻 + 累计暂停时长"，
 * 剩余时间用墙钟算出来。这样进程被杀、手机休眠、系统省电都不会走偏 ——
 * 恢复时算出来的剩余时间就是真实剩余时间。
 */
@Serializable
data class TimerSnapshot(
    val phase: Phase = Phase.FOCUS,
    val running: Boolean = false,
    /** 本段开始的墙钟时刻；0 = 空闲（什么都没在跑） */
    val startedAt: Long = 0L,
    val plannedSeconds: Int = 25 * 60,
    /** 已累计的暂停时长 */
    val pausedTotalMs: Long = 0L,
    /** 本次暂停的开始时刻；0 = 当前不在暂停 */
    val pausedAt: Long = 0L,
    val tagId: Long? = null,
    val tagName: String = "",
    /** 数据库里那条"进行中"记录的 id；0 = 还没落库 */
    val sessionId: Long = 0L,
    /** 本轮已完成的专注段数，用来决定第几段之后进长休息 */
    val focusRound: Int = 0,
    /** 关联对象（课表里的一节课 / 自定义日程），空串 = 自由专注 */
    val contextRef: String = "",
) {
    val idle: Boolean get() = startedAt == 0L

    /** 暂停中（区别于"没在跑"） */
    val paused: Boolean get() = !idle && pausedAt > 0L
}

/** 纯函数的时间计算，方便单测 */
object PomodoroClock {
    /** 已专注秒数（已扣掉暂停） */
    fun elapsedSeconds(s: TimerSnapshot, now: Long): Int {
        if (s.startedAt == 0L) return 0
        val paused = pausedMs(s, now)
        val raw = now - s.startedAt - paused
        return (raw / 1000L).coerceAtLeast(0L).toInt()
    }

    fun pausedMs(s: TimerSnapshot, now: Long): Long {
        var paused = s.pausedTotalMs
        if (s.pausedAt > 0L) paused += (now - s.pausedAt).coerceAtLeast(0L)
        return paused.coerceAtLeast(0L)
    }

    fun remainingSeconds(s: TimerSnapshot, now: Long): Int {
        if (s.startedAt == 0L) return s.plannedSeconds
        return (s.plannedSeconds - elapsedSeconds(s, now)).coerceAtLeast(0)
    }

    fun progress(s: TimerSnapshot, now: Long): Float {
        if (s.plannedSeconds <= 0) return 0f
        return (elapsedSeconds(s, now).toFloat() / s.plannedSeconds.toFloat()).coerceIn(0f, 1f)
    }

    /** 计划时长已经走完（不等于用户已确认结束） */
    fun isFinished(s: TimerSnapshot, now: Long): Boolean =
        s.startedAt != 0L && remainingSeconds(s, now) <= 0

    /** mm:ss */
    fun format(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }
}

/** 完成一段之后该去哪一段，以及轮次怎么变 */
data class PhasePlan(val next: Phase, val nextRound: Int)

/**
 * 段落流转。`round` 的语义是"距离上一次长休息已经完成几个番茄"，
 * 长休息走完清零 —— 这样长休息的节奏不会因为中途关掉 App 而乱掉。
 */
object PomodoroFlow {

    fun afterFinish(finished: Phase, round: Int, longBreakEvery: Int): PhasePlan = when (finished) {
        Phase.FOCUS -> {
            val r = round + 1
            if (longBreakEvery > 0 && r >= longBreakEvery) {
                PhasePlan(Phase.LONG_BREAK, r)
            } else {
                PhasePlan(Phase.SHORT_BREAK, r)
            }
        }

        Phase.LONG_BREAK -> PhasePlan(Phase.FOCUS, 0)
        Phase.SHORT_BREAK -> PhasePlan(Phase.FOCUS, round)
    }
}

/** 一段结束时发出去的事件（界面拿它弹提示 / 响一下） */
data class PhaseEvent(
    val finished: Phase,
    val completed: Boolean,
    val next: Phase?,
)

