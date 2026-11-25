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
 * 课程提醒广播接收器。两类 action：
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
                Log.w(TAG, "处理课程提醒广播失败: $action", t)
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
        // 课早已经开始很久才触发（设备沉睡 / ROM 拦截）：不弹过期提醒，直接排下一节。
        // 迟到容忍分钟数可在设置页调（默认 5），偏保守的同学可以调大到 30。
        val lateLimitMs = prefs.classReminderLateMinutes * 60_000L
        if (now > startAt + lateLimitMs) {
            Log.i(
                TAG,
                "丢弃过期提醒：late=${(now - startAt) / 60_000L}min > limit=${prefs.classReminderLateMinutes}min, key=$key",
            )
            store.setClassReminderLastKey(key)
            // 这节已过去，planner 不会再选它；安全地往下排
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
            val sent = AppGraph.classReminderNotifications.notify(plan, now)
            if (sent) {
                // 只有真的发出去了才写去重键：发不出去时写键会让这节永远不再提醒
                store.setClassReminderLastKey(key)
            } else {
                Log.w(TAG, "通知发送失败，不写去重键，保留下次再提醒的机会: $key")
                // 不链式重排：此刻重排会算出同一节（它还没开始），arm 立即触发 → 快速重试死循环。
                // 留给下一次 onResume / 课表同步 / 系统事件再排；权限开通后 onResume 即可补上。
                return
            }
        }
        // 链式排下一节
        ClassReminderScheduler.reschedule(context)
    }

    private companion object {
        const val TAG = "ClassReminderReceiver"
    }
}
