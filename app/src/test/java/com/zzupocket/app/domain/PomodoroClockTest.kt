package com.zzupocket.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PomodoroClockTest {

    private val t0 = 1_700_000_000_000L

    private fun running(planned: Int = 25 * 60, startedAt: Long = t0) = TimerSnapshot(
        phase = Phase.FOCUS,
        running = true,
        startedAt = startedAt,
        plannedSeconds = planned,
    )

    @Test
    fun `剩余时间按墙钟递减`() {
        val s = running()
        assertEquals(25 * 60, PomodoroClock.remainingSeconds(s, t0))
        assertEquals(24 * 60, PomodoroClock.remainingSeconds(s, t0 + 60_000))
        // 不足一秒的零头向下取整，不会"少一秒"
        assertEquals(25 * 60 - 1, PomodoroClock.remainingSeconds(s, t0 + 1_400))
    }

    @Test
    fun `暂停期间剩余时间冻结`() {
        val paused = running().copy(running = false, pausedAt = t0 + 60_000)
        assertEquals(24 * 60, PomodoroClock.remainingSeconds(paused, t0 + 60_000))
        // 暂停 10 分钟后再看，还是 24:00
        assertEquals(24 * 60, PomodoroClock.remainingSeconds(paused, t0 + 11 * 60_000))
    }

    @Test
    fun `恢复后暂停时长被扣掉`() {
        val resumed = running().copy(
            running = true,
            pausedTotalMs = 5 * 60_000L,
            pausedAt = 0L,
        )
        val now = t0 + 10 * 60_000L
        // 过去 10 分钟，其中 5 分钟在暂停 → 实际专注 5 分钟
        assertEquals(20 * 60, PomodoroClock.remainingSeconds(resumed, now))
    }

    @Test
    fun `进度封顶在 0 到 1`() {
        val s = running(planned = 60)
        assertEquals(0f, PomodoroClock.progress(s, t0), 0.001f)
        assertEquals(0.5f, PomodoroClock.progress(s, t0 + 30_000), 0.001f)
        assertEquals(1f, PomodoroClock.progress(s, t0 + 90_000), 0.001f)
    }

    @Test
    fun `走完计划时长算到点`() {
        val s = running(planned = 60)
        assertFalse(PomodoroClock.isFinished(s, t0 + 59_000))
        assertTrue(PomodoroClock.isFinished(s, t0 + 60_000))
        assertTrue(PomodoroClock.isFinished(s, t0 + 3_600_000))
    }

    @Test
    fun `空闲状态是满的也不会被判定为到点`() {
        val idle = TimerSnapshot(phase = Phase.FOCUS, plannedSeconds = 0)
        assertFalse(PomodoroClock.isFinished(idle, t0))
        assertEquals(0, PomodoroClock.remainingSeconds(idle, t0 + 999_999))
    }

    @Test
    fun `时间格式化补零`() {
        assertEquals("00:00", PomodoroClock.format(0))
        assertEquals("00:09", PomodoroClock.format(9))
        assertEquals("01:00", PomodoroClock.format(60))
        assertEquals("90:00", PomodoroClock.format(90 * 60))
        assertEquals("00:00", PomodoroClock.format(-5))
    }
}

class PomodoroFlowTest {

    @Test
    fun `专注之后的短休息`() {
        val plan = PomodoroFlow.afterFinish(Phase.FOCUS, round = 0, longBreakEvery = 4)
        assertEquals(Phase.SHORT_BREAK, plan.next)
        assertEquals(1, plan.nextRound)
    }

    @Test
    fun `第四个番茄之后是长休息`() {
        val plan = PomodoroFlow.afterFinish(Phase.FOCUS, round = 3, longBreakEvery = 4)
        assertEquals(Phase.LONG_BREAK, plan.next)
        assertEquals(4, plan.nextRound)
    }

    @Test
    fun `长休息走完轮次清零`() {
        val plan = PomodoroFlow.afterFinish(Phase.LONG_BREAK, round = 4, longBreakEvery = 4)
        assertEquals(Phase.FOCUS, plan.next)
        assertEquals(0, plan.nextRound)
    }

    @Test
    fun `短休息不改变轮次`() {
        val plan = PomodoroFlow.afterFinish(Phase.SHORT_BREAK, round = 2, longBreakEvery = 4)
        assertEquals(Phase.FOCUS, plan.next)
        assertEquals(2, plan.nextRound)
    }

    @Test
    fun `长休间隔为 0 时不会除零`() {
        val plan = PomodoroFlow.afterFinish(Phase.FOCUS, round = 99, longBreakEvery = 0)
        assertEquals(Phase.SHORT_BREAK, plan.next)
    }
}
