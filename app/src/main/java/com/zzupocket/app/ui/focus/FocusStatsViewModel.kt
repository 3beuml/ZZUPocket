package com.zzupocket.app.ui.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.domain.FocusRecord
import com.zzupocket.app.domain.FocusStats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 统计页的数据源。只吐原始记录，具体怎么汇总交给纯函数 [FocusStats] ——
 * 这样统计口径能被单测覆盖，界面里不留业务逻辑。
 */
class FocusStatsViewModel : ViewModel() {

    private val since = FocusStats.dayStart(
        FocusStats.dayKey(System.currentTimeMillis()).minusDays(400),
    )

    val records: StateFlow<List<FocusRecord>> = AppGraph.focusRepository
        .observeRecords(since)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
