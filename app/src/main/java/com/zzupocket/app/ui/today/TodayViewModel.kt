package com.zzupocket.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppError
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.toUserMessage
import com.zzupocket.app.data.auth.AuthRepository
import com.zzupocket.app.data.repo.ElectricityRepository
import com.zzupocket.app.domain.DayUsage
import com.zzupocket.app.domain.EnergySample
import com.zzupocket.app.domain.EnergyStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 单个表计的界面状态 */
data class MeterUi(
    val kwh: Double? = null,
    val fetchedAt: Long? = null,
    val error: String? = null,
    /** 这一路的房间 ID；空 = 没设置（只有一路有值时，今天页就只显示一行「电量」） */
    val roomId: String = "",
)

data class TodayUiState(
    val loggedIn: Boolean = false,
    val light: MeterUi = MeterUi(),
    val ac: MeterUi = MeterUi(),
    val loading: Boolean = false,
    /** 任一表计用的是旧数据（离线 / 令牌失效） */
    val stale: Boolean = false,
    val roomHint: String = "",
    /** 近 7 天每天用了多少度（照明 + 空调分开，用来画堆叠小柱） */
    val trend: List<UsageTrendBar> = emptyList(),
    val trendTotalKwh: Double = 0.0,
    val trendCost: Double = 0.0,
    val trendAvgKwh: Double = 0.0,
    /** 电价（元/度），用来把用电量折算成钱 */
    val price: Double = EnergyStats.DEFAULT_PRICE_PER_KWH.toDouble(),
)

/** 趋势卡里的一根柱子 */
data class UsageTrendBar(
    val label: String,
    val light: Double,
    val ac: Double,
) {
    val total: Double get() = light + ac
}

private const val TREND_DAYS = 7
private val TREND_DAY_FMT = DateTimeFormatter.ofPattern("d", Locale.getDefault())

class TodayViewModel : ViewModel() {

    private val auth: AuthRepository = AppGraph.authRepository
    private val repo: ElectricityRepository = AppGraph.electricityRepository

    private val _state = MutableStateFlow(TodayUiState())
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // 先从库里读 —— 断网也有东西显示
            loadFromDb()
            autoRefreshIfStale()
        }
    }

    private suspend fun rooms(): Pair<String, String> {
        // 没有默认房间：用户设置优先，没设置就从本机已有读数反推（早期版本会把开发者自己的宿舍写死）
        return repo.effectiveRooms()
    }

    private suspend fun loadFromDb() {
        val (lightRoom, acRoom) = rooms()
        val light = repo.latest(ElectricityRepository.ACCOUNT_TYPE_LIGHT, lightRoom)
        val ac = repo.latest(ElectricityRepository.ACCOUNT_TYPE_AC, acRoom)
        _state.update {
            it.copy(
                loggedIn = auth.isLoggedIn(),
                light = MeterUi(light?.kwh, light?.ts, roomId = lightRoom),
                ac = MeterUi(ac?.kwh, ac?.ts, roomId = acRoom),
                roomHint = roomLabel(lightRoom, acRoom),
            )
        }
        loadTrend(lightRoom, acRoom)
    }

    /**
     * 近 7 天用电趋势。
     *
     * 学校不提供历史用电量接口，这里全用**本机采样**算出来（和电费页同一套口径），
     * 所以断网也有东西看；采样越频繁越准。
     */
    private suspend fun loadTrend(lightRoom: String, acRoom: String) {
        val prefs = AppGraph.uiPrefsStore.flow.first()
        val price = prefs.energyPricePerKwh.toDouble()
        if (lightRoom.isBlank() && acRoom.isBlank()) {
            _state.update { it.copy(trend = emptyList(), price = price) }
            return
        }
        val today = LocalDate.now()
        val since = EnergyStats.dayStart(today.minusDays(TREND_DAYS - 1L))
        val lightSamples = repo.readingsSince(ElectricityRepository.ACCOUNT_TYPE_LIGHT, lightRoom, since)
            .map { EnergySample(it.ts, it.kwh) }
        val acSamples = repo.readingsSince(ElectricityRepository.ACCOUNT_TYPE_AC, acRoom, since)
            .map { EnergySample(it.ts, it.kwh) }
        val daily = EnergyStats.mergeDaily(lightSamples, acSamples)
        val bars = (TREND_DAYS - 1 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            val usage = daily[day] ?: DayUsage()
            UsageTrendBar(label = TREND_DAY_FMT.format(day), light = usage.light, ac = usage.ac)
        }
        val total = bars.sumOf { it.total }
        _state.update {
            it.copy(
                price = price,
                trend = bars,
                trendTotalKwh = total,
                trendCost = EnergyStats.cost(total, price),
                trendAvgKwh = EnergyStats.averageDaily(daily),
            )
        }
    }

    private suspend fun autoRefreshIfStale() {
        if (!auth.isLoggedIn()) return
        val s = _state.value
        val newest = listOfNotNull(s.light.fetchedAt, s.ac.fetchedAt).maxOrNull()
        val age = newest?.let { System.currentTimeMillis() - it }
        if (age == null || age > AUTO_REFRESH_AFTER_MS) {
            doRefresh()
        }
    }

    fun refresh() {
        viewModelScope.launch { doRefresh() }
    }

    /** 从登录页返回、或从后台切回来时调用 */
    fun onResume() {
        viewModelScope.launch {
            _state.update { it.copy(loggedIn = auth.isLoggedIn()) }
            autoRefreshIfStale()
        }
    }

    private suspend fun doRefresh() {
        _state.update { it.copy(loading = true) }
        val (lightRoom, acRoom) = rooms()
        val snap = repo.refreshAll(lightRoom, acRoom)

        val needRelogin = listOfNotNull(snap.light.error, snap.ac.error)
            .any { it is AppError.ReLoginRequired }

        _state.update {
            it.copy(
                loading = false,
                loggedIn = auth.isLoggedIn() && !needRelogin,
                light = MeterUi(
                    snap.light.kwh,
                    snap.light.fetchedAt,
                    snap.light.error?.toUserMessage(),
                    roomId = lightRoom,
                ),
                ac = MeterUi(
                    snap.ac.kwh,
                    snap.ac.fetchedAt,
                    snap.ac.error?.toUserMessage(),
                    roomId = acRoom,
                ),
                stale = snap.light.error != null || snap.ac.error != null,
                roomHint = roomLabel(lightRoom, acRoom),
            )
        }
        // 新读数落库了，趋势跟着重算
        loadTrend(lightRoom, acRoom)
    }

    /**
     * 房间的显示文案（今日页只有一行位置，所以这里是**一个**名字）：
     * 优先用照明那一路的可读名（空调那路若不同楼，电费页会明确标出来），
     * 其次是两串内部 ID，都没设置就什么都不显示（去电费页设置）。
     */
    private suspend fun roomLabel(lightRoom: String, acRoom: String): String {
        if (lightRoom.isBlank() && acRoom.isBlank()) return ""
        val prefs = AppGraph.uiPrefsStore.flow.first()
        val picked = (prefs.lightRoomLabel ?: prefs.acRoomLabel).orEmpty()
        if (picked.isNotBlank()) return picked
        // 没选过房间也不给用户看内部代码：用房表反查出寝室号（柳20-101）
        return AppGraph.roomCatalog.labelForRoom(lightRoom.ifBlank { acRoom })
            ?: "照明 ${lightRoom.ifBlank { "未设置" }} / 空调 ${acRoom.ifBlank { "未设置" }}"
    }

    companion object {
        /** 超过这个时间就自动刷新一次（避免每次切 tab 都打接口） */
        private const val AUTO_REFRESH_AFTER_MS = 5 * 60 * 1000L
    }
}
