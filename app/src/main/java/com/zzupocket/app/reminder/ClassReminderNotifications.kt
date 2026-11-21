package com.zzupocket.app.reminder

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

/**
 * 上课提醒通知，走**普通通知**（IMPORTANCE_DEFAULT：状态栏显示 + 默认提示音）
 */
class ClassReminderNotifications(private val context: Context) {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "上课提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "上课前弹出通知提醒"
                setShowBadge(false)
            },
        )
    }

    /** 按触发时刻算剩余分钟（向上取整），国产 ROM 偶有延迟也不影响文案 */
    fun notify(plan: ClassReminderPlan, nowMillis: Long) {
        val minutesLeft = ((plan.startAt - nowMillis + 59_999) / 60_000L).toInt().coerceAtLeast(0)
        val title = when {
            plan.isConflict -> "课程冲突 · 请查看课表"
            minutesLeft <= 0 -> "${plan.title}"
            else -> "${plan.title}"
            // else -> "${plan.title} - ${minutesLeft} 分钟后"
        }
        val body = buildString {
            append(plan.subtitle.ifBlank { "点开查看课程详情" })
            if (plan.isConflict) append(" · 到点有多个课未选择显示")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_class)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setWhen(plan.startAt)
            .setShowWhen(true)
            .build()

        // 没给通知权限时 notify 会抛 SecurityException（API 33+）；
        // 提醒绝不能因为发不出去而把进程搞崩，吞掉即可 —— 设置页会提示权限状态。
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notification) }
    }

    /** 自检用：发一条固定文案的测试通知，验证通知渠道与权限是否正常 */
    fun notifyTest() {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_class)
            .setContentTitle("课程提醒测试")
            .setContentText("示例通知，看到这条说明通知权限和提醒渠道正常")
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notification) }
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

    companion object {
        const val CHANNEL_ID = "class_reminder"
        /** 固定 ID：同一时刻只保留一条，重复触发直接覆盖，不叠一叠通知 */
        const val NOTIF_ID = 2101
    }
}
