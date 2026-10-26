package com.zzupocket.app.ui.campus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.zzupocket.app.ui.components.GlassCard
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.domain.CardStats
import com.zzupocket.app.domain.CardTotals
import com.zzupocket.app.domain.CardTransaction
import com.zzupocket.app.ui.components.RelativeTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TX_FMT = DateTimeFormatter.ofPattern("M月d日 HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardScreen(
    onBack: () -> Unit,
    vm: CardViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val rows = vm.filtered()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("校园卡") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        if (state.loading) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "校园卡余额",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = state.balance?.let { "¥ %.2f".format(it) } ?: "—",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        state.balanceAt?.let {
                            Text(
                                text = "更新于 ${RelativeTime.ago(it)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            state.error?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            item { PeriodCard("今天", state.summary.today) }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "周 · 月 · 年",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        PeriodRow("本周", state.summary.week)
                        PeriodRow("本月", state.summary.month)
                        PeriodRow("本年", state.summary.year)
                        PeriodRow("累计", state.summary.allTime)
                    }
                }
            }

            if (state.summary.byMerchant.isNotEmpty()) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "钱花在哪了",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            val maxSpend = state.summary.byMerchant.maxOf { it.totals.spend }
                                .coerceAtLeast(0.01)
                            state.summary.byMerchant.forEach { merchant ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = merchant.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = "¥ %.2f · %d 笔".format(
                                                merchant.totals.spend,
                                                merchant.totals.count,
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    ShareBar((merchant.totals.spend / maxSpend).toFloat())
                                }
                            }
                        }
                    }
                }
            }

            // 充值来源单独一张卡：它们的金额是正的，混在"钱花在哪了"里会显示成 0.00
            if (state.summary.incomeSources.isNotEmpty()) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "充值来源",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            state.summary.incomeSources.forEach { source ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = source.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "+¥ %.2f · %d 笔".format(
                                            source.totals.income,
                                            source.totals.count,
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "流水",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.filterState.active) {
                        Text(
                            text = "已筛选 ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    OutlinedButton(onClick = vm::openFilterSheet) { Text("筛选") }
                }
            }

            item {
                val totals = vm.filteredTotals()
                Text(
                    text = "当前条件：${rows.size} 笔 · 支出 ¥%.2f · 充值 ¥%.2f".format(
                        totals.spend,
                        totals.income,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Text(
                    text = when {
                        !state.synced -> "正在同步…"
                        state.loadedCount == 0 -> "本地还没有流水记录"
                        else -> "本地共 ${state.loadedCount} 笔" +
                            (state.earliest?.let {
                                "，最早到 ${CardStats.dayKey(it)}（服务端能给到的最早）"
                            } ?: "")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (rows.isEmpty() && state.synced && state.loadedCount > 0) {
                item {
                    Text(
                        text = "当前筛选下没有记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(rows.take(200), key = { it.journo }) { record ->
                TransactionRow(record) { vm.openDetail(record) }
                HorizontalDivider()
            }

            if (rows.size > 200) {
                item {
                    Text(
                        text = "只显示最近 200 笔（本地共 ${rows.size} 笔）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    state.detail?.let { record ->
        ModalBottomSheet(onDismissRequest = vm::closeDetail) {
            TransactionDetail(record)
        }
    }

    if (state.showFilterSheet) {
        FilterSheet(
            state = state.filterState,
            merchantOptions = state.merchantOptions,
            hitCount = rows.size,
            hitTotals = vm.filteredTotals(),
            onChange = vm::updateFilterState,
            onReset = vm::resetFilter,
            onDismiss = vm::closeFilterSheet,
        )
    }
}

/** 筛选面板：时间范围 / 类型 / 商户 / 金额 / 关键词，全部本地筛选 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    state: CardFilterState,
    merchantOptions: List<String>,
    hitCount: Int,
    hitTotals: CardTotals,
    onChange: (CardFilterState) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var customFrom by remember(state.customFrom) {
        mutableStateOf(state.customFrom?.toString() ?: "")
    }
    var customTo by remember(state.customTo) { mutableStateOf(state.customTo?.toString() ?: "") }
    var minText by remember(state.minAmount) {
        mutableStateOf(state.minAmount?.let { "%.2f".format(it) } ?: "")
    }
    var maxText by remember(state.maxAmount) {
        mutableStateOf(state.maxAmount?.let { "%.2f".format(it) } ?: "")
    }
    var keyword by remember(state.keyword) { mutableStateOf(state.keyword) }

    fun apply() {
        onChange(
            state.copy(
                customFrom = customFrom.takeIf { it.length == 10 }
                    ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() },
                customTo = customTo.takeIf { it.length == 10 }
                    ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() },
                minAmount = minText.toDoubleOrNull(),
                maxAmount = maxText.toDoubleOrNull(),
                keyword = keyword,
            ),
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("筛选流水", style = MaterialTheme.typography.titleLarge)

            Text("时间范围", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DatePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = !state.usesCustomRange && state.preset == preset,
                        onClick = {
                            customFrom = ""
                            customTo = ""
                            onChange(state.copy(preset = preset, customFrom = null, customTo = null))
                        },
                        label = { Text(preset.label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = customFrom,
                    onValueChange = { customFrom = it.take(10) },
                    label = { Text("起始 yyyy-MM-dd") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = customTo,
                    onValueChange = { customTo = it.take(10) },
                    label = { Text("结束 yyyy-MM-dd") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Text("类型", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.type == filter,
                        onClick = { onChange(state.copy(type = filter)) },
                        label = { Text(filter.label) },
                    )
                }
            }

            if (merchantOptions.isNotEmpty()) {
                Text("商户（可多选）", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    merchantOptions.forEach { merchant ->
                        FilterChip(
                            selected = merchant in state.merchants,
                            onClick = {
                                val next = state.merchants.toMutableSet()
                                if (!next.add(merchant)) next.remove(merchant)
                                onChange(state.copy(merchants = next))
                            },
                            label = {
                                Text(
                                    text = if (merchant.length > 8) {
                                        merchant.take(8) + "…"
                                    } else {
                                        merchant
                                    },
                                )
                            },
                        )
                    }
                }
            }

            Text("单笔金额（元，绝对值）", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = minText,
                    onValueChange = { minText = it.filter { c -> c.isDigit() || c == '.' }.take(8) },
                    label = { Text("≥") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = maxText,
                    onValueChange = { maxText = it.filter { c -> c.isDigit() || c == '.' }.take(8) },
                    label = { Text("≤") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it.take(20) },
                label = { Text("关键词（商户/摘要）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "命中 $hitCount 笔 · 支出 ¥%.2f · 充值 ¥%.2f".format(
                    hitTotals.spend,
                    hitTotals.income,
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { apply(); onDismiss() }, modifier = Modifier.weight(1f)) {
                    Text("应用")
                }
                OutlinedButton(onClick = { onReset(); onDismiss() }) { Text("重置") }
            }

            Text(
                text = "筛选只在**本地已有的流水**里进行；首次进页面会把服务端能给的都拉下来，" +
                    "所以最早期的一批记录受服务端范围限制。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PeriodCard(title: String, totals: CardTotals) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth()) {
                BigStat("支出", "¥ %.2f".format(totals.spend), "${totals.count} 笔", Modifier.weight(1f))
                BigStat("充值", "¥ %.2f".format(totals.income), "—", Modifier.weight(1f))
                BigStat("净支出", "¥ %.2f".format(totals.spend - totals.income), "—", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PeriodRow(label: String, totals: CardTotals) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp))
        Text(
            text = "¥ %.2f".format(totals.spend),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (totals.income > 0) "充值 ¥ %.2f".format(totals.income) else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BigStat(label: String, value: String, hint: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.titleMedium)
        if (hint != "—") {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransactionRow(record: CardTransaction, onClick: () -> Unit) {
    val spendColor = MaterialTheme.colorScheme.error
    val incomeColor = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = record.merchantName.ifBlank { record.summary },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = record.dateText + if (record.isRefund) " · 退款" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = CardStats.signedMoney(record.amount),
            style = MaterialTheme.typography.titleMedium,
            color = if (record.isSpend) spendColor else incomeColor,
        )
    }
}

@Composable
private fun TransactionDetail(record: CardTransaction) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = CardStats.signedMoney(record.amount) + " 元",
            style = MaterialTheme.typography.headlineSmall,
            color = if (record.isSpend) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        DetailLine("商户", record.merchantName.ifBlank { "—" })
        DetailLine("摘要", record.summary.ifBlank { "—" })
        DetailLine("时间", record.dateText)
        DetailLine("流水号", record.journo)
        DetailLine("类型", if (record.isSpend) "消费" else if (record.isRefund) "退款" else "充值/补助")
        if (record.isRefund) DetailLine("标记", "这是一笔退款")
        Text(
            text = "流水号是这笔记录的唯一标识，重复刷新不会重复计入统计。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
