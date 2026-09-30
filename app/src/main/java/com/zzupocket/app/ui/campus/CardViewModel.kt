package com.zzupocket.app.ui.campus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.toUserMessage
import com.zzupocket.app.data.repo.CardRepository
import com.zzupocket.app.domain.CardStats
import com.zzupocket.app.domain.CardSummary
import com.zzupocket.app.domain.CardTotals
import com.zzupocket.app.domain.CardTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 流水筛选 */
enum class CardFilter(val label: String) {
    ALL("全部"),
    SPEND("消费"),
    INCOME("充值"),
}

/** 时间范围快捷选项 */
enum class DatePreset(val label: String) {
    ALL("全部时间"),
    TODAY("今天"),
    WEEK("近 7 天"),
    MONTH("本月"),
    LAST_MONTH("上月"),
    YEAR("本年"),
    ;

    /** 返回 (起, 止)；起止都是闭区间（含当天） */
    fun range(today: java.time.LocalDate): Pair<java.time.LocalDate?, java.time.LocalDate?> = when (this) {
        ALL -> null to null
        TODAY -> today to today
        WEEK -> today.minusDays(6) to today
        MONTH -> today.withDayOfMonth(1) to today
        LAST_MONTH -> {
            val first = today.withDayOfMonth(1).minusMonths(1)
            first to first.withDayOfMonth(first.lengthOfMonth())
        }

        YEAR -> today.withDayOfYear(1) to today
    }
}

/**
 * 筛选条件。
 *
 * 全部在**本地**做（数据已经在 SQLite 里），所以不花流量、离线也能筛。
 * 注意筛选只能在"本地已有的流水"里筛 —— 首次进页面会把能拉到的都拉下来。
 */
data class CardFilterState(
    val preset: DatePreset = DatePreset.ALL,
    /** 自定义起止（设了就压过预设） */
    val customFrom: java.time.LocalDate? = null,
    val customTo: java.time.LocalDate? = null,
    val type: CardFilter = CardFilter.ALL,
    val merchants: Set<String> = emptySet(),
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
    val keyword: String = "",
) {
    val usesCustomRange: Boolean get() = customFrom != null || customTo != null

    val active: Boolean
        get() = preset != DatePreset.ALL || usesCustomRange || type != CardFilter.ALL ||
            merchants.isNotEmpty() || minAmount != null || maxAmount != null || keyword.isNotBlank()

    /** 生效的起止日期（都含当天） */
    fun effectiveRange(today: java.time.LocalDate): Pair<java.time.LocalDate?, java.time.LocalDate?> =
        if (usesCustomRange) customFrom to customTo else preset.range(today)

    fun matches(record: com.zzupocket.app.domain.CardTransaction, today: java.time.LocalDate): Boolean {
        val (from, to) = effectiveRange(today)
        val day = CardStats.dayKey(record.ts)
        if (from != null && day.isBefore(from)) return false
        if (to != null && day.isAfter(to)) return false
        when (type) {
            CardFilter.SPEND -> if (!record.isSpend) return false
            CardFilter.INCOME -> if (!record.isIncome) return false
            CardFilter.ALL -> Unit
        }
        if (merchants.isNotEmpty() && record.merchantName !in merchants) return false
        val abs = kotlin.math.abs(record.amount)
        minAmount?.let { if (abs < it) return false }
        maxAmount?.let { if (abs > it) return false }
        if (keyword.isNotBlank()) {
            val hay = record.merchantName + " " + record.summary
            if (!hay.contains(keyword.trim(), ignoreCase = true)) return false
        }
        return true
    }
}

data class CardUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val balance: Double? = null,
    val balanceAt: Long? = null,
    val summary: CardSummary = CardSummary(),
    val filter: CardFilter = CardFilter.ALL,
    val filterState: CardFilterState = CardFilterState(),
    val records: List<CardTransaction> = emptyList(),
    val loadedCount: Int = 0,
    val earliest: Long? = null,
    val latest: Long? = null,
    val detail: CardTransaction? = null,
    val synced: Boolean = false,
    val showFilterSheet: Boolean = false,
) {
    /** 本地出现过的商户（按笔数降序），给筛选面板用 */
    val merchantOptions: List<String>
        get() = records.groupBy { it.merchantName }
            .filterKeys { it.isNotBlank() }
            .entries
            .sortedByDescending { it.value.size }
            .take(14)
            .map { it.key }
}

class CardViewModel : ViewModel() {

    private val repo: CardRepository = AppGraph.cardRepository

    private val _state = MutableStateFlow(CardUiState())
    val state: StateFlow<CardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            reloadFromDb()
            syncAndRefreshBalance()
        }
    }

    /** 刷新：余额 + 增量流水（首次会全量回溯） */
    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch { syncAndRefreshBalance() }
    }

    private suspend fun syncAndRefreshBalance() {
        _state.update { it.copy(loading = true, error = null) }
        var error: String? = null
        runCatching {
            if (repo.count() == 0) repo.syncAll() else repo.syncRecent()
        }.onFailure { error = it.toUserMessage() }
        runCatching { repo.balance() }
            .onSuccess { value ->
                val at = System.currentTimeMillis()
                _state.update { it.copy(balance = value, balanceAt = at) }
                // 存一份给桌面组件用：组件不能联网，余额又只能联网查，只能读这份缓存
                runCatching { AppGraph.cardBalanceStore.save(value, at) }
            }
            .onFailure { if (error == null) error = it.toUserMessage() }
        reloadFromDb()
        _state.update { it.copy(loading = false, error = error, synced = true) }
    }

    private suspend fun reloadFromDb() {
        val all = repo.all()
        _state.update { s ->
            s.copy(
                summary = CardStats.summarize(all, System.currentTimeMillis()),
                records = all,
                loadedCount = all.size,
                earliest = all.minOfOrNull { it.ts },
                latest = all.maxOfOrNull { it.ts },
            )
        }
    }

    fun setFilter(filter: CardFilter) = _state.update {
        it.copy(filter = filter, filterState = it.filterState.copy(type = filter))
    }

    fun openFilterSheet() = _state.update { it.copy(showFilterSheet = true) }

    fun closeFilterSheet() = _state.update { it.copy(showFilterSheet = false) }

    fun updateFilterState(state: CardFilterState) = _state.update {
        it.copy(filterState = state, filter = state.type)
    }

    fun resetFilter() = _state.update {
        it.copy(filterState = CardFilterState(), filter = CardFilter.ALL)
    }

    fun openDetail(record: CardTransaction) = _state.update { it.copy(detail = record) }

    fun closeDetail() = _state.update { it.copy(detail = null) }

    /** 按当前筛选条件过滤后的流水（新→旧） */
    fun filtered(): List<CardTransaction> {
        val s = _state.value
        val today = java.time.LocalDate.now()
        return s.records.filter { s.filterState.matches(it, today) }
    }

    /** 筛选后的合计（支出/充值分开算） */
    fun filteredTotals(): CardTotals = CardStats.totals(filtered())

    fun clearLocal() {
        viewModelScope.launch {
            repo.clear()
            reloadFromDb()
        }
    }
}
