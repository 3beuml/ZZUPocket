package com.zzupocket.app.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zzupocket.app.core.AppGraph
import java.util.concurrent.TimeUnit

/**
 * 电量定时采样。
 *
 * 为什么必须要它：**学校不提供历史用电量**（P4-0 实测：两个房间都回"所选房间不支持
 * 查询历史用电量"，原因是"未配置厂商"）。所以"每天用了多少度"只能靠我们自己按点
 * 采样余额、再用相邻差值算。采样越密，用电量越准。
 *
 * 节奏参考社区项目 ZZU-Electricity-Monitor：北京时间 0/4/8/12/16/20 点，
 * **每 4 小时一次**。这里用系统允许的最小周期（15 分钟的下限之上取 4 小时）。
 *
 * ⚠️ 诚实说明：国产系统（含这台 MEIZU）可能延迟甚至杀掉后台任务，
 * 所以定时采样只是"尽力而为"，真正的保底是**每次打开 App 顺手采一次**。
 */
class EnergySamplingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!AppGraph.authRepository.isLoggedIn()) return Result.success()
        val (lightRoom, acRoom) = AppGraph.electricityRepository.effectiveRooms()
        // 用户还没设置自己的宿舍房间就别采了（早期版本会回落到开发者自己的房间号）
        if (lightRoom.isBlank() && acRoom.isBlank()) return Result.success()
        return try {
            AppGraph.electricityRepository.refreshAll(
                lightRoomId = lightRoom,
                acRoomId = acRoom,
            )
            Result.success()
        } catch (e: Throwable) {
            // 失败就让系统按退避策略重试；令牌过期这类问题重试也会失败，
            // 但不影响下次 App 打开时的补采
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "zzupocket-energy-sampling"

        /** 幂等：已经排过就保持原样，不会重复排 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EnergySamplingWorker>(
                4, TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }
        }

        fun cancel(context: Context) {
            runCatching {
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
            }
        }
    }
}
