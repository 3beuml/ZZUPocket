package com.zzupocket.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppError
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.toUserMessage
import com.zzupocket.app.data.auth.AuthRepository
import com.zzupocket.app.data.repo.ElectricityRepository
import com.zzupocket.app.domain.EnergySample
import com.zzupocket.app.domain.EnergyStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 单个表计的界面状态 */
data class MeterUi(
    val kwh: Double? = null,
    val fetchedAt: Long? = null,
    val error: String? = null,
    /** 这一路的房间 ID；空 = 没设置（只有一路有值时，首页就只显示一行「电量」） */
    val roomId: String = "",
)

data class TodayUiState(
    val loggedIn: Boolean = false,
    val light: MeterUi = MeterUi(),
    val ac: MeterUi = MeterUi(),
    val loading: Boolean = false,
    /** 最近 7 天里照明这一路的日均用量（度） */
    val lightAvgDaily: Double = 0.0,
    /** 最近 7 天里空调这一路的日均用量（度） */
    val acAvgDaily: Double = 0.0,
)

private const val AVG_DAYS = 7

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
        // 没有默认房间：用户设置优先，没设置就从本机已有读数反推
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
            )
        }
        loadAverages(lightRoom, acRoom)
    }

    /** 学校没有历史用电量接口，两路日均都由本机采样差分算出来（和电费页同一套口径） */
    private suspend fun loadAverages(lightRoom: String, acRoom: String) {
        val today = LocalDate.now()
        val since = EnergyStats.dayStart(today.minusDays(AVG_DAYS - 1L))
        val lightSamples = if (lightRoom.isBlank()) {
            emptyList()
        } else {
            repo.readingsSince(ElectricityRepository.ACCOUNT_TYPE_LIGHT, lightRoom, since)
                .map { EnergySample(it.ts, it.kwh) }
        }
        val acSamples = if (acRoom.isBlank()) {
            emptyList()
        } else {
            repo.readingsSince(ElectricityRepository.ACCOUNT_TYPE_AC, acRoom, since)
                .map { EnergySample(it.ts, it.kwh) }
        }
        _state.update {
            it.copy(
                lightAvgDaily = EnergyStats.recentDailyAverage(lightSamples, AVG_DAYS, today),
                acAvgDaily = EnergyStats.recentDailyAverage(acSamples, AVG_DAYS, today),
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
            )
        }
        loadAverages(lightRoom, acRoom)
    }

    companion object {
        /** 超过这个时间就自动刷新一次（避免每次切 tab 都打接口） */
        private const val AUTO_REFRESH_AFTER_MS = 5 * 60 * 1000L
    }
}
