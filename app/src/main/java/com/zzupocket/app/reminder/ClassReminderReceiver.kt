package com.zzupocket.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zzupocket.app.core.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 上课提醒广播接收器。两类 action：
 *
 *  - [ClassReminderScheduler.ACTION_FIRE]：闹钟到点 → 发通知 + 记去重键 + 排下一节；
 *  - 系统事件（开机 / 时间或时区变化 / 语言变化 / 应用更新 / 精确闹钟权限被授予）→ 重排一次。
 *
 * 闹钟触发时进程可能已被杀，所以走 goAsync + 协程，声明"我还没干完"，
 * 避免 onReceive 一返回进程就被回收、通知还没发出去。
 */
class ClassReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            try {
                when (action) {
                    ClassReminderScheduler.ACTION_FIRE -> fire(appContext, intent)
                    else -> ClassReminderScheduler.reschedule(appContext)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "处理上课提醒广播失败: $action", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fire(context: Context, intent: Intent) {
        val store = AppGraph.uiPrefsStore
        val prefs = runCatching { store.flow.first() }.getOrNull()
        // 关掉开关后可能还有一枚已排的闹钟没撤销：到点也不许打扰
        if (prefs == null || !prefs.classReminderEnabled) {
            ClassReminderScheduler.cancel(context)
            return
        }

        val key = intent.getStringExtra(ClassReminderScheduler.EXTRA_KEY).orEmpty()
        val startAt = intent.getLongExtra(ClassReminderScheduler.EXTRA_START_AT, 0L)
        if (key.isBlank() || startAt <= 0L) return

        val now = System.currentTimeMillis()
        // 课早已经开始很久才触发（设备沉睡/ ROM 拦截）：不弹过期提醒，直接排下一节
        if (now > startAt + 5 * 60_000L) {
            store.setClassReminderLastKey(key)
            ClassReminderScheduler.reschedule(context)
            return
        }

        val plan = ClassReminderPlan(
            key = key,
            title = intent.getStringExtra(ClassReminderScheduler.EXTRA_TITLE).orEmpty(),
            subtitle = intent.getStringExtra(ClassReminderScheduler.EXTRA_SUBTITLE).orEmpty(),
            startAt = startAt,
            notifyAt = startAt - prefs.classReminderLeadMinutes * 60_000L,
            isConflict = intent.getBooleanExtra(ClassReminderScheduler.EXTRA_CONFLICT, false),
        )

        // 去重：只有"没提醒过这一节"才弹。换课表/改设置重排后可能同一节排两次闹钟。
        val lastKey = runCatching { store.classReminderLastKey() }.getOrNull()
        if (lastKey != key) {
            AppGraph.classReminderNotifications.notify(plan, now)
            store.setClassReminderLastKey(key)
        }
        // 链式排下一节
        ClassReminderScheduler.reschedule(context)
    }

    private companion object {
        const val TAG = "ClassReminderReceiver"
    }
}
