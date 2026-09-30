package com.zzupocket.app.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EnergyStatsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 3): Long =
        LocalDateTime.of(2025, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun s(ts: Long, kwh: Double) = EnergySample(ts, kwh)

    @Test
    fun `余额下降算作用电`() {
        assertEquals(1.5, EnergyStats.usageBetween(s(at(1, 8), 100.0), s(at(1, 12), 98.5)), 0.0001)
    }

    @Test
    fun `余额上升是充值 不算作用电`() {
        // 充值 50 度：这条差值必须被剔除，否则会算出负的用电量
        assertEquals(0.0, EnergyStats.usageBetween(s(at(1, 8), 10.0), s(at(1, 12), 60.0)), 0.0001)
    }

    @Test
    fun `充值不会把当天的消耗抹平`() {
        // 上午用了 3 度 → 中午充值 50 度 → 晚上又用了 2 度
        val samples = listOf(
            s(at(2, 7), 60.0),
            s(at(2, 11), 57.0),   // 用了 3
            s(at(2, 12), 107.0),  // 充值 +50（不计）
            s(at(2, 22), 105.0),  // 又用了 2
        )
        val daily = EnergyStats.daily(samples, zone)
        assertEquals(5.0, daily[LocalDate.of(2025, 3, 2)] ?: 0.0, 0.0001)
    }

    @Test
    fun `差值归到后一次读数所在的那一天`() {
        val samples = listOf(s(at(1, 23), 50.0), s(at(2, 1), 49.0))
        val daily = EnergyStats.daily(samples, zone)
        assertEquals(null, daily[LocalDate.of(2025, 3, 1)])
        assertEquals(1.0, daily[LocalDate.of(2025, 3, 2)] ?: 0.0, 0.0001)
    }

    @Test
    fun `跨天采样被标成估算`() {
        val samples = listOf(s(at(1, 23), 50.0), s(at(2, 1), 49.0))
        val estimated = EnergyStats.estimatedDays(samples, zone)
        assertEquals(setOf(LocalDate.of(2025, 3, 2)), estimated)
    }

    @Test
    fun `间隔过长被标成估算`() {
        val samples = listOf(s(at(2, 8), 50.0), s(at(2, 20), 48.0)) // 12 小时
        assertEquals(setOf(LocalDate.of(2025, 3, 2)), EnergyStats.estimatedDays(samples, zone))
        // 4 小时间隔不算估算
        val dense = listOf(s(at(2, 8), 50.0), s(at(2, 12), 48.0))
        assertEquals(emptySet<LocalDate>(), EnergyStats.estimatedDays(dense, zone))
    }

    @Test
    fun `双表合并成每天一个数`() {
        val light = listOf(s(at(2, 8), 30.0), s(at(2, 20), 29.0))
        val ac = listOf(s(at(2, 8), 60.0), s(at(2, 20), 54.0))
        val merged = EnergyStats.mergeDaily(light, ac, zone)
        val day = merged[LocalDate.of(2025, 3, 2)]!!
        assertEquals(1.0, day.light, 0.0001)
        assertEquals(6.0, day.ac, 0.0001)
        assertEquals(7.0, day.total, 0.0001)
    }

    @Test
    fun `序列按天补齐 缺的天为 0`() {
        val daily = mapOf(LocalDate.of(2025, 3, 2) to DayUsage(light = 1.0, ac = 2.0))
        val series = EnergyStats.series(daily, LocalDate.of(2025, 3, 4), days = 3)
        assertEquals(3, series.size)
        assertEquals(LocalDate.of(2025, 3, 2), series[0].first)
        assertEquals(3.0, series[0].second.total, 0.0001)
        assertEquals(0.0, series[1].second.total, 0.0001)
        assertEquals(0.0, series[2].second.total, 0.0001)
    }

    @Test
    fun `日均只算有数据的天`() {
        val daily = mapOf(
            LocalDate.of(2025, 3, 1) to DayUsage(light = 2.0, ac = 2.0),
            LocalDate.of(2025, 3, 2) to DayUsage(light = 3.0, ac = 3.0),
            LocalDate.of(2025, 3, 3) to DayUsage(), // 没数据，不参与平均
        )
        assertEquals(5.0, EnergyStats.averageDaily(daily), 0.0001)
    }

    @Test
    fun `剩余可用天数`() {
        assertEquals(10.0, EnergyStats.daysLeft(50.0, 5.0)!!, 0.0001)
        assertNull(EnergyStats.daysLeft(50.0, 0.0))
        assertNotNull(EnergyStats.daysLeft(0.0, 5.0))
    }

    // ── 「还能用几天」的统一口径 ────────────────────────────────────────────
    // 这一组钉的是真机上看到的问题：同一时刻、同一个余额，
    // App 电费页说「15.9 天」、桌面小组件说「可用约 28 天」。
    // 根因是两处各写了一份口径：电费页拿"照明+空调合计日均"去除每一路，
    // 还用了 120 天的窗口，而组件按表计各自算近 7 天。

    @Test
    fun `按表计各自算日均_不拿合计日均去除单表余额`() {
        val today = LocalDate.of(2025, 3, 7)
        // 照明：每天 2 度；空调：每天 4 度（都在这 7 天窗口里均匀用）
        val light = (1..7).map { s(at(it, 8), 100.0 - 2.0 * it) }
        val ac = (1..7).map { s(at(it, 8), 200.0 - 4.0 * it) }

        assertEquals(2.0, EnergyStats.recentDailyAverage(light, today = today), 0.0001)
        assertEquals(4.0, EnergyStats.recentDailyAverage(ac, today = today), 0.0001)

        // 照明剩 100 度、空调剩 100 度：各自的天数必须差一倍
        assertEquals(50.0, EnergyStats.daysLeftFor(100.0, light, today = today)!!, 0.0001)
        assertEquals(25.0, EnergyStats.daysLeftFor(100.0, ac, today = today)!!, 0.0001)

        // 曾经的错误算法（合计日均 6 度去除照明）会算出 16.7 天 —— 明确钉住它不该出现
        val merged = EnergyStats.averageDaily(EnergyStats.mergeDaily(light, ac))
        assertEquals(6.0, merged, 0.0001)
        assertNotEquals(
            EnergyStats.daysLeft(100.0, merged)!!,
            EnergyStats.daysLeftFor(100.0, light, today = today)!!,
        )
    }

    @Test
    fun `窗口外的老样本不算进日均`() {
        val today = LocalDate.of(2025, 3, 20)
        val samples = listOf(
            // 8 天前用过 100 度（在 7 天窗口之外，必须被丢掉）
            s(at(12, 8), 300.0), s(at(12, 20), 200.0),
            // 窗口内每天 1 度
            s(at(19, 8), 10.0), s(at(19, 20), 9.0),
            s(at(20, 8), 9.0), s(at(20, 20), 8.0),
        )
        // 窗口内只有 2 天有用量：1 + 1 = 2，日均 1.0（不是把 100 度摊进来）
        assertEquals(1.0, EnergyStats.recentDailyAverage(samples, today = today), 0.0001)
    }

    @Test
    fun `窗口内一次都没用电时没有日均`() {
        val today = LocalDate.of(2025, 3, 20)
        // 两次采样余额一样 = 没用电
        val flat = listOf(s(at(19, 8), 50.0), s(at(20, 8), 50.0))
        assertEquals(0.0, EnergyStats.recentDailyAverage(flat, today = today), 0.0001)
        assertNull(EnergyStats.daysLeftFor(50.0, flat, today = today))
    }

    @Test
    fun `电费页与桌面组件用的是同一个窗口常量`() {
        // 窗口只有一份（EnergyStats.DAYS_LEFT_WINDOW_DAYS）：两边各写一份正是数字对不上的来源
        assertEquals(7, EnergyStats.DAYS_LEFT_WINDOW_DAYS)
        val today = LocalDate.of(2025, 3, 10)
        val samples = (1..10).map { s(at(it, 8), 100.0 - it) }
        assertEquals(
            EnergyStats.recentDailyAverage(samples, days = EnergyStats.DAYS_LEFT_WINDOW_DAYS, today = today),
            EnergyStats.recentDailyAverage(samples, today = today),
            0.0001,
        )
    }

    @Test
    fun `度数换算成电费`() {
        assertEquals(5.68, EnergyStats.cost(10.0, 0.568), 0.0001)
    }
}

class CardStatsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun tx(
        amount: Double,
        dateText: String,
        merchant: String = "松园男浴室",
        journo: String = dateText + amount,
    ) = CardTransaction(
        journo = journo,
        summary = if (amount < 0) "水控POS消费流水" else "移动端交易",
        merchantName = merchant,
        dateText = dateText,
        ts = CardStats.parseTs(dateText, zone),
        amount = amount,
        isRefund = false,
        tradeType = if (amount < 0) "1" else "2",
    )

    @Test
    fun `时间文本解析`() {
        val ts = CardStats.parseTs("2026-09-19 17:45:17", zone)
        assertEquals(LocalDate.of(2026, 9, 19), CardStats.dayKey(ts, zone))
        assertEquals(0L, CardStats.parseTs("坏掉的时间", zone))
    }

    @Test
    fun `支出与充值分开累加`() {
        val records = listOf(
            tx(-0.93, "2026-09-19 17:45:17"),
            tx(-12.50, "2026-09-19 12:00:00", merchant = "荷园餐厅"),
            tx(20.00, "2026-09-05 22:02:28", merchant = "微信支付转账充值"),
        )
        val totals = CardStats.totals(records)
        assertEquals(13.43, totals.spend, 0.0001)
        assertEquals(20.00, totals.income, 0.0001)
        assertEquals(3, totals.count)
        assertEquals(6.57, totals.net, 0.0001)
    }

    @Test
    fun `按自然日划分 不是凌晨四点`() {
        val now = CardStats.parseTs("2026-09-20 10:00:00", zone)
        val records = listOf(
            tx(-5.0, "2026-09-20 01:30:00"),  // 凌晨 1 点半 → 仍算 9/20 当天
            tx(-3.0, "2026-09-19 23:50:00"),  // 前一天
        )
        val summary = CardStats.summarize(records, now, zone)
        assertEquals(5.0, summary.today.spend, 0.0001)
        assertEquals(8.0, summary.allTime.spend, 0.0001)
    }

    @Test
    fun `周汇总从周一开始`() {
        // 2026-09-14 是周一
        val now = CardStats.parseTs("2026-09-20 12:00:00", zone)
        val records = listOf(
            tx(-1.0, "2026-09-14 08:00:00"),
            tx(-2.0, "2026-09-20 08:00:00"),
            tx(-4.0, "2026-09-13 08:00:00"), // 上周日
        )
        val summary = CardStats.summarize(records, now, zone)
        assertEquals(3.0, summary.week.spend, 0.0001)
        assertEquals(7.0, summary.month.spend, 0.0001)
    }

    @Test
    fun `按商户聚合 支出降序`() {
        val records = listOf(
            tx(-2.0, "2026-09-19 08:00:00", merchant = "荷园餐厅"),
            tx(-9.0, "2026-09-19 12:00:00", merchant = "松园男浴室"),
            tx(-1.0, "2026-09-19 18:00:00", merchant = "荷园餐厅"),
        )
        val byMerchant = CardStats.byMerchant(records)
        assertEquals(listOf("松园男浴室", "荷园餐厅"), byMerchant.map { it.name })
        assertEquals(9.0, byMerchant[0].totals.spend, 0.0001)
        assertEquals(3.0, byMerchant[1].totals.spend, 0.0001)
    }

    @Test
    fun `每日支出只算消费`() {
        val records = listOf(
            tx(-3.0, "2026-09-19 08:00:00"),
            tx(50.0, "2026-09-19 09:00:00"), // 充值不算支出
        )
        val daily = CardStats.dailySpend(records, zone)
        assertEquals(3.0, daily[LocalDate.of(2026, 9, 19)] ?: 0.0, 0.0001)
    }

    @Test
    fun `充值渠道归到充值来源 不出现在消费去向里`() {
        // 这是用户实测报的问题：微信/支付宝充值在"钱花在哪了"里显示成 ¥0.00 · N 笔
        val now = CardStats.parseTs("2026-09-20 12:00:00", zone)
        val records = listOf(
            tx(-0.93, "2026-09-19 17:45:17", merchant = "松园男浴室"),
            tx(-12.5, "2026-09-18 12:00:00", merchant = "荷园餐厅"),
            tx(20.0, "2026-09-05 22:02:28", merchant = "微信支付转账充值"),
            tx(100.0, "2026-09-01 10:00:00", merchant = "支付宝充值"),
        )
        val summary = CardStats.summarize(records, now, zone)
        assertEquals(listOf("荷园餐厅", "松园男浴室"), summary.byMerchant.map { it.name })
        assertEquals(listOf("支付宝充值", "微信支付转账充值"), summary.incomeSources.map { it.name })
        assertEquals(100.0, summary.incomeSources[0].totals.income, 0.0001)
        assertEquals(1, summary.incomeSources[1].totals.count)
        // 消费去向里不能出现任何充值渠道
        assertEquals(0, summary.byMerchant.count { it.name.contains("充值") })
    }

    @Test
    fun `只有充值没有消费时 消费去向为空`() {
        val records = listOf(tx(20.0, "2026-09-05 22:02:28", merchant = "微信支付转账充值"))
        val bySpend = CardStats.merchantSpend(records)
        val byIncome = CardStats.incomeSources(records)
        assertEquals(0, bySpend.size)
        assertEquals(1, byIncome.size)
    }

    @Test
    fun `金额文案`() {
        assertEquals("12.34", CardStats.money(12.34))
        assertEquals("-12.34", CardStats.money(-12.34))
        assertEquals("-0.93", CardStats.signedMoney(-0.93))
        assertEquals("+20.00", CardStats.signedMoney(20.0))
    }
}
