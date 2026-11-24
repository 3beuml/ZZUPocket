package com.zzupocket.app.ui.campus

import android.util.Log
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.eas.EasClient
import com.zzupocket.app.data.repo.RoomCacheRow
import com.zzupocket.app.data.repo.RoomRepository
import com.zzupocket.app.domain.RoomDayResult
import com.zzupocket.app.domain.RoomNames
import com.zzupocket.app.domain.RoomOccupation
import com.zzupocket.app.domain.RoomSnapshotPlan
import com.zzupocket.app.domain.shouldContinuePrefetch
import java.time.LocalDate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 接口一页教室 → 快照行（UI 落库与全量分批管线共用，口径才不会分叉） */
internal fun roomRowsToCacheRows(
    result: EasClient.ClassroomPageDto,
    buildings: List<EasClient.BuildingDto>,
): List<RoomCacheRow> {
    val buildingNames = buildings.associate { it.id to it.nameZh }
    return result.data.filterNot { it.virtual }.map { room ->
        RoomCacheRow(
            roomId = room.id,
            code = RoomNames.numberOf(room.nameZh, room.code),
            buildingId = room.buildingId,
            buildingName = buildingNames[room.buildingId] ?: "未知楼栋",
            campusName = room.campusNameZh.orEmpty(),
            occupations = room.roomOccupationInfoVms.map {
                RoomOccupation(it.startTime, it.endTime, it.activityType.orEmpty())
            },
        )
    }
}

/**
 * 空教室快照的全量分批管线与 app 级预取协调者（2026-11-24 拍板重做）。
 *
 * 管线（[fetchAndSaveDay]，自动预取与手动保存共用）：
 *  - 楼栋按 [RoomSnapshotPlan.batches] 分批：批 1 = 置顶核心楼（北1–5、南1–5，
 *    与筛选置顶同一逻辑），其余每 [RoomSnapshotPlan.REST_BATCH_SIZE] 栋一批；
 *    逐批抓、逐批落库——中途断网时核心楼已经保住；
 *  - 每批先查该批楼栋是否"当天已抓过"，是则整批跳过——二次进页零请求，
 *    中断重跑只补缺的批；
 *  - 每批攒齐该批所有页后**只写本批楼栋**（saveRoomSnapshotBatch），绝不
 *    replace 整天——其他批已落库的行不能被删（2026-11-11 每页互删的教训）。
 *
 * 协调者：预取跑在 [AppGraph.applicationScope]（进程内存活），用户翻出页面
 * （ViewModel 销毁）不再取消——旧实现跑在 viewModelScope，中断后"补全其余批"
 * 只能等下次进页，残缺快照就这样一直挂着（2026-11-24 用户实测）。
 * 进程被杀属尽力而为：下次进页按批跳过已完成的，自愈补齐。
 */
internal object RoomPrefetch {

    private const val TAG = "RoomPrefetch"

    /** 手动保存与预取轮共用管线：串行化，避免同一天并行抓两遍 */
    private val pipelineMutex = Mutex()

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    /**
     * 进页联网成功后启动一轮预取：**今天 ~ 今天+[RoomSnapshotPlan.PREFETCH_DAYS]**
     * 全量落库（2026-11-11 曾收窄到 3 天求完整；2026-11-24 扩回 5 天与保留窗口
     * 对齐——分批落库后中断也保得住核心批，完整性不再依赖"一轮跑完"）。
     * 串行逐日；连续 [shouldContinuePrefetch] 天不完整就停（多半断网了）。
     */
    fun start(campusId: Long, buildings: List<EasClient.BuildingDto>) {
        if (running) return
        if (buildings.isEmpty()) return
        running = true
        AppGraph.applicationScope.launch {
            try {
                var consecutiveFailures = 0
                for (offset in 0..RoomSnapshotPlan.PREFETCH_DAYS) {
                    val day = LocalDate.now().plusDays(offset.toLong())
                    val result = runCatching { fetchAndSaveDay(campusId, buildings, day) }
                        .getOrElse { e ->
                            Log.w(TAG, "预取 $day 失败", e)
                            RoomDayResult(totalBatches = 0, savedBatches = 0, skippedBatches = 0)
                        }
                    consecutiveFailures = if (result.complete) 0 else consecutiveFailures + 1
                    Log.i(
                        TAG,
                        "预取 $day：${result.totalBatches} 批（存 ${result.savedBatches} / 跳 ${result.skippedBatches}）" +
                            "完整=${result.complete}",
                    )
                    if (!shouldContinuePrefetch(consecutiveFailures)) {
                        Log.w(TAG, "连续 $consecutiveFailures 天不完整，本轮预取停止")
                        break
                    }
                }
            } finally {
                running = false
            }
        }
    }

    /**
     * 抓取某一天的全量教室占用并**分批**落库（自动预取与手动保存共用）。
     * 返回各批完成情况：saved + skipped == total 即当天完整。
     */
    internal suspend fun fetchAndSaveDay(
        campusId: Long,
        buildings: List<EasClient.BuildingDto>,
        date: LocalDate,
    ): RoomDayResult = pipelineMutex.withLock {
        val scheduleRepo = AppGraph.scheduleRepository
        // 每楼最后一次抓取时间：某批**所有楼栋**都"当天抓过"才整批跳过——
        // 残缺的批（部分楼缺行）会被重抓补齐，旧版 30 间残缺快照由此自愈
        val fetchedByBuilding = runCatching {
            scheduleRepo.roomSnapshotFetchedAtByBuilding(date.toString(), campusId)
        }.getOrDefault(emptyMap())
        val todayStart = LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val batches = RoomSnapshotPlan.batches(
            buildings.map { it.id to it.nameZh.ifBlank { it.code } },
        )
        var saved = 0
        var skipped = 0
        for ((index, batch) in batches.withIndex()) {
            if (batch.all { (fetchedByBuilding[it] ?: 0L) >= todayStart }) {
                skipped++
                continue
            }
            val rows = try {
                fetchAllPages(batch, date, buildings)
            } catch (e: Throwable) {
                Log.w(TAG, "快照批次 ${index + 1}/${batches.size} 抓取失败，跳过该批", e)
                continue
            }
            if (rows.isEmpty()) {
                // 空响应不写库：删了好数据又没有替代行等于误删；该批留待下次重试
                Log.w(TAG, "快照批次 ${index + 1}/${batches.size} 返回 0 间，留待重试")
                continue
            }
            runCatching {
                scheduleRepo.saveRoomSnapshotBatch(date.toString(), campusId, batch, rows)
            }.onFailure { e ->
                Log.w(TAG, "快照批次 ${index + 1}/${batches.size} 落库失败", e)
            }.onSuccess {
                saved++
                // 诊断（仅 debug 包）：该批请求了哪些楼、各回来多少间
                if (com.zzupocket.app.BuildConfig.DEBUG) {
                    val byBuilding = rows.groupBy { it.buildingId }
                        .map { (b, r) -> "b$b:${r.size}" }.sorted()
                    Log.i(TAG, "批次 ${index + 1}/${batches.size}：${rows.size} 间 [${byBuilding.joinToString(",")}]")
                }
            }
        }
        RoomDayResult(totalBatches = batches.size, savedBatches = saved, skippedBatches = skipped)
    }

    /** 抓一批楼栋某天的**全部页**（页大小 100；攒齐该批才落库） */
    private suspend fun fetchAllPages(
        batch: List<Long>,
        date: LocalDate,
        buildings: List<EasClient.BuildingDto>,
    ): List<RoomCacheRow> {
        val all = mutableListOf<RoomCacheRow>()
        var page = 1
        while (true) {
            val result = AppGraph.roomRepository.roomsPage(
                date = date,
                buildingIds = batch,
                page = page,
                pageSize = RoomRepository.PAGE_SIZE_LARGE,
            )
            all += roomRowsToCacheRows(result, buildings)
            val totalPages = result.page?.totalPages ?: 1
            if (page >= totalPages) break
            page++
        }
        return all.distinctBy { it.roomId }
    }
}
