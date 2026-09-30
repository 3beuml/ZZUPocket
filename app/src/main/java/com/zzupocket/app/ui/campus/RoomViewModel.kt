package com.zzupocket.app.ui.campus

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
    val showFilter: Boolean = false,
    val showDatePicker: Boolean = false,
    /** 长按教室号弹出的原始字段（排查用） */
    val rawRoom: RoomRow? = null,
    val hiddenVirtual: Int = 0,
) {
    val campusName: String
        get() = campuses.firstOrNull { it.id == campusId }?.nameZh ?: "主校区"

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
        try {
            val periods = scheduleRepo.periods()
            val semester = scheduleRepo.currentSemester()
            val range = if (semester?.startDate != null && semester.endDate != null) {
                semester.startDate to semester.endDate
            } else {
                null
            }
            val campuses = repo.campuses()
            val campus = campuses.firstOrNull { it.nameZh.contains("主校区") } ?: campuses.firstOrNull()
            _state.update {
                it.copy(
                    campuses = campuses,
                    campusId = campus?.id,
                    periods = periods,
                    semesterRange = range,
                )
            }
            val buildings = campus?.let { orderedBuildings(repo.buildings(it.id)) }.orEmpty()
            _state.update { it.copy(buildings = buildings) }
            fetch(reset = true)
        } catch (e: Throwable) {
            _state.update { it.copy(loading = false, error = e.toUserMessage()) }
        }
    }

    private fun orderedBuildings(
        list: List<EasClient.BuildingDto>,
    ): List<EasClient.BuildingDto> = RoomBuildingOrder.sorted(list) { it.nameZh.ifBlank { it.code } }

    /** 拉教室（[reset] = 从第一页开始） */
    private suspend fun fetch(reset: Boolean) {
        val s = _state.value
        if (s.campusId == null) return
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
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                repo.campuses(force = true)
                _state.value.campusId?.let { repo.buildings(it, force = true) }
            }
            fetch(reset = true)
        }
    }

    /** 滑到底加载下一页 */
    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || s.page >= s.totalPages) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { fetch(reset = false) }
                .onFailure { e ->
                    _state.update { it.copy(loadingMore = false, error = e.toUserMessage()) }
                }
        }
    }

    fun setCampus(id: Long) {
        if (_state.value.campusId == id) return
        _state.update {
            it.copy(
                campusId = id,
                selectedBuildings = emptySet(),
                buildings = emptyList(),
                loading = true,
            )
        }
        viewModelScope.launch {
            runCatching {
                val buildings = orderedBuildings(repo.buildings(id))
                _state.update { it.copy(buildings = buildings) }
                fetch(reset = true)
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }

    fun setBuildings(ids: Set<Long>) {
        _state.update { it.copy(selectedBuildings = ids, loading = true) }
        viewModelScope.launch {
            runCatching { fetch(reset = true) }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toUserMessage()) } }
        }
    }

    fun setDate(date: LocalDate) {
        _state.update { it.copy(date = date, loading = true, showDatePicker = false) }
        viewModelScope.launch {
            runCatching { fetch(reset = true) }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toUserMessage()) } }
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
        /** 过滤后少于这个数就自动续页 */
        const val MIN_VISIBLE = 15
    }
}
