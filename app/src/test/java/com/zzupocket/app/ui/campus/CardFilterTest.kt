package com.zzupocket.app.ui.campus

import com.zzupocket.app.domain.CardStats
import com.zzupocket.app.domain.CardTransaction
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardFilterTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 9, 20)

    private fun tx(
        amount: Double,
        day: String,
        merchant: String = "松园男浴室",
        summary: String = if (amount < 0) "水控POS消费流水" else "移动端交易",
    ) = CardTransaction(
        journo = "$day-$amount",
        summary = summary,
        merchantName = merchant,
        dateText = "$day 12:00:00",
        ts = CardStats.parseTs("$day 12:00:00", zone),
        amount = amount,
        isRefund = false,
        tradeType = if (amount < 0) "1" else "2",
    )

    private val records = listOf(
        tx(-0.93, "2026-09-19"),
        tx(-12.50, "2026-09-18", merchant = "荷园餐厅"),
        tx(-120.00, "2026-09-02", merchant = "教材科"),
        tx(20.00, "2026-09-05", merchant = "微信支付转账充值"),
        tx(-8.00, "2026-08-30", merchant = "荷园餐厅"),
    )

    private fun applied(filter: CardFilterState) =
        records.filter { filter.matches(it, today) }

    @Test
    fun `默认条件不过滤`() {
        val state = CardFilterState()
        assertFalse(state.active)
        assertEquals(5, applied(state).size)
    }

    @Test
    fun `近七天`() {
        val filter = CardFilterState(preset = DatePreset.WEEK)
        // 9/14 ~ 9/20
        assertEquals(listOf(-0.93, -12.50), applied(filter).map { it.amount })
    }

    @Test
    fun `本月与上月`() {
        assertEquals(4, applied(CardFilterState(preset = DatePreset.MONTH)).size)
        val lastMonth = applied(CardFilterState(preset = DatePreset.LAST_MONTH))
        assertEquals(1, lastMonth.size)
        assertEquals(-8.00, lastMonth[0].amount, 0.001)
    }

    @Test
    fun `自定义区间优先于预设`() {
        val filter = CardFilterState(
            preset = DatePreset.YEAR,
            customFrom = LocalDate.of(2026, 9, 2),
            customTo = LocalDate.of(2026, 9, 2),
        )
        assertTrue(filter.active)
        assertEquals(listOf(-120.00), applied(filter).map { it.amount })
    }

    @Test
    fun `类型筛选`() {
        assertEquals(4, applied(CardFilterState(type = CardFilter.SPEND)).size)
        assertEquals(1, applied(CardFilterState(type = CardFilter.INCOME)).size)
    }

    @Test
    fun `商户多选`() {
        val filter = CardFilterState(merchants = setOf("荷园餐厅"))
        assertEquals(2, applied(filter).size)
    }

    @Test
    fun `金额区间按绝对值`() {
        val big = CardFilterState(minAmount = 50.0)
        assertEquals(listOf(-120.00), applied(big).map { it.amount })
        val small = CardFilterState(maxAmount = 1.0)
        assertEquals(listOf(-0.93), applied(small).map { it.amount })
    }

    @Test
    fun `关键词匹配商户或摘要`() {
        assertEquals(2, applied(CardFilterState(keyword = "荷园")).size)
        assertEquals(4, applied(CardFilterState(keyword = "水控")).size)
        assertEquals(0, applied(CardFilterState(keyword = "不存在的词")).size)
    }

    @Test
    fun `多个条件是且的关系`() {
        val filter = CardFilterState(
            preset = DatePreset.MONTH,
            type = CardFilter.SPEND,
            merchants = setOf("松园男浴室"),
        )
        assertEquals(listOf(-0.93), applied(filter).map { it.amount })
    }
}
