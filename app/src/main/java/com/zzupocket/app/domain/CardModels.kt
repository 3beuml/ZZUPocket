package com.zzupocket.app.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * 校园卡的一笔流水。
 *
 * [amount] 单位是**元、带符号**：消费为负、充值为正（服务端就是这么给的）。
 * 注意别和 `/bllt/queryConsumeTotalDay` 混了 —— 那个接口的单位是**分**。
 */
data class CardTransaction(
    /** 流水号：天然主键，重复拉取不会重复计 */
    val journo: String,
    val summary: String,
    val merchantName: String,
    /** 服务端原始时间文本 "2026-09-19 17:45:17" */
    val dateText: String,
    /** 解析出来的本地时间戳；解析失败为 0 */
    val ts: Long,
    val amount: Double,
    val isRefund: Boolean,
    val tradeType: String,
) {
    val isSpend: Boolean get() = amount < 0
    val isIncome: Boolean get() = amount > 0
}

/** 支出 / 充值 / 净额（元）。三者必须分开算 —— 混在一起充值会把消费冲淡 */
data class CardTotals(
    val spend: Double = 0.0,
    val income: Double = 0.0,
    val count: Int = 0,
) {
    val net: Double get() = income - spend

    operator fun plus(other: CardTotals) = CardTotals(
        spend = spend + other.spend,
        income = income + other.income,
        count = count + other.count,
    )

    companion object {
        val ZERO = CardTotals()
    }
}

/** 某个商户/场景的合计 */
data class MerchantTotal(val name: String, val totals: CardTotals)

data class CardSummary(
    val today: CardTotals = CardTotals.ZERO,
    val week: CardTotals = CardTotals.ZERO,
    val month: CardTotals = CardTotals.ZERO,
    val year: CardTotals = CardTotals.ZERO,
    val allTime: CardTotals = CardTotals.ZERO,
    /** **消费去向**：只含有支出的商户（按支出降序） */
    val byMerchant: List<MerchantTotal> = emptyList(),
    /** **充值来源**：只有收入的渠道（微信/支付宝/银行转账…） */
    val incomeSources: List<MerchantTotal> = emptyList(),
)

/**
 * 消费统计。纯函数，方便单测。
 *
 * 口径刻意用**自然日**（0 点分界），不是番茄钟那套"凌晨 4 点算前一天" ——
 * 花钱是按账单日理解的，食堂夜宵记在当天更符合直觉。
 */
object CardStats {

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun zone(): ZoneId = ZoneId.systemDefault()

    /** "2026-09-19 17:45:17" → 本地时间戳；解析失败返回 0 */
    fun parseTs(text: String, zone: ZoneId = zone()): Long = try {
        LocalDateTime.parse(text.trim(), DATE_FMT).atZone(zone).toInstant().toEpochMilli()
    } catch (_: Exception) {
        0L
    }

    fun dayKey(ts: Long, zone: ZoneId = zone()): LocalDate =
        java.time.Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()

    fun dayStart(day: LocalDate, zone: ZoneId = zone()): Long =
        day.atStartOfDay(zone).toInstant().toEpochMilli()

    fun dayEnd(day: LocalDate, zone: ZoneId = zone()): Long = dayStart(day.plusDays(1), zone)

    fun weekStart(day: LocalDate): LocalDate =
        day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun monthStart(day: LocalDate): LocalDate = day.withDayOfMonth(1)

    fun yearStart(day: LocalDate): LocalDate = day.withDayOfYear(1)

    fun inRange(records: List<CardTransaction>, fromMs: Long, toMs: Long): List<CardTransaction> =
        records.filter { it.ts >= fromMs && it.ts < toMs }

    /** 支出取绝对值累加，充值单独累加 */
    fun totals(records: List<CardTransaction>): CardTotals {
        var spend = 0.0
        var income = 0.0
        records.forEach { r ->
            if (r.amount < 0) spend += -r.amount else if (r.amount > 0) income += r.amount
        }
        return CardTotals(spend = spend, income = income, count = records.size)
    }

    /**
     * **消费去向**：按商户聚合支出，降序。
     *
     * ⚠️ 只统计**有支出**的商户。充值渠道（微信支付、支付宝）金额是正的，
     * 混进来会显示成"¥ 0.00 · 3 笔"，看着像 bug —— 它们归到 [incomeSources]。
     */
    fun merchantSpend(records: List<CardTransaction>, limit: Int = 8): List<MerchantTotal> =
        records.groupBy { it.merchantName.ifBlank { UNKNOWN_MERCHANT } }
            .map { (name, group) -> MerchantTotal(name, totals(group)) }
            .filter { it.totals.spend > 0.0 }
            .sortedWith(
                compareByDescending<MerchantTotal> { it.totals.spend }
                    .thenByDescending { it.totals.count },
            )
            .take(limit)

    /** **充值来源**：按收入聚合，降序 */
    fun incomeSources(records: List<CardTransaction>, limit: Int = 6): List<MerchantTotal> =
        records.groupBy { it.merchantName.ifBlank { UNKNOWN_MERCHANT } }
            .map { (name, group) -> MerchantTotal(name, totals(group)) }
            .filter { it.totals.income > 0.0 }
            .sortedWith(
                compareByDescending<MerchantTotal> { it.totals.income }
                    .thenByDescending { it.totals.count },
            )
            .take(limit)

    /** 兼容旧用法：默认按支出聚合 */
    fun byMerchant(records: List<CardTransaction>, limit: Int = 8): List<MerchantTotal> =
        merchantSpend(records, limit)

    const val UNKNOWN_MERCHANT = "未知商户"

    /** 每天的支出（自然日） */
    fun dailySpend(records: List<CardTransaction>, zone: ZoneId = zone()): Map<LocalDate, Double> {
        val out = HashMap<LocalDate, Double>()
        records.filter { it.amount < 0 }.forEach { r ->
            val day = dayKey(r.ts, zone)
            out[day] = (out[day] ?: 0.0) + (-r.amount)
        }
        return out
    }

    fun summarize(records: List<CardTransaction>, now: Long, zone: ZoneId = zone()): CardSummary {
        val today = dayKey(now, zone)
        return CardSummary(
            today = totals(inRange(records, dayStart(today, zone), dayEnd(today, zone))),
            week = totals(inRange(records, dayStart(weekStart(today), zone), dayEnd(today, zone))),
            month = totals(inRange(records, dayStart(monthStart(today), zone), dayEnd(today, zone))),
            year = totals(inRange(records, dayStart(yearStart(today), zone), dayEnd(today, zone))),
            allTime = totals(records),
            byMerchant = merchantSpend(records),
            incomeSources = incomeSources(records),
        )
    }

    /** "12.34" → 12.34 元；服务端给的是元，不是分 */
    fun money(yuan: Double): String = "%.2f".format(yuan)

    /** 带符号的金额文本，用于流水列表 */
    fun signedMoney(yuan: Double): String = when {
        yuan < 0 -> "-%.2f".format(-yuan)
        else -> "+%.2f".format(yuan)
    }
}
