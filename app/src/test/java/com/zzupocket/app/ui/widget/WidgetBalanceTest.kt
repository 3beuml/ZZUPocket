package com.zzupocket.app.ui.widget

import com.zzupocket.app.domain.EnergyStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「电量与校园卡」组件的回归测试（左右两半、每半两块）。
 *
 * 钉的是这几件事：
 *   · 数字格式化与今天页/校园卡页同一口径；
 *   · 「可用约 N 天」怎么算、欠费怎么说、没样本怎么说；
 *   · 校园卡今日/本周消费：没同步过流水时要说「未同步」，绝不显示 0.00 假装今天没花钱；
 *   · 右上角更新时刻取三份数据里最旧的那份（宁可保守）。
 */
class WidgetBalanceTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val hour = 60 * 60 * 1000L
    private val now: Long = LocalDate.of(2026, 9, 29).atTime(22, 30)
        .atZone(zone).toInstant().toEpochMilli()

    private fun build(
        roomLabel: String = "柳园20号楼 101",
        lightKwh: Double? = 152.82,
        acKwh: Double? = 80.21,
        lightAvg: Double = 5.2,
        acAvg: Double = 3.8,
        lightAt: Long? = now,
        acAt: Long? = now,
        cardYuan: Double? = 36.5,
        cardAt: Long? = now,
        cardTodaySpend: Double? = 3.20,
        cardWeekSpend: Double? = 87.6,
        cardWeekCount: Int? = 5,
    ) = WidgetBalance.build(
        roomLabel = roomLabel,
        lightKwh = lightKwh,
        acKwh = acKwh,
        lightAvgDaily = lightAvg,
        acAvgDaily = acAvg,
        lightAt = lightAt,
        acAt = acAt,
        cardYuan = cardYuan,
        cardAt = cardAt,
        cardTodaySpend = cardTodaySpend,
        cardWeekSpend = cardWeekSpend,
        cardWeekCount = cardWeekCount,
        nowMillis = now,
    )

    // ── 格式化 ───────────────────────────────────────────────────────────

    @Test
    fun `整数电量不显示小数_其余两位小数`() {
        assertEquals("228", WidgetBalance.kwhText(228.0))
        assertEquals("152.82", WidgetBalance.kwhText(152.82))
        assertEquals("0", WidgetBalance.kwhText(0.0))
        assertEquals("-3.20", WidgetBalance.kwhText(-3.2))   // 欠费时学校返回负数，是正常业务数据
        assertEquals("—", WidgetBalance.kwhText(null))
    }

    @Test
    fun `金额固定两位小数`() {
        assertEquals("36.50", WidgetBalance.moneyText(36.5))
        assertEquals("0.00", WidgetBalance.moneyText(0.0))
        assertEquals("-1.50", WidgetBalance.moneyText(-1.5))
        assertEquals("—", WidgetBalance.moneyText(null))
    }

    // ── 警示 ─────────────────────────────────────────────────────────────

    @Test
    fun `欠费与电量偏低都要标红_阈值与今天页共用`() {
        assertTrue(WidgetBalance.meterAlert(-0.5))
        assertTrue(WidgetBalance.meterAlert(9.99))
        assertFalse(WidgetBalance.meterAlert(EnergyStats.LOW_KWH))
        assertFalse(WidgetBalance.meterAlert(100.0))
        assertFalse(WidgetBalance.meterAlert(null))
    }

    @Test
    fun `校园卡余额低于一顿饭的钱才标红`() {
        assertTrue(WidgetBalance.cardAlert(9.99))
        assertTrue(WidgetBalance.cardAlert(0.0))
        assertFalse(WidgetBalance.cardAlert(10.0))
        assertFalse(WidgetBalance.cardAlert(36.5))
        assertFalse(WidgetBalance.cardAlert(null))
    }

    // ── 可用天数 ─────────────────────────────────────────────────────────

    @Test
    fun `可用天数按余额除日均算_并且写成约`() {
        // 152.82 / 5.2 ≈ 29.4 → 约 29 天；写「约」是因为日均靠本机 4 小时采样推的
        assertEquals("可用约 29 天", WidgetBalance.daysLeftText(152.82, 5.2))
        assertEquals("可用约 21 天", WidgetBalance.daysLeftText(80.21, 3.8))
    }

    @Test
    fun `余量很大时不给一堆精确数字`() {
        assertEquals("可用 999+ 天", WidgetBalance.daysLeftText(10000.0, 0.5))
    }

    @Test
    fun `欠费时说已欠费而不是可用 0 天`() {
        assertEquals("已欠费", WidgetBalance.daysLeftText(-2.0, 5.2))
        assertEquals("已欠费", WidgetBalance.daysLeftText(-0.1, 5.2))
    }

    @Test
    fun `没有用量样本时明说没有_不编一个看起来像真的天数`() {
        assertEquals("暂无用量样本", WidgetBalance.daysLeftText(152.82, 0.0))
        assertEquals("", WidgetBalance.daysLeftText(null, 5.2))
    }

    // ── 校园卡第二落点 ───────────────────────────────────────────────────

    @Test
    fun `今日消费文案`() {
        assertEquals("今日 3.20", WidgetBalance.todaySpendText(3.20))
        assertEquals("今日 0.00", WidgetBalance.todaySpendText(0.0))
        assertEquals("未同步", WidgetBalance.todaySpendText(null))
    }

    @Test
    fun `本周笔数文案`() {
        assertEquals("5 笔", WidgetBalance.weekCountText(5))
        assertEquals("未同步", WidgetBalance.weekCountText(null))
    }

    // ── 更新时刻 ─────────────────────────────────────────────────────────

    @Test
    fun `更新时刻按天给不同粒度`() {
        assertEquals("22:30 更新", WidgetBalance.updateLabel(now, now, zone))
        assertEquals("21:30 更新", WidgetBalance.updateLabel(now - hour, now, zone))
        assertEquals("昨天 21:30", WidgetBalance.updateLabel(now - 25 * hour, now, zone))
        assertEquals("3 天前", WidgetBalance.updateLabel(now - 73 * hour, now, zone))
        assertEquals("9月20日", WidgetBalance.updateLabel(now - 9 * 24 * hour, now, zone))
    }

    @Test
    fun `从没查过余额时说打开 App 更新`() {
        assertEquals("打开 App 更新", WidgetBalance.updateLabel(null, now, zone))
        assertEquals("打开 App 更新", WidgetBalance.updateLabel(0L, now, zone))
    }

    // ── 内容组装：左右两半、每半两块 ─────────────────────────────────────

    @Test
    fun `左半是照明与空调_带可用天数`() {
        val p = build()
        assertEquals("宿舍电量 · 柳园20号楼 101", p.leftTitle)
        assertEquals(2, p.leftBlocks.size)

        assertEquals("照明", p.leftBlocks[0].label)
        assertEquals("152.82", p.leftBlocks[0].value)
        assertEquals("度", p.leftBlocks[0].unit)
        assertEquals("可用约 29 天", p.leftBlocks[0].hint)

        assertEquals("空调", p.leftBlocks[1].label)
        assertEquals("80.21", p.leftBlocks[1].value)
        assertEquals("可用约 21 天", p.leftBlocks[1].hint)
        assertTrue(p.leftBlocks.none { it.alert })
    }

    @Test
    fun `右半是余额与本周_带今日消费与笔数`() {
        val p = build()
        assertEquals("校园卡", p.rightTitle)
        assertEquals(2, p.rightBlocks.size)

        assertEquals("余额", p.rightBlocks[0].label)
        assertEquals("36.50", p.rightBlocks[0].value)
        assertEquals("元", p.rightBlocks[0].unit)
        assertEquals("今日 3.20", p.rightBlocks[0].hint)

        assertEquals("本周", p.rightBlocks[1].label)
        assertEquals("87.60", p.rightBlocks[1].value)
        assertEquals("5 笔", p.rightBlocks[1].hint)
    }

    @Test
    fun `更新时刻取三份数据里最旧的那份`() {
        // 电表刚读的、余额是前天的 → 报前天的（不能让用户以为整块都是新的）
        // 22:30 往前 30 小时 = 昨天 16:30
        val p = build(lightAt = now, acAt = now, cardAt = now - 30 * hour)
        assertEquals("昨天 16:30", p.update)
    }

    @Test
    fun `欠费的表计那一块标红并写已欠费`() {
        val p = build(lightKwh = -2.0, acKwh = 80.21)
        assertTrue(p.leftBlocks[0].alert)
        assertEquals("已欠费", p.leftBlocks[0].hint)
        assertFalse(p.leftBlocks[1].alert)
    }

    @Test
    fun `余额低于十元标红_但不影响本周那块`() {
        val p = build(cardYuan = 8.4)
        assertTrue(p.rightBlocks[0].alert)
        assertFalse(p.rightBlocks[1].alert)
    }

    // ── 数据不全 ─────────────────────────────────────────────────────────

    @Test
    fun `没设置房间时右上角明说_电表块不给可用天数的空话`() {
        val p = build(roomLabel = "", lightKwh = null, acKwh = null, lightAt = null, acAt = null)
        assertEquals("没设置房间", p.update)
        assertEquals("宿舍电量", p.leftTitle)
        assertEquals("—", p.leftBlocks[0].value)
        assertEquals("", p.leftBlocks[0].hint)
    }

    @Test
    fun `从没查过校园卡余额时明说去 App 更新`() {
        val p = build(
            cardYuan = null, cardAt = null, cardTodaySpend = null, cardWeekSpend = null,
            cardWeekCount = null, lightAt = null, acAt = null,
        )
        assertEquals("—", p.rightBlocks[0].value)
        assertEquals("打开 App 更新", p.rightBlocks[0].hint)
        assertEquals("—", p.rightBlocks[1].value)
        assertEquals("未同步", p.rightBlocks[1].hint)
    }

    @Test
    fun `有余额但流水没同步过时说未同步_不是 0_00`() {
        val p = build(cardYuan = 36.5, cardTodaySpend = null, cardWeekSpend = null, cardWeekCount = null)
        assertEquals("36.50", p.rightBlocks[0].value)
        assertEquals("未同步", p.rightBlocks[0].hint)
        assertEquals("未同步", p.rightBlocks[1].hint)
    }
}
