package com.zzupocket.app

import android.app.Application
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.work.EnergySamplingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ZZUPocketApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        AppGraph.pomodoroNotifications.ensureChannels()
        AppGraph.classReminderNotifications.ensureChannel()

        // 上课提醒：启动时重排一次（闹钟可能被 ROM 清掉、课表可能在别处更新过）。
        // 开关默认关；关着的时候 reschedule 只会撤销旧闹钟，不留任何后台唤醒。
        com.zzupocket.app.reminder.ClassReminderScheduler.rescheduleAsync(this)

        // 电量定时采样：学校不给历史用电量，只能靠按点采样差分算（尽力而为）
        EnergySamplingWorker.schedule(this)

        // 番茄钟可能在上一轮进程里就被杀了 —— 启动时把状态接回来。
        // 剩余时间是用墙钟算的，所以"接回来"之后不用做任何补偿。
        appScope.launch {
            runCatching { AppGraph.pomodoroEngine.restore() }
        }
    }
}
