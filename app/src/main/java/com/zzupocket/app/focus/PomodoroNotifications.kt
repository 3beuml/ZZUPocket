package com.zzupocket.app.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zzupocket.app.MainActivity
import com.zzupocket.app.R
import com.zzupocket.app.domain.Phase
import com.zzupocket.app.domain.PomodoroClock

/**
 * 番茄钟通知。
 *
 * 两个通道分开：
 *  - TIMER：常驻的倒计时通知，**静音**（每秒/每分钟都响会疯掉）；
 *  - ALARM：一段结束时的提醒，走默认提示音 —— 这个必须吵，不然用户不知道时间到了。
 */
class PomodoroNotifications(private val context: Context) {

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TIMER,
                "专注倒计时",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "常驻的番茄钟倒计时，静音"
                setShowBadge(false)
                enableVibration(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARM,
                "专注结束提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "一段专注或休息结束时提醒"
            },
        )
    }

    /** 常驻倒计时通知；由前台服务负责让它显示出来 */
    fun buildTimerNotification(
        phase: Phase,
        remainingSeconds: Int,
        plannedSeconds: Int,
        tagName: String,
        round: Int,
        longBreakEvery: Int,
        running: Boolean,
    ): Notification {
        val title = when {
            tagName.isNotBlank() -> "${phase.label} · $tagName"
            else -> phase.label
        }
        val pizza = if (phase == Phase.FOCUS && longBreakEvery > 0) {
            "  ${round % longBreakEvery}/$longBreakEvery"
        } else {
            ""
        }
        val text = PomodoroClock.format(remainingSeconds) +
            " / ${plannedSeconds / 60} 分钟$pizza"

        return NotificationCompat.Builder(context, CHANNEL_TIMER)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                if (running) "暂停" else "继续",
                serviceIntent(if (running) PomodoroService.ACTION_PAUSE else PomodoroService.ACTION_RESUME, 1),
            )
            .addAction(0, "跳过", serviceIntent(PomodoroService.ACTION_SKIP, 2))
            .addAction(0, "结束", serviceIntent(PomodoroService.ACTION_STOP, 3))
            .build()
    }

    /** 一段走完时的提醒：可以不带前台服务，直接弹 */
    fun notifyPhaseDone(finishedPhase: Phase, next: Phase?, tagName: String) {
        val title = when {
            finishedPhase == Phase.FOCUS && next == Phase.LONG_BREAK -> "长休息时间到 🍅"
            finishedPhase == Phase.FOCUS -> "本轮专注完成 🍅"
            else -> "休息结束，继续加油"
        }
        val body = buildString {
            if (tagName.isNotBlank()) append("$tagName · ")
            append(
                when {
                    finishedPhase == Phase.FOCUS -> "完成了 1 个番茄"
                    else -> "休息结束"
                },
            )
            next?.let { append("，接下来${it.label}") }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)

        if (next != null) {
            builder.addAction(
                0,
                "开始${next.label}",
                serviceIntent(PomodoroService.ACTION_NEXT, 4),
            )
        }
        notifySafely(NOTIF_ID_ALARM, builder.build())
    }

    fun cancelAlarm() = NotificationManagerCompat.from(context).cancel(NOTIF_ID_ALARM)

    /**
     * 没有通知权限时 notify 会抛 SecurityException（API 33+），
     * 而番茄钟本身不该因为通知发不出去就崩 —— 所以吞掉。
     */
    private fun notifySafely(id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, PomodoroService::class.java).setAction(action)
        return PendingIntent.getForegroundService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_TIMER = "pomodoro_timer"
        const val CHANNEL_ALARM = "pomodoro_alarm"
        const val NOTIF_ID_TIMER = 1001
        const val NOTIF_ID_ALARM = 1002
    }
}
