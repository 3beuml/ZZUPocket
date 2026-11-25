package com.zzupocket.app.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zzupocket.app.MainActivity
import com.zzupocket.app.R

/**
 * 课程提醒通知，走**普通通知**（IMPORTANCE_DEFAULT：状态栏显示 + 默认提示音）
 */
class ClassReminderNotifications(private val context: Context) {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "课程提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "上课前弹出通知提醒"
                setShowBadge(false)
            },
        )
    }

    /**
     * 发一条课程提醒，返回**是否真的发出去了**。
     *
     * 调用方据此决定是否写去重键：发不出去（无权限 / 渠道被关 / 被拦截）时不能写，
     * 否则这一节以后再也不会提醒。
     */
    fun notify(plan: ClassReminderPlan, nowMillis: Long): Boolean {
        val manager = NotificationManagerCompat.from(context)
        // 提前自查：无通知权限、或本渠道被用户关掉时，notify 不会显示，别误判成成功
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "通知未发出：全局通知权限关闭 · $nowMillis")
            return false
        }
        val channel = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(CHANNEL_ID)
        if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
            Log.w(TAG, "通知未发出：课程提醒渠道被关闭 · $nowMillis")
            return false
        }

        val title = if (plan.isConflict) "课程冲突 · 请查看课表" else plan.title
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

        // 没给通知权限时 notify 会抛 SecurityException（API 33+）；被 ROM 拦截时也可能静默失败。
        // 这里只吞异常、如实返回 false，绝不让提醒把进程搞崩。
        return runCatching {
            manager.notify(NOTIF_ID, notification)
            true
        }.getOrElse {
            Log.w(TAG, "通知未发出：notify 抛异常 · $nowMillis", it)
            false
        }
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
        private const val TAG = "ClassReminderNotif"
        const val CHANNEL_ID = "class_reminder"
        /** 固定 ID：同一时刻只保留一条，重复触发直接覆盖，不叠一叠通知 */
        const val NOTIF_ID = 2101
    }
}
