package com.zzupocket.app.ui.campus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.toUserMessage
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.data.repo.ElectricityRepository
import com.zzupocket.app.data.room.RoomCatalogData
import com.zzupocket.app.domain.DayUsage
import com.zzupocket.app.domain.EnergySample
import com.zzupocket.app.domain.EnergyStats
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一行"按日明细" */
data class UsageDayRow(
    val day: LocalDate,
    val usage: DayUsage,
    val cost: Double,
)

data class MeterUi(
    val label: String,
    val roomId: String,
    val kwh: Double?,
    val fetchedAt: Long?,
    val error: String?,
    val daysLeft: Double?,
)

/** 「从学校账户读取房间」的结果，给界面显示一行反馈 */
data class RoomReadUi(val ok: Boolean, val text: String)

data class ElectricityUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false,
    val price: Double = EnergyStats.DEFAULT_PRICE_PER_KWH.toDouble(),
    val light: MeterUi? = null,
    val ac: MeterUi? = null,
    val rangeDays: Int = 30,
    /** 最近 rangeDays 天：日期 → 用量（缺的天为 0） */
    val series: List<Pair<LocalDate, DayUsage>> = emptyList(),
    /** 堆叠柱状图用的柱子（照明 + 空调 + 是否估算） */
    val bars: List<UsageBar> = emptyList(),
    val estimatedDays: Set<LocalDate> = emptySet(),
    /** 余额折线：(时间戳, 度数) */
    val lightBalance: List<Pair<Long, Double>> = emptyList(),
    val acBalance: List<Pair<Long, Double>> = emptyList(),
    val yesterday: DayUsage = DayUsage(),
    val today: DayUsage = DayUsage(),
    val avgDaily: Double = 0.0,
    val sampleCount: Int = 0,
    val lastSampleAt: Long? = null,
    /** 按日明细（新→旧，只保留有用电量的天） */
    val dayRows: List<UsageDayRow> = emptyList(),
    val selectedDay: LocalDate? = null,
    /** 房间的可读名（"柳园20号楼 101"）；为空说明是手动填的 ID 或还没设置 */
    val roomLabel: String = "",
    /** 内置房间表（选楼栋 → 选房号用）；读不出来时为 null */
    val roomCatalog: RoomCatalogData? = null,
    val roomCatalogError: String? = null,
    /** 「从学校账户读取房间」的进行中 / 结果反馈 */
    val roomReading: Boolean = false,
    val roomRead: RoomReadUi? = null,
) {
    val todayCost: Double get() = EnergyStats.cost(today.total, price)

    /** 选中的那一天在区间里的用量（没选中返回 null） */
    val selectedUsage: DayUsage?
        get() = selectedDay?.let { day -> series.firstOrNull { it.first == day }?.second }
}

class ElectricityViewModel : ViewModel() {

    private val repo: ElectricityRepository = AppGraph.electricityRepository
    private val prefsStore = AppGraph.uiPrefsStore

    private val _state = MutableStateFlow(ElectricityUiState())
    val state: StateFlow<ElectricityUiState> = _state.asStateFlow()

    private var lightRoom = ""
    private var acRoom = ""

    init {
        viewModelScope.launch {
            // 房间号没有默认值：用户设置优先，没设置就从本机已有读数反推（老版本升级上来的人不用重填）
            val (light, ac) = repo.effectiveRooms()
            lightRoom = light
            acRoom = ac
            _state.update { it.copy(loggedIn = AppGraph.authRepository.isLoggedIn()) }
            load()
            loadRoomCatalog()
            refresh()
        }
    }

    /** 房间表是 assets 里的静态文件，读一次就够了；读不出来也不影响手动填 ID */
    private fun loadRoomCatalog() {
        viewModelScope.launch {
            runCatching { AppGraph.roomCatalog.load() }
                .onSuccess { catalog -> _state.update { it.copy(roomCatalog = catalog) } }
                .onFailure { e ->
                    _state.update { it.copy(roomCatalogError = e.message ?: "读取失败") }
                }
        }
    }

    fun refresh() {
        if (_state.value.loading) return
        // 还没设置房间：没有可查的表计，直接读库（界面会显示"去设置房间"）
        if (lightRoom.isBlank() && acRoom.isBlank()) {
            load()
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val snapshot = repo.refreshAll(lightRoom, acRoom)
            val error = snapshot.light.error?.toUserMessage()
                ?: snapshot.ac.error?.toUserMessage()
            _state.update { it.copy(loading = false, error = error) }
            load()
        }
    }

    /**
     * 保存宿舍房间。
     *
     * [label] 是选择器给的可读名（"柳园20号楼 101"）；手动填 ID 时传空字符串，
     * 界面就会退回显示两串 ID。房间号进数据库，可读名进偏好存储（不涉及数据库迁移）。
     */
    fun saveRooms(light: String, ac: String, label: String = "") {
        viewModelScope.launch { persistRooms(light, ac, label) }
    }

    /**
     * 从学校账户读取"我绑定的房间"，填进对应的那一路。
     *
     * 用途：内置房间表没有的楼栋（亿达学生公寓、护理学院、洛阳校区……）也能用 ——
     * 不需要用户知道任何内部房间号，学校那边住哪就读到哪。
     *
     * 规则：服务端给 `levelName`（实测"照明房间"/"空调房间"），据此决定填哪一路；
     * **另一路一律不动**，只如实说明 —— 服务端只返回一个绑定房间，
     * 拿它去猜另一个（或留下别的楼的旧值）都会给出错误的表计。
     */
    fun readRoomFromSchool() {
        if (_state.value.roomReading) return
        _state.update { it.copy(roomReading = true, roomRead = null) }
        viewModelScope.launch {
            val result = runCatching { repo.boundRoom() }
            val location = result.getOrNull()
            if (location == null) {
                _state.update {
                    it.copy(
                        roomReading = false,
                        roomRead = RoomReadUi(
                            ok = false,
                            text = "读取失败：${result.exceptionOrNull()?.toUserMessage() ?: "未知错误"}",
                        ),
                    )
                }
                return@launch
            }
            val target = if (location.isAcMeter) "空调" else "照明"
            val light = if (location.isAcMeter) lightRoom else location.room
            val ac = if (location.isAcMeter) location.room else acRoom
            persistRooms(light, ac, location.shortName)
            // 另一路没值就说清楚：服务端只给一个绑定房间，不能替用户猜第二个
            val otherBlank = if (location.isAcMeter) light.isBlank() else ac.isBlank()
            _state.update {
                it.copy(
                    roomReading = false,
                    roomRead = RoomReadUi(
                        ok = true,
                        text = "已读取到你的房间：${location.shortName}（${location.levelName}），" +
                            "已填入「$target」。" +
                            if (otherBlank) {
                                "学校只返回这一个绑定房间，另一路要你自己设 —— " +
                                    "只填这一路也能查（有些楼栋本来就只有一套表）。"
                            } else {
                                ""
                            },
                    ),
                )
            }
        }
    }

    /** 落盘 + 刷新，[saveRooms] 与 [readRoomFromSchool] 共用 */
    private suspend fun persistRooms(light: String, ac: String, label: String) {
        val current = repo.settings()
        repo.saveSettings(
            current.copy(
                lightRoomId = light.trim(),
                acRoomId = ac.trim(),
            ),
        )
        prefsStore.setRoomLabel(label)
        lightRoom = light.trim()
        acRoom = ac.trim()
        _state.update { it.copy(roomLabel = label) }
        load()
        refresh()
    }

    /** 只读库：断网也能看历史 */
    fun load() {
        viewModelScope.launch {
            val settings = repo.settings()
            if (lightRoom.isBlank()) lightRoom = settings.lightRoomId.orEmpty()
            if (acRoom.isBlank()) acRoom = settings.acRoomId.orEmpty()

            val prefs = prefsStore.flow.first()
            _state.update { it.copy(price = prefs.energyPricePerKwh.toDouble()) }

            // 房间显示名：选择器选过就用它；没选过也别给用户看内部代码，
            // 用房表反查出寝室号（柳20-101）来显示
            val roomLabel = prefs.roomLabel?.takeIf { it.isNotBlank() }
                ?: AppGraph.roomCatalog.labelFor(lightRoom, acRoom).orEmpty()

            val since = EnergyStats.dayStart(
                LocalDate.now().minusDays(WINDOW_DAYS.toLong()),
            )
            val light = repo.readingsSince(ElectricityRepository.ACCOUNT_TYPE_LIGHT, lightRoom, since)
            val ac = repo.readingsSince(ElectricityRepository.ACCOUNT_TYPE_AC, acRoom, since)

            val lightSamples = light.map { EnergySample(it.ts, it.kwh) }
            val acSamples = ac.map { EnergySample(it.ts, it.kwh) }
            val daily = EnergyStats.mergeDaily(lightSamples, acSamples)
            val estimated = EnergyStats.estimatedDays(lightSamples) +
                EnergyStats.estimatedDays(acSamples)

            val today = LocalDate.now()
            val avg = EnergyStats.averageDaily(daily)
            val lightKwh = repo.latest(ElectricityRepository.ACCOUNT_TYPE_LIGHT, lightRoom)?.kwh
            val acKwh = repo.latest(ElectricityRepository.ACCOUNT_TYPE_AC, acRoom)?.kwh

            // 「还能用几天」必须按**表计各自**的日均算（照明余额 ÷ 照明日均），
            // 而且窗口由 EnergyStats 统一 —— 这样桌面小组件上的「可用约 N 天」与这里
            // 是同一个数。以前两路都除以上面那个"合计日均"（还是 120 天的），
            // 照明/空调的可用天数一起被低估（真机：15.9 天 vs 28 天）。
            val lightAvgForDaysLeft = EnergyStats.recentDailyAverage(lightSamples)
            val acAvgForDaysLeft = EnergyStats.recentDailyAverage(acSamples)

            _state.update { s ->
                val series = EnergyStats.series(daily, today, s.rangeDays)
                s.copy(
                    price = prefs.energyPricePerKwh.toDouble(),
                    series = series,
                    bars = series.map { (day, usage) ->
                        UsageBar(
                            day = day,
                            light = usage.light,
                            ac = usage.ac,
                            estimated = day in estimated,
                        )
                    },
                    estimatedDays = estimated,
                    lightBalance = lightSamples.map { it.ts to it.kwh },
                    acBalance = acSamples.map { it.ts to it.kwh },
                    yesterday = daily[today.minusDays(1)] ?: DayUsage(),
                    today = daily[today] ?: DayUsage(),
                    avgDaily = avg,
                    sampleCount = lightSamples.size + acSamples.size,
                    lastSampleAt = (lightSamples + acSamples).maxOfOrNull { it.ts },
                    dayRows = daily.entries
                        .filter { it.value.total > 0.0 }
                        .sortedByDescending { it.key }
                        .take(60)
                        .map { (day, usage) ->
                            UsageDayRow(
                                day = day,
                                usage = usage,
                                cost = EnergyStats.cost(usage.total, prefs.energyPricePerKwh.toDouble()),
                            )
                        },
                    light = MeterUi(
                        label = "照明",
                        roomId = lightRoom,
                        kwh = lightKwh,
                        fetchedAt = light.lastOrNull()?.ts,
                        error = null,
                        daysLeft = EnergyStats.daysLeft(lightKwh ?: 0.0, lightAvgForDaysLeft),
                    ),
                    ac = MeterUi(
                        label = "空调",
                        roomId = acRoom,
                        kwh = acKwh,
                        fetchedAt = ac.lastOrNull()?.ts,
                        error = null,
                        daysLeft = EnergyStats.daysLeft(acKwh ?: 0.0, acAvgForDaysLeft),
                    ),
                    roomLabel = roomLabel,
                )
            }
        }
    }

    fun setRange(days: Int) {
        _state.update { it.copy(rangeDays = days) }
        load()
    }

    fun selectDay(day: LocalDate?) = _state.update {
        it.copy(selectedDay = if (it.selectedDay == day) null else day)
    }

    fun setPrice(value: Double) {
        viewModelScope.launch {
            prefsStore.setEnergyPricePerKwh(value.toFloat())
            load()
        }
    }

    private companion object {
        /** 只读最近这么多天的读数，够画 90 天图和日均 */
        const val WINDOW_DAYS = 120
    }
}
