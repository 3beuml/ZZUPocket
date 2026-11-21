package com.zzupocket.app.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.repo.TodayTimetable
import com.zzupocket.app.ui.today.TodayTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 上课提醒的排程器：**按需唤醒，无常驻服务**。
 *
 * 设计要点（对应"低占用 + 不干扰用户闹铃"）：
 *  - 同一时刻只存在**一个**待触发闹钟（固定 requestCode 的 PendingIntent，重设即覆盖）；
 *  - 用 [AlarmManager.setExactAndAllowWhileIdle]（RTC_WAKEUP，后台可唤醒）；
 *    没有精确闹钟权限时降级成 [AlarmManager.setAndAllowWhileIdle]（可能被系统延后几分钟）；
 *  - **不用 setAlarmClock**：那个会出现在系统时钟/闹铃列表、带闹钟图标，正是用户不想要的打扰；
 *  - 触发后写去重键并重排下一节，形成链；课表变化 / 时间变化 / 开机 / 权限授予后也会重排。
 *
 * 这里只读本地课表缓存（TodayTimetable.sessionsOn + periods），不联网。
 */
object ClassReminderScheduler {

    const val ACTION_FIRE = "com.zzupocket.app.reminder.FIRE"
    const val ACTION_RESCHEDULE = "com.zzupocket.app.reminder.RESCHEDULE"

    const val EXTRA_KEY = "key"
    const val EXTRA_TITLE = "title"
    const val EXTRA_SUBTITLE = "subtitle"
    const val EXTRA_START_AT = "start_at"
    const val EXTRA_CONFLICT = "conflict"

    private const val REQUEST_FIRE = 7301
    private const val TAG = "ClassReminderScheduler"

    /**
     * 重排下一节课的提醒。任何课表/设置/时间来源变化后都应调用。
     * 关掉开关时会撤销闹钟（不留后台唤醒）。
     */
    suspend fun reschedule(context: Context) {
        val store = AppGraph.uiPrefsStore
        val prefs = runCatching { store.flow.first() }.getOrNull()
        if (prefs == null || !prefs.classReminderEnabled) {
            cancel(context)
            return
        }

        val now = System.currentTimeMillis()
        val lastKey = runCatching { store.classReminderLastKey() }.getOrNull()
        val plan = peekNext(now, prefs.classReminderLeadMinutes, lastKey)
        if (plan == null) {
            cancel(context)
            return
        }
        arm(context, plan)
    }

    /** 非 suspend 包装：给 BroadcastReceiver / 生命周期回调 fire-and-forget 用 */
    fun rescheduleAsync(context: Context) {
        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            runCatching { reschedule(context.applicationContext) }
                .onFailure { Log.w(TAG, "重排上课提醒失败", it) }
        }
    }

    /** 供设置页展示"下一节提醒的是什么" */
    suspend fun peekNext(
        now: Long = System.currentTimeMillis(),
        leadMinutes: Int,
        excludeKey: String? = null,
    ): ClassReminderPlan? = computePlan(now, leadMinutes, excludeKey)

    private suspend fun computePlan(now: Long, leadMinutes: Int, excludeKey: String?): ClassReminderPlan? {
        val repo = AppGraph.scheduleRepository
        val prefs = AppGraph.uiPrefsStore
        // 课表计算是 suspend，先同步取好未来 N 天的数据，再交给纯函数 planner 挑下一节
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val periods = runCatching { repo.periods() }.getOrDefault(emptyList())
        val days = (0 until ClassReminderPlanner.DEFAULT_SCAN_DAYS).associate { offset ->
            val date = today.plusDays(offset.toLong())
            val entries = runCatching {
                val sessions = TodayTimetable.sessionsOn(repo, prefs, date)
                TodayTimeline.courseEntries(TodayTimetable.displayBlocks(repo, sessions), periods)
            }.getOrDefault(emptyList())
            date to entries
        }
        return ClassReminderPlanner.planNext(
            today = today,
            nowMillis = now,
            leadMinutes = leadMinutes,
            excludeKey = excludeKey,
            entriesOn = { date -> days[date].orEmpty() },
        )
    }

    private fun arm(context: Context, plan: ClassReminderPlan) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = fireIntent(context, plan)
        runCatching { am.cancel(pi) }

        // 触发时刻若已过（例如刚打开 App 时已落在提前窗口内），立刻触发一次
        val triggerAt = maxOf(plan.notifyAt, System.currentTimeMillis() + 1_000L)
        runCatching {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                // 没有精确闹钟权限：仍能提醒，但 Doze/省电下系统可能延后几分钟
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }.onFailure { Log.w(TAG, "设置上课提醒闹钟失败", it) }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(fireIntent(context, null)) }
    }

    private fun fireIntent(context: Context, plan: ClassReminderPlan?): PendingIntent {
        val intent = Intent(context, ClassReminderReceiver::class.java).setAction(ACTION_FIRE)
        plan?.let {
            intent.putExtra(EXTRA_KEY, it.key)
                .putExtra(EXTRA_TITLE, it.title)
                .putExtra(EXTRA_SUBTITLE, it.subtitle)
                .putExtra(EXTRA_START_AT, it.startAt)
                .putExtra(EXTRA_CONFLICT, it.isConflict)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_FIRE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 精确闹钟权限：Android 12+ 用 canScheduleExactAlarms；minSdk=31，无需版本判断 */
    fun canScheduleExact(context: Context): Boolean = runCatching {
        val am = context.getSystemService(AlarmManager::class.java)
        am != null && am.canScheduleExactAlarms()
    }.getOrDefault(false)

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 跳系统「闹钟和提醒」设置页让用户授权精确闹钟（系统按调用方包名识别，不用带 data URI） */
    fun exactAlarmSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
