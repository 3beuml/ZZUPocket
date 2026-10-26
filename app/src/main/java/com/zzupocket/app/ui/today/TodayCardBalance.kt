package com.zzupocket.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.Card
import com.zzupocket.app.ui.components.GlassCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.auth.AuthRepository
import com.zzupocket.app.data.repo.CardRepository
import com.zzupocket.app.domain.CardStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** 「今天」页的校园卡状态 */
data class TodayCardUi(
    val loggedIn: Boolean = false,
    /** 余额（元）；拿不到就是 null */
    val balance: Double? = null,
    val todaySpend: Double = 0.0,
    val todayIncome: Double = 0.0,
    val todayCount: Int = 0,
    /** "食堂 · -12.50" 这类最近一笔 */
    val lastTrade: String? = null,
    val loading: Boolean = false,
    val note: String? = null,
)

/**
 * 「今天」页的校园卡卡片：余额 + 今日消费。
 *
 * 明细来自本地库（离线也有），余额要联网查，所以**带 5 分钟节流**，
 * 避免每次切回「今天」都去打一次接口。
 */
class TodayCardViewModel : ViewModel() {

    private val repo: CardRepository = AppGraph.cardRepository
    private val auth: AuthRepository = AppGraph.authRepository

    private var cachedBalance: Double? = null
    private var lastBalanceAt = 0L

    private val _state = MutableStateFlow(TodayCardUi())
    val state: StateFlow<TodayCardUi> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun reload() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val now = System.currentTimeMillis()
        val loggedIn = auth.isLoggedIn()
        val today = CardStats.dayKey(now)

        val transactions = runCatching { repo.all() }.getOrDefault(emptyList())
        val todayTx = transactions.filter { CardStats.dayKey(it.ts) == today }
        val spend = todayTx.filter { it.isSpend }.sumOf { -it.amount }
        val income = todayTx.filter { it.isIncome }.sumOf { it.amount }
        val last = todayTx.maxByOrNull { it.ts }

        var loading = false
        var note: String? = null
        // 冷启动时先把上次存下来的余额显示出来（离线也看得见），再去查新的
        val stored = runCatching { AppGraph.cardBalanceStore.latest() }.getOrNull()
        if (cachedBalance == null && stored != null) {
            cachedBalance = stored.yuan
            lastBalanceAt = stored.at
        }
        if (loggedIn && now - lastBalanceAt > BALANCE_TTL_MS) {
            loading = true
            _state.value = _state.value.copy(loading = true)
            runCatching { repo.balance() }
                .onSuccess {
                    val at = System.currentTimeMillis()
                    cachedBalance = it
                    lastBalanceAt = at
                    // 存一份给桌面组件用（组件不能联网，只能读这份缓存）
                    runCatching { AppGraph.cardBalanceStore.save(it, at) }
                }
                .onFailure { note = "余额暂时读不到，稍后自动重试" }
        } else if (!loggedIn) {
            note = "登录后显示余额"
        }

        _state.value = TodayCardUi(
            loggedIn = loggedIn,
            balance = cachedBalance,
            todaySpend = spend,
            todayIncome = income,
            todayCount = todayTx.size,
            lastTrade = last?.let { tx ->
                val who = tx.merchantName.ifBlank { tx.summary }.ifBlank { "一笔" }
                "$who ${formatMoney(tx.amount)}"
            },
            loading = loading,
            note = note,
        )
    }

    private companion object {
        /** 余额查询节流：这期间只用缓存值 */
        const val BALANCE_TTL_MS = 5 * 60 * 1000L
    }
}

@Composable
fun TodayCardBalanceCard(
    state: TodayCardUi,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CreditCard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("校园卡", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else if (state.balance != null) {
                    Text(
                        text = "¥ ${formatMoney(state.balance)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                } else {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = buildString {
                        append("今日消费 ¥ ${formatMoney(state.todaySpend)}")
                        if (state.todayCount > 0) append(" · ${state.todayCount} 笔")
                        if (state.todayIncome > 0) {
                            append(" · 入账 ¥ ${formatMoney(state.todayIncome)}")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            state.lastTrade?.let {
                Text(
                    text = "最近：$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 12.5 → "12.50"；-3 → "-3.00" */
private fun formatMoney(v: Double): String = String.format(Locale.US, "%.2f", v)
