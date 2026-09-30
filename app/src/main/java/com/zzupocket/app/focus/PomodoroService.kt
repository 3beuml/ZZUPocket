package com.zzupocket.app.focus

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.zzupocket.app.core.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 番茄钟前台服务。
 *
 * 只用它做两件事：让系统别在后台把进程回收掉，以及把常驻通知显示出来。
 * **计时逻辑完全不在这里** —— 时间由墙钟算，服务被杀也只是通知没了，
 * 重新打开 App 后剩余时间照旧正确。
 */
class PomodoroService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing: Job? = null
    private var foregroundStarted = false

    private val engine get() = AppGraph.pomodoroEngine
    private val notifier get() = AppGraph.pomodoroNotifications

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> engine.pause()
            ACTION_RESUME -> engine.resume()
            ACTION_SKIP -> engine.skip()
            ACTION_STOP -> engine.stop()
            ACTION_NEXT -> engine.startNext()
            ACTION_SYNC -> Unit // 只是把服务叫起来（比如进程恢复后）
        }

        val snapshot = engine.state.value
        if (snapshot.idle) {
            // 已经空闲了（比如"结束"按钮点完）：前台服务没有存在的意义。
            // ⚠️ 在 startForeground 之前 stopSelf 是允许的，系统只在"迟迟不调 startForeground"时才报错。
            stopSelfSafely()
            return START_NOT_STICKY
        }

        if (!foregroundStarted) {
            val notification = notifier.buildTimerNotification(
                phase = snapshot.phase,
                remainingSeconds = snapshot.remainingSeconds,
                plannedSeconds = snapshot.snapshot.plannedSeconds,
                tagName = snapshot.snapshot.tagName,
                round = snapshot.snapshot.focusRound,
                longBreakEvery = engine.longBreakEvery(),
                running = snapshot.running,
            )
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    PomodoroNotifications.NOTIF_ID_TIMER,
                    notification,
                    type,
                )
            }
            foregroundStarted = true
        }

        if (observing == null) {
            observing = scope.launch {
                var lastRendered: String? = null
                engine.state.collect { ui ->
                    if (ui.idle) {
                        stopSelfSafely()
                        return@collect
                    }
                    // 引擎每 500ms 发一次状态，但通知只需要在"秒数或运行状态变了"的时候更新 ——
                    // 否则通知栏每秒被写两次，费电还刷日志。
                    val fingerprint = "${ui.remainingSeconds}|${ui.running}|${ui.phase}|" +
                        "${ui.snapshot.tagName}|${ui.snapshot.plannedSeconds}|${ui.snapshot.focusRound}"
                    if (fingerprint == lastRendered) return@collect
                    lastRendered = fingerprint

                    val notification = notifier.buildTimerNotification(
                        phase = ui.phase,
                        remainingSeconds = ui.remainingSeconds,
                        plannedSeconds = ui.snapshot.plannedSeconds,
                        tagName = ui.snapshot.tagName,
                        round = ui.snapshot.focusRound,
                        longBreakEvery = engine.longBreakEvery(),
                        running = ui.running,
                    )
                    if (!foregroundStarted) {
                        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        } else {
                            0
                        }
                        runCatching {
                            ServiceCompat.startForeground(
                                this@PomodoroService,
                                PomodoroNotifications.NOTIF_ID_TIMER,
                                notification,
                                type,
                            )
                        }
                        foregroundStarted = true
                    } else {
                        runCatching {
                            NotificationManagerCompat.from(this@PomodoroService)
                                .notify(PomodoroNotifications.NOTIF_ID_TIMER, notification)
                        }
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun stopSelfSafely() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        foregroundStarted = false
        stopSelf()
    }

    override fun onDestroy() {
        observing?.cancel()
        observing = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SYNC = "com.zzupocket.app.focus.SYNC"
        const val ACTION_PAUSE = "com.zzupocket.app.focus.PAUSE"
        const val ACTION_RESUME = "com.zzupocket.app.focus.RESUME"
        const val ACTION_SKIP = "com.zzupocket.app.focus.SKIP"
        const val ACTION_STOP = "com.zzupocket.app.focus.STOP"
        const val ACTION_NEXT = "com.zzupocket.app.focus.NEXT"
    }
}
