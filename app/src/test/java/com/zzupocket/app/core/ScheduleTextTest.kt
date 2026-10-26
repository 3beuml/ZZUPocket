package com.zzupocket.app.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTextTest {

    // ── 星期表头下面那行日期 ────────────────────────────────────────────────
    // 这一组钉的是课表页表头的日期：第 N 周 → 那一周每天的日期。
    // 算错一天的后果很直接：星期三下面写着星期二的日期。

    @Test
    fun `起始日正好是周一时_第 N 周的周一`() {
        val start = LocalDate.of(2026, 9, 7)   // 周一
        assertEquals(start, ScheduleText.mondayOf(start, 1))
        assertEquals(LocalDate.of(2026, 9, 28), ScheduleText.mondayOf(start, 4))
    }

    @Test
    fun `起始日在周中时_先对齐到那一周的周一`() {
        // 教务给的起始日常常是周中某天（这里给周三），第 1 周仍应从周一算起
        val start = LocalDate.of(2026, 9, 9)   // 周三
        assertEquals(LocalDate.of(2026, 9, 7), ScheduleText.mondayOf(start, 1))
        assertEquals(LocalDate.of(2026, 9, 14), ScheduleText.mondayOf(start, 2))
    }

    @Test
    fun `日期用完整的月-日并补零`() {
        assertEquals("09-30", ScheduleText.dayLabel(LocalDate.of(2026, 9, 30)))
        assertEquals("10-01", ScheduleText.dayLabel(LocalDate.of(2026, 10, 1)))
        assertEquals("01-04", ScheduleText.dayLabel(LocalDate.of(2027, 1, 4)))
    }

    @Test
    fun `跨月与跨年那一周的日期是连续的`() {
        val start = LocalDate.of(2026, 9, 7)
        // 第 4 周：9/28 起，跨到 10 月
        val week4 = ScheduleText.mondayOf(start, 4)!!
        assertEquals(
            listOf("09-28", "09-29", "09-30", "10-01", "10-02", "10-03", "10-04"),
            (0..6).map { ScheduleText.dayLabel(week4.plusDays(it.toLong())) },
        )
        // 跨年：第 17 周从 12/28 起
        val week17 = ScheduleText.mondayOf(start, 17)!!
        assertEquals(
            listOf("12-28", "12-29", "12-30", "12-31", "01-01", "01-02", "01-03"),
            (0..6).map { ScheduleText.dayLabel(week17.plusDays(it.toLong())) },
        )
    }

    @Test
    fun `没有起始日或周次非法时不显示日期`() {
        assertNull(ScheduleText.mondayOf(null, 3))
        assertNull(ScheduleText.mondayOf(LocalDate.of(2026, 9, 7), 0))
        assertNull(ScheduleText.mondayOf(LocalDate.of(2026, 9, 7), -1))
    }

    @Test
    fun `连续区间压缩`() {
        assertEquals("1-4, 6-16周", ScheduleText.compressWeeks((1..4) + (6..16)))
        assertEquals("1-16周", ScheduleText.compressWeeks((1..16).toList()))
        assertEquals("1, 4, 7周", ScheduleText.compressWeeks(listOf(1, 4, 7)))
        assertEquals("5周", ScheduleText.compressWeeks(listOf(5)))
    }

    @Test
    fun `全单周与全双周用文字表示`() {
        // 逐周列出来会变成 "1, 3, 5, 7, 9, 11, 13, 15周"，很难读
        assertEquals("1-15周（单周）", ScheduleText.compressWeeks(listOf(1, 3, 5, 7, 9, 11, 13, 15)))
        assertEquals("2-16周（双周）", ScheduleText.compressWeeks(listOf(2, 4, 6, 8, 10, 12, 14, 16)))
    }

    @Test
    fun `单周课偶尔多上一周`() {
        // 真实数据里出现过：1,3,5,...,15 再加上第 16 周
        // 以前会输出 "1, 3, 5, 7, 9, 11, 13, 15-16周"，很难读
        assertEquals(
            "1-15周（单周） + 第16周",
            ScheduleText.compressWeeks(listOf(1, 3, 5, 7, 9, 11, 13, 15, 16)),
        )
        assertEquals(
            "1-15周（单周） + 2, 16周",
            ScheduleText.compressWeeks(listOf(1, 2, 3, 5, 7, 9, 11, 13, 15, 16)),
        )
    }

    @Test
    fun `补周太多时不再套用单双周`() {
        // 偶数周有 4 个，已经不能算"零星补周"了，退回逐个区间
        assertEquals(
            "1-8周",
            ScheduleText.compressWeeks(listOf(1, 2, 3, 4, 5, 6, 7, 8)),
        )
    }

    @Test
    fun `短连续区间不被误判成单周加补周`() {
        // 真实案例（issue #5）：研究生课 5-9 周连续，曾显示成 "5-9周（单周） + 6, 8周"
        assertEquals("5-9周", ScheduleText.compressWeeks((5..9).toList()))
        assertEquals("2-9周", ScheduleText.compressWeeks((2..9).toList()))
    }

    @Test
    fun `偶数个但非全同奇偶时不套用单双周`() {
        // 1..8 全都有，不应该被当成单周或双周
        assertEquals("1-8周", ScheduleText.compressWeeks((1..8).toList()))
        // 只有两三个奇数周时也不加（否则 "1-5周（单周）" 反而绕）
        assertEquals("1, 3周", ScheduleText.compressWeeks(listOf(1, 3)))
    }

    @Test
    fun `空集合不崩`() {
        assertEquals("—", ScheduleText.compressWeeks(emptyList()))
    }

    @Test
    fun `时段文本`() {
        assertEquals(
            "周二 3-4节 · 周四 5-6节",
            ScheduleText.timeSlots(listOf(Triple(2, 3, 4), Triple(4, 5, 6))),
        )
        assertEquals("周一 1节", ScheduleText.timeSlots(listOf(Triple(1, 1, 1))))
    }

    @Test
    fun `从 ISO 时间串里取时刻`() {
        assertEquals("08:00", ScheduleText.timeOfDay("2026-09-22T08:00:00+08:00"))
        assertEquals(null, ScheduleText.timeOfDay(null))
        assertEquals(null, ScheduleText.timeOfDay("不是时间"))
    }

    // ── 地点规整 ─────────────────────────────────────────────────────────

    @Test
    fun `相邻重复合并_带下划线的原有形态`() {
        assertEquals("南2 206", ScheduleText.tidyPlace("南2 南2_206"))
        assertEquals("南2 206", ScheduleText.tidyPlace("南2_206"))
    }

    @Test
    fun `楼名前缀被带进教室名时剥掉前缀`() {
        // 真实案例（issue #6）：馆名楼的教室号不带分隔符（"物理馆X101"），拼楼栋名后显示两遍
        assertEquals("物理馆 X101", ScheduleText.tidyPlace("物理馆 物理馆X101"))
        assertEquals("材料馆 203", ScheduleText.tidyPlace("材料馆 材料馆203"))
        // 数字结尾的楼名也剥（issue #6 复测：周二课表"中3 中3302"重复）；
        // 房号 302（"中3"楼里的 302，不是 3302）
        assertEquals("中3 302", ScheduleText.tidyPlace("中3 中3302"))
    }

    @Test
    fun `前导零的剩余不是前缀不剥`() {
        // `南2 南209` 剥前缀会把房号剥坏成 "南2 09" —— 以 0 开头的纯数字剩余保持原词
        assertEquals("南2 南209", ScheduleText.tidyPlace("南2 南209"))
        // 正常的楼栋+房号不受影响
        assertEquals("南2 206", ScheduleText.tidyPlace("南2 206"))
    }

    @Test
    fun `房号自带楼名首字缩写的剥除`() {
        // 真实案例（issue #6 复测 2026-10-26）：教务房间号写成「楼名首字 + 纯数字」，
        // 拼楼栋名后像重复了半个楼名；小组件放大态还会把这些半重复截断得更难看
        assertEquals("物理馆 505", ScheduleText.tidyPlace("物理馆 物505"))
        assertEquals("材料馆 317", ScheduleText.tidyPlace("材料馆 材317"))
        assertEquals("物理馆 505", ScheduleText.tidyPlace("物理馆 物_505"))
        // 幂等：剥过一次再过不变
        assertEquals("物理馆 505", ScheduleText.tidyPlace("物理馆 505"))

        // 防误伤边界
        // 余部不是纯数字：不剥（带字母的房号不属于「首字缩写+纯数字」形态）
        assertEquals("物理馆 物A", ScheduleText.tidyPlace("物理馆 物A"))
        // 余部以 0 开头 = 房号本身（与 `南2 南209` 前导零守卫同一口径）
        assertEquals("南2 南209", ScheduleText.tidyPlace("南2 南209"))
        // 首字与房号之间有空格的缩写（教务写「简写 + 空格 + 房号」）：中间的「物」丢掉
        assertEquals("物理馆 505", ScheduleText.tidyPlace("物理馆 物 505"))
        // 不相干楼栋不触碰
        assertEquals("河南省 文3", ScheduleText.tidyPlace("河南省 文3"))
        // 房号不以楼名首字开头（首字是数字）
        assertEquals("北3 412", ScheduleText.tidyPlace("北3 412"))
    }

    @Test
    fun `超长地点保头保尾缩写`() {
        // 真实案例（issue #6）：编译原理实验的地点是学院自由文本，
        // 普通截断把房号切掉；要保住头部单位 + 尾部房号
        val out = ScheduleText.abbreviatePlace("信息工程学院网络实验室302", maxWeighted = 10)
        assertTrue("应保留房号: $out", out!!.endsWith("302"))
        assertTrue("应保留头部: $out", out.startsWith("信息工程"))
        assertTrue("中间应有省略号: $out", out.contains("…"))
        // 不超预算原样返回
        assertEquals("中3 3302", ScheduleText.abbreviatePlace("中3 3302"))
        assertNull(ScheduleText.abbreviatePlace(null))
    }

    @Test
    fun `地点规整是幂等的`() {
        assertEquals("物理馆 X101", ScheduleText.tidyPlace("物理馆 X101"))
        assertEquals("南2 206", ScheduleText.tidyPlace("南2 206"))
    }

    @Test
    fun `长课名建议字号缩减`() {
        // 研究生班级名课（issue #6）：加权宽度（汉字 1、其它 0.5）13 起缩 1 号，18 起缩 2 号
        assertEquals(0, ScheduleText.fontAdjust("大学物理"))
        // 9 字的班级名两行放得下，不需要缩
        assertEquals(0, ScheduleText.fontAdjust("专业英语人工智能班"))
        assertEquals(-1, ScheduleText.fontAdjust("新时代中国特色社会主义理论"))
        assertEquals(-2, ScheduleText.fontAdjust("数据科学与大数据技术专业实验班课程设计"))
        // 英文按半宽计：长串才缩
        assertEquals(0, ScheduleText.fontAdjust("HUSSAIN"))
        assertEquals(-1, ScheduleText.fontAdjust("Computer Networks and Security"))
    }
}
