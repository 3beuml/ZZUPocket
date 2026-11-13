package com.zzupocket.app.ui.campus

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.toUserMessage
import com.zzupocket.app.data.eas.EasClient
import com.zzupocket.app.data.repo.RoomRepository
import com.zzupocket.app.data.repo.ScheduleRepository
import com.zzupocket.app.domain.Period
import com.zzupocket.app.domain.RoomBuildingOrder
import com.zzupocket.app.domain.RoomNames
import com.zzupocket.app.domain.RoomOccupation
import com.zzupocket.app.domain.RoomOccupancyGrid
import com.zzupocket.app.domain.RoomRow
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RoomUiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val campuses: List<EasClient.CampusDto> = emptyList(),
    val campusId: Long? = null,
    /** 当前校区的楼栋，**已按展示顺序排好**（北1–5、南1–5 置顶） */
    val buildings: List<EasClient.BuildingDto> = emptyList(),
    /** 选中的楼栋；空 = 全部 */
    val selectedBuildings: Set<Long> = emptySet(),
    val date: LocalDate = LocalDate.now(),
    /** 当前学期的起止日；日历只允许在这区间里选（避免翻到假期看到空表） */
    val semesterRange: Pair<LocalDate, LocalDate>? = null,
    val periods: List<Period> = emptyList(),
    /** 选中的节次（indexNo）；空 = 不按节次过滤 */
    val selectedUnits: Set<Int> = emptySet(),
    val groups: List<Pair<String, List<RoomRow>>> = emptyList(),
    val loadedRooms: Int = 0,
    val page: Int = 1,
    val totalPages: Int = 1,
    val lastFetchedAt: Long? = null,
    /** true = 当前表格来自本地快照（教务暂时取不到） */
    val fromCache: Boolean = false,
    /**
     * 缓存提示（一行灰字）：null = 什么都不显示。
     *
     * 空教室是"当天某时刻"的数据，过期了会误导人，所以提示必须**常显获取时间**；
     * 但不用红字 —— 红字会让人以为是自己账号的问题。
     */
    val notice: String? = null,
    val showFilter: Boolean = false,
    val showDatePicker: Boolean = false,
    /** 长按教室号弹出的原始字段（排查用） */
    val rawRoom: RoomRow? = null,
    val hiddenVirtual: Int = 0,
    /**
     * error 是"校园网安全网关要求认证"类提示时置 true：界面据此给
     * 「去补录密码」按钮（跳登录页）——老用户已登录且无登出功能，
     * 这是他们把密码交给自动认证的唯一路径（2026-11-12）。
     */
    val errorNeedsRelogin: Boolean = false,
    /**
     * 快照自带的校区名（字典没起来时界面汇总行用它，别把北校区写成"主校区"）。
     * 字典加载成功后置回 null。
     */
    val snapshotCampusName: String? = null,
) {
    val campusName: String
        get() = campuses.firstOrNull { it.id == campusId }?.nameZh
            ?: snapshotCampusName
            ?: "主校区"

    val buildingSummary: String
        get() = if (selectedBuildings.isEmpty()) {
            "全部楼栋（${buildings.size}）"
        } else {
            "已选 ${selectedBuildings.size} 栋"
        }

    val visibleCount: Int get() = groups.sumOf { it.second.size }

    /** "全部节次" / "第 3、4 节" */
    val unitSummary: String
        get() = if (selectedUnits.isEmpty()) {
            "全部节次"
        } else {
            "第 " + selectedUnits.sorted().joinToString("、") + " 节"
        }
}

class RoomViewModel : ViewModel() {

    private val repo: RoomRepository = AppGraph.roomRepository
    private val scheduleRepo: ScheduleRepository = AppGraph.scheduleRepository

    private val _state = MutableStateFlow(RoomUiState())
    val state: StateFlow<RoomUiState> = _state.asStateFlow()

    /** 全量教室（未按节次过滤），改节次筛选时不用重新请求 */
    private var allRooms: List<RoomRow> = emptyList()

    /** 一次动作里最多自动续几页（防止"筛出来的太少"时无限拉） */
    private var autoFills = 0

    init {
        viewModelScope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        _state.update { it.copy(loading = true, error = null) }
        Log.i(TAG, "bootstrap：进页")
        try {
            val periods = scheduleRepo.periods()
            val semester = scheduleRepo.currentSemester()
            val range = if (semester?.startDate != null && semester.endDate != null) {
                semester.startDate to semester.endDate
            } else {
                null
            }
            _state.update {
                it.copy(periods = periods, semesterRange = range)
            }
            // ① 先用本地快照渲染（教务挂了/断网也能看今天早上抓到的状态）。
            // ⚠️ 快照渲染必须放在任何网络调用之前：断网时 campuses() 会抛，
            // 走不到快照渲染的话明明有数据也整页红条（2026-11-05 用户蜂窝实测，#15 复发）。
            val shownFromCache = renderCachedSnapshot()
            Log.i(
                TAG,
                "bootstrap：快照渲染 shown=$shownFromCache rows=${_state.value.loadedRooms} " +
                    "fetchedAt=${_state.value.lastFetchedAt} campusId=${_state.value.campusId}",
            )
            // ② 校区/楼栋字典失败不再炸整页：有快照就停在快照 + 灰字，没快照才红条
            val dictResult = runCatching {
                val campuses = repo.campuses()
                val campus = campuses.firstOrNull { it.nameZh.contains("主校区") } ?: campuses.firstOrNull()
                if (campus != null) {
                    val buildings = orderedBuildings(repo.buildings(campus.id))
                    _state.update {
                        it.copy(
                            campuses = campuses,
                            campusId = campus.id,
                            buildings = buildings,
                            // 字典回来了：汇总行别再用快照里的校区名
                            snapshotCampusName = null,
                        )
                    }
                }
                campus != null
            }
            if (!dictResult.getOrDefault(false)) {
                Log.w(TAG, "bootstrap：字典加载失败", dictResult.exceptionOrNull())
                // 字典彻底拿不到（网络 + 盘里都没有）：从快照行反推楼栋列表，
                // 保住筛选弹窗可用——断网也该能按楼栋看快照（2026-11-12 用户实测：
                // 字典失败后弹窗 0 栋、刷新卡死，一整天只能瞪着快照）。
                deriveBuildingsFromSnapshot()
                degradeToCache(
                    dictResult.exceptionOrNull() ?: IllegalStateException("校区字典不可用"),
                )
                return
            }
            Log.i(TAG, "bootstrap：字典 OK buildings=${_state.value.buildings.size}")
            // ③ 距上次成功抓取不足 30 分钟就不联网了（空教室是分钟级数据，但也不该每秒重拉）
            if (shownFromCache && !com.zzupocket.app.core.SyncPolicy.shouldRefresh(
                    now = System.currentTimeMillis(),
                    lastFetchedAt = _state.value.lastFetchedAt,
                    ttlMs = ROOM_TTL_MS,
                )
            ) {
                Log.i(TAG, "bootstrap：快照够新（${_state.value.lastFetchedAt}），跳过联网")
                _state.update { it.copy(loading = false) }
                startPrefetchIfOnline()
                return
            }
            Log.i(TAG, "bootstrap：开始联网抓取")
            fetch(reset = true)
            Log.i(TAG, "bootstrap：抓取完成 rooms=${_state.value.loadedRooms}")
            startPrefetchIfOnline()
        } catch (e: Throwable) {
            Log.w(TAG, "bootstrap：异常降级", e)
            degradeToCache(e)
        }
    }

    /**
     * 网络失败的统一降级：**有快照就不报红错**（红字会让人以为账号出问题），
     * 留一行灰字说明数据是几点抓的；真没快照才红条。
     */
    /**
     * 切换（日期 / 楼栋 / 校区）后的离线兜底：为**切换后的新状态**读本地快照并渲染。
     *
     * 之前的降级只把**旧状态的数据**留在屏上（灰字提示"获取于 HH:MM"）——
     * 用户切了日期看到的还是上一个日期的占用，切了楼栋筛选看起来毫无反应
     * （2026-11-11 用户实测）。快照覆盖 昨天~今天+5（在线时抓取即存 + 预取 +1~+5），
     * 切进这个窗口应当直接出快照数据，而不是老画面。
     *
     * 返回 false = 新状态确实没有可用快照（调用方再走 [degradeToCache]）。
     */
    private suspend fun renderSnapshotForCurrentState(): Boolean {
        val s = _state.value
        val snap = runCatching {
            // 先精确匹配当前校区；campusId 未知（boot 未完成）才退「该日期最近抓取的任意校区」
            // ——切校区离线时 campusId 已知，退到别的校区的快照会张冠李戴
            s.campusId?.let {
                AppGraph.scheduleRepository.cachedRoomSnapshot(s.date.toString(), it)
            } ?: AppGraph.scheduleRepository.cachedRoomSnapshotAnyCampus(s.date.toString())
        }.getOrNull() ?: return false
        if (snap.rows.isEmpty()) return false
        val units = s.periods.map { it.startTime to it.endTime }
        val rows = snap.rows.map { r ->
            RoomRow(
                id = r.roomId,
                code = r.code,
                buildingId = r.buildingId,
                buildingName = r.buildingName,
                campusName = r.campusName,
                occupied = RoomOccupancyGrid.grid(r.occupations, units),
            )
        }
        // 楼栋筛选是服务端做的（快照按抓取时的筛选落盘）——离线在本地补一道
        val filtered = if (s.selectedBuildings.isEmpty()) {
            rows
        } else {
            rows.filter { it.buildingId in s.selectedBuildings }
        }
        if (filtered.isEmpty()) return false
        allRooms = filtered
        val groups = rebuildGroups(filtered, s.buildings.map { it.id }, s.selectedUnits)
        _state.update {
            it.copy(
                loading = false,
                loadingMore = false,
                error = null,
                campusId = s.campusId ?: snap.campusId,
                snapshotCampusName = filtered.first().campusName.ifBlank { null },
                groups = groups,
                loadedRooms = filtered.size,
                lastFetchedAt = snap.fetchedAt,
                fromCache = true,
                notice = noticeOf(snap.fetchedAt),
            )
        }
        return true
    }

    /**
     * 字典彻底拿不到时，从已渲染的快照行反推楼栋列表（id + 楼栋名），
     * 让筛选弹窗在断网时也有东西可选、离线筛选有 ids 可用。
     * 只在 buildings 为空时生效；字典恢复后会被正常字典覆盖。
     */
    private fun deriveBuildingsFromSnapshot() {
        val s = _state.value
        if (s.buildings.isNotEmpty() || allRooms.isEmpty()) return
        val derived = allRooms
            .filter { it.buildingName.isNotBlank() }
            .distinctBy { it.buildingId }
            .map { EasClient.BuildingDto(id = it.buildingId, nameZh = it.buildingName) }
        if (derived.isEmpty()) return
        _state.update { it.copy(buildings = derived) }
    }

    private fun degradeToCache(e: Throwable) {
        Log.w(TAG, "空教室降级：快照兜底 rows=${allRooms.size} auth=${e is com.zzupocket.app.core.AppError.ReLoginRequired}", e)
        if (allRooms.isNotEmpty()) {
            // 鉴权失效必须亮出来（红条）：温和的"获取于 HH:mm · 点此刷新"会把它
            // 吞掉——用户不知道要重新登录，只会觉得"点了刷新没反应"
            // （2026-11-12 用户实测：令牌过期 + 字典无兜底 → 整页卡死在快照上）。
            val authMessage = when (e) {
                is com.zzupocket.app.core.AppError.ReLoginRequired -> e.toUserMessage()
                // 统一身份验证网关要求认证：重登教务令牌解决不了，必须引导去浏览器认证
                is com.zzupocket.app.core.AppError.ZeroTrustAuthRequired -> e.toUserMessage()
                else -> null
            }
            _state.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    error = authMessage,
                    errorNeedsRelogin = e is com.zzupocket.app.core.AppError.ZeroTrustAuthRequired,
                    fromCache = true,
                    notice = if (authMessage != null) {
                        null
                    } else {
                        noticeOf(it.lastFetchedAt) ?: "暂时连不上教务系统 · 点此重试"
                    },
                )
            }
        } else {
            _state.update {
                it.copy(
                    loading = false,
                    error = e.toUserMessage(),
                    errorNeedsRelogin = e is com.zzupocket.app.core.AppError.ZeroTrustAuthRequired,
                )
            }
        }
    }

    /** 空教室缓存有效期：30 分钟（当天课表，变化少但毕竟是"现在是否有课"） */
    private val ROOM_TTL_MS = 30L * 60 * 1000

    /** 一行灰字里的"获取于 HH:MM"；不是今天的快照要带日期——昨天的 09:34 看起来像今天会误导人 */
    private fun noticeOf(fetchedAt: Long?): String? = fetchedAt?.let {
        val zoned = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
        val time = zoned.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val day = when (zoned.toLocalDate()) {
            java.time.LocalDate.now() -> ""
            java.time.LocalDate.now().minusDays(1) -> "昨天 "
            else -> zoned.toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("M月d日 ")) + " "
        }
        "获取于 " + day + time
    }

    /**
     * 用本地快照渲染当前日期；返回 true 表示有可用快照。
     *
     * 快照里存的是**原始占用时间段**，所以这里用当前节次表重新算格子 ——
     * 作息变了也不会错位。不依赖校区字典（断网时字典拿不到，快照自带 campusId）。
     */
    private suspend fun renderCachedSnapshot(): Boolean {
        val s = _state.value
        val snap = runCatching {
            AppGraph.scheduleRepository.cachedRoomSnapshotAnyCampus(s.date.toString())
        }.getOrNull() ?: return false
        if (snap.rows.isEmpty()) return false
        val units = s.periods.map { it.startTime to it.endTime }
        val rows = snap.rows.map { r ->
            RoomRow(
                id = r.roomId,
                code = r.code,
                buildingId = r.buildingId,
                buildingName = r.buildingName,
                campusName = r.campusName,
                occupied = RoomOccupancyGrid.grid(r.occupations, units),
            )
        }
        allRooms = rows
        val groups = rebuildGroups(rows, s.buildings.map { it.id }, s.selectedUnits)
        val snapCampusId = snap.campusId
        val snapCampusName = rows.firstOrNull()?.campusName.orEmpty()
        _state.update {
            it.copy(
                loading = false,
                error = null,
                // 快照自带 campusId：字典没起来时用它顶上（校区名同取快照），
                // 这样后续 refresh 的 fetch 有 campusId 可用，不会卡死
                campusId = s.campusId ?: snapCampusId,
                campuses = s.campuses.ifEmpty {
                    listOfNotNull(
                        snapCampusId?.let { id ->
                            EasClient.CampusDto(id = id, nameZh = snapCampusName)
                        },
                    )
                },
                snapshotCampusName = snapCampusName.ifBlank { null },
                groups = groups,
                loadedRooms = rows.size,
                lastFetchedAt = snap.fetchedAt,
                fromCache = true,
                // 只要来自快照就必须标时间，避免被当成"此刻的实时状态"
                notice = noticeOf(snap.fetchedAt),
            )
        }
        return true
    }

    private fun orderedBuildings(
        list: List<EasClient.BuildingDto>,
    ): List<EasClient.BuildingDto> = RoomBuildingOrder.sorted(list) { it.nameZh.ifBlank { it.code } }

    /** 拉教室（[reset] = 从第一页开始） */
    private suspend fun fetch(reset: Boolean) {
        val s = _state.value
        Log.i(
            TAG,
            "fetch：reset=$reset date=${s.date} campusId=${s.campusId} " +
                "buildings=${s.buildings.size} selected=${s.selectedBuildings.size} page=${s.page}",
        )
        // ⚠️ campusId 没就绪必须**抛错**而不是静默 return：静默返回会让调用方
        // （refresh 的 loading=true）永远等不到 loading=false——之后 refresh 的
        // `if (loading) return` 把所有刷新永远拒之门外，用户看到的就是
        // "刷新/筛选都没反应"（2026-11-12 用户实测 0.36.1）。
        val campusId = s.campusId ?: throw IllegalStateException("校区字典未加载，无法查询教室")
        val ids = if (s.selectedBuildings.isEmpty()) {
            s.buildings.map { it.id }
        } else {
            s.buildings.map { it.id }.filter { it in s.selectedBuildings }
        }
        if (ids.isEmpty()) {
            allRooms = emptyList()
            _state.update { it.copy(loading = false, groups = emptyList(), loadedRooms = 0) }
            return
        }
        if (reset) {
            repo.invalidateRooms()
            autoFills = 0
        }
        val page = if (reset) 1 else s.page + 1
        // 选定了楼栋时把每页提到 100 间：一栋楼通常 15–60 间，一次基本就够，
        // 免得筛完节次还要手动划好几页
        val pageSize = if (s.selectedBuildings.isEmpty()) {
            RoomRepository.PAGE_SIZE
        } else {
            RoomRepository.PAGE_SIZE_LARGE
        }
        val result = repo.roomsPage(
            date = s.date,
            buildingIds = ids,
            page = page,
            pageSize = pageSize,
        )
        Log.i(TAG, "fetch：拿到 ${result.data.size} 间（page=$page/${result.page?.totalPages}）")
        val units = s.periods.map { it.startTime to it.endTime }
        val buildingNames = s.buildings.associate { it.id to it.nameZh }
        val newRows = result.data.filterNot { it.virtual }.map { room ->
            RoomRow(
                id = room.id,
                code = RoomNames.numberOf(room.nameZh, room.code),
                buildingId = room.buildingId,
                buildingName = buildingNames[room.buildingId] ?: "未知楼栋",
                campusName = room.campusNameZh.orEmpty(),
                // 纯展示用：分组和过滤都靠它
                occupied = RoomOccupancyGrid.grid(
                    room.roomOccupationInfoVms.map {
                        RoomOccupation(it.startTime, it.endTime, it.activityType.orEmpty())
                    },
                    units,
                ),
                rawNameZh = room.nameZh,
                rawCode = room.code,
            )
        }
        allRooms = if (reset) newRows else (allRooms + newRows).distinctBy { it.id }
        // 落当天快照：教务挂了/断网时还能看到"上次抓到的状态 + 时间"。
        // reset（第一页）= replace；续页 = append 累积——否则"今天"的快照
        // 永远只有第一页（2026-11-11 自检发现）。
        // ⚠️ **筛选态不落快照**：快照的语义是"该日期+校区的全量占用"，
        // 把只含所选楼栋的结果 replace 进去会覆盖全量快照——之后所有
        // 快照渲染（冷启动断网兜底、切换兜底）都只剩那栋楼，看起来像
        // "app 记住了我上次选的楼"（2026-11-12 用户实测）。
        if (s.selectedBuildings.isEmpty()) {
            runCatching {
                AppGraph.scheduleRepository.saveRoomSnapshot(
                    date = s.date.toString(),
                    campusId = campusId,
                    rows = toCacheRows(result, s.buildings),
                    replace = reset,
                )
            }
        }
        val totalPages = result.page?.totalPages ?: 1
        val currentPage = result.page?.currentPage ?: page
        val order = s.buildings.map { it.id }
        val groups = rebuildGroups(allRooms, order, s.selectedUnits)
        _state.update {
            it.copy(
                loading = false,
                loadingMore = false,
                error = null,
                page = currentPage,
                totalPages = totalPages,
                loadedRooms = allRooms.size,
                lastFetchedAt = System.currentTimeMillis(),
                hiddenVirtual = it.hiddenVirtual + result.data.count { room -> room.virtual },
                groups = groups,
                fromCache = false,
                notice = null,
                // 网络实抓成功：汇总行回到字典口径
                snapshotCampusName = null,
            )
        }

        // 筛出来的太少就自动续页（最多 5 页），省得手动划
        val visible = groups.sumOf { it.second.size }
        if (visible < MIN_VISIBLE && currentPage < totalPages && autoFills < 5) {
            autoFills++
            _state.update { it.copy(loadingMore = true) }
            fetch(reset = false)
        }
    }

    /** 接口一页教室 → 快照行（当天快照与预取共用同一转换，口径才不会分叉） */
    private fun toCacheRows(
        result: EasClient.ClassroomPageDto,
        buildings: List<EasClient.BuildingDto>,
    ): List<com.zzupocket.app.data.repo.RoomCacheRow> {
        val buildingNames = buildings.associate { it.id to it.nameZh }
        return result.data.filterNot { it.virtual }.map { room ->
            com.zzupocket.app.data.repo.RoomCacheRow(
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

    /** 一次进页只做一轮预取（filter 切换/翻日不触发，失败当天内不反复重试） */
    private var prefetchJob: kotlinx.coroutines.Job? = null

    /**
     * 进页联网成功后，把**今天+1 ~ 今天+3** 的占用**完整**（所有页）预取落库
     * （2026-11-11 用户拍板收窄到 3 天：目标是应对教务系统临时出问题，
     * 完整比天数重要——此前的分页覆盖 bug 正是"求天数不求完整性"的产物。
     * 7 天保留窗口不变，昨天与浏览过的日期照旧在窗口内保留）。
     *
     * 串行、低优先、逐日跳过当天已抓过的：二次进页零请求；中途断网就整轮停掉。
     */
    private fun startPrefetchIfOnline() {
        if (prefetchJob?.isActive == true) return
        val s = _state.value
        if (s.date != LocalDate.now()) return
        val campusId = s.campusId ?: return
        val buildingIds = s.buildings.map { it.id }
        if (buildingIds.isEmpty()) return
        prefetchJob = viewModelScope.launch {
            for (offset in 1..PREFETCH_DAYS) {
                val day = LocalDate.now().plusDays(offset.toLong())
                runCatching { prefetchDay(campusId, buildingIds, day) }
                    .onFailure {
                        Log.w(TAG, "预取 $day 教室占用失败，本轮到此为止", it)
                        return@launch
                    }
            }
        }
    }

    private suspend fun prefetchDay(campusId: Long, buildingIds: List<Long>, date: LocalDate) {
        // 该天已有"今天抓的"快照就跳过（预取只在成功联网后跑，快照即新鲜）
        val existing = scheduleRepo.cachedRoomSnapshot(date.toString(), campusId)
        if (existing != null && java.time.Instant.ofEpochMilli(existing.fetchedAt)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
        ) {
            return
        }
        // 攒齐该日期的**所有页**再一次性落库——按页落库会互相覆盖，
        // 快照只剩最后一页的教室（根因见 saveRoomSnapshot 的 replace 注释）。
        val allRows = mutableListOf<com.zzupocket.app.data.repo.RoomCacheRow>()
        var page = 1
        while (true) {
            val result = repo.roomsPage(
                date = date,
                buildingIds = buildingIds,
                page = page,
                pageSize = RoomRepository.PAGE_SIZE_LARGE,
            )
            allRows += toCacheRows(result, _state.value.buildings)
            val totalPages = result.page?.totalPages ?: 1
            if (page >= totalPages) break
            page++
        }
        runCatching {
            AppGraph.scheduleRepository.saveRoomSnapshot(
                date = date.toString(),
                campusId = campusId,
                rows = allRows,
            )
        }
    }

    private fun rebuildGroups(
        rooms: List<RoomRow>,
        buildingOrder: List<Long>,
        selectedUnits: Set<Int>,
    ): List<Pair<String, List<RoomRow>>> {
        val periods = _state.value.periods
        val posOf = periods.withIndex().associate { (index, period) -> period.indexNo to index }
        val filtered = if (selectedUnits.isEmpty()) {
            rooms
        } else {
            // 选中的这几节**全都空着**才留下
            rooms.filter { room ->
                selectedUnits.all { no -> room.occupied.getOrNull(posOf[no] ?: -1) == false }
            }
        }
        return RoomOccupancyGrid.groupByBuilding(filtered, buildingOrder)
    }

    fun refresh() {
        if (_state.value.loading) {
            Log.w(TAG, "refresh：被 loading 守卫拦下（上一轮未结束）")
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // 字典强刷一次（成功则落盘也更新）。失败**不能吞**：
            // campusId 还没就绪时 fetch 无从谈起，把真实原因（典型：登录已失效）
            // 交给 degradeToCache 亮出来——否则用户看到的就是"刷新没反应"。
            val dictError = runCatching {
                repo.campuses(force = true)
                _state.value.campusId?.let { repo.buildings(it, force = true) }
            }.exceptionOrNull()
            if (dictError != null) Log.w(TAG, "refresh：字典强刷失败", dictError)
            if (_state.value.campusId == null) {
                degradeToCache(
                    dictError ?: IllegalStateException("校区字典未加载"),
                )
                return@launch
            }
            runCatching { fetch(reset = true) }
                .onFailure { e -> degradeToCache(e) }
            startPrefetchIfOnline()
        }
    }

    /** 滑到底加载下一页 */
    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || s.page >= s.totalPages) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { fetch(reset = false) }
                .onFailure { e -> degradeToCache(e) }
        }
    }

    fun setCampus(id: Long) {
        if (_state.value.campusId == id) return
        _state.update {
            it.copy(
                campusId = id,
                selectedBuildings = emptySet(),
                buildings = emptyList(),
                snapshotCampusName = null,
                loading = true,
            )
        }
        viewModelScope.launch {
            runCatching {
                val buildings = orderedBuildings(repo.buildings(id))
                _state.update { it.copy(buildings = buildings) }
                fetch(reset = true)
            }.onFailure { e ->
                if (!renderSnapshotForCurrentState()) degradeToCache(e)
            }
        }
    }

    fun setBuildings(ids: Set<Long>) {
        _state.update { it.copy(selectedBuildings = ids, loading = true) }
        viewModelScope.launch {
            runCatching { fetch(reset = true) }
                .onFailure { e ->
                    if (!renderSnapshotForCurrentState()) degradeToCache(e)
                }
        }
    }

    fun setDate(date: LocalDate) {
        _state.update { it.copy(date = date, loading = true, showDatePicker = false) }
        viewModelScope.launch {
            runCatching { fetch(reset = true) }
                .onFailure { e ->
                    if (!renderSnapshotForCurrentState()) degradeToCache(e)
                }
        }
    }

    fun shiftDate(days: Long) = setDate(_state.value.date.plusDays(days))

    /** 节次筛选 */
    fun toggleUnit(indexNo: Int) {
        val current = _state.value.selectedUnits
        val next = if (indexNo in current) current - indexNo else current + indexNo
        applyUnits(next)
    }

    /** 整天空（= 原来的"只看空闲"） */
    fun selectAllUnits() = applyUnits(_state.value.periods.map { it.indexNo }.toSet())

    fun clearUnits() = applyUnits(emptySet())

    private fun applyUnits(units: Set<Int>) {
        val s = _state.value
        _state.update {
            it.copy(
                selectedUnits = units,
                groups = rebuildGroups(allRooms, s.buildings.map { b -> b.id }, units),
            )
        }
    }

    fun openFilter() = _state.update { it.copy(showFilter = true) }

    fun closeFilter() = _state.update { it.copy(showFilter = false) }

    fun openDatePicker() = _state.update { it.copy(showDatePicker = true) }

    fun closeDatePicker() = _state.update { it.copy(showDatePicker = false) }

    /** 长按教室号：看接口原始字段（学校改字段名时靠它秒定位） */
    fun openRaw(room: RoomRow) = _state.update { it.copy(rawRoom = room) }

    fun closeRaw() = _state.update { it.copy(rawRoom = null) }

    /** 当前时间对应的节次序号（-1 = 不在上课时段），用来高亮那一列 */
    fun currentUnitNo(): Int {
        val s = _state.value
        if (s.date != LocalDate.now()) return -1
        val now = java.time.LocalTime.now()
        val hhmm = now.hour * 100 + now.minute
        return s.periods.firstOrNull { hhmm in it.startTime..it.endTime }?.indexNo ?: -1
    }

    private companion object {
        private const val TAG = "RoomViewModel"

        /** 过滤后少于这个数就自动续页 */
        const val MIN_VISIBLE = 15

        /** 预取天数：今天+1 ~ 今天+5（与保留窗口"昨天~今天+5"配套） */
        const val PREFETCH_DAYS = 3
    }
}
