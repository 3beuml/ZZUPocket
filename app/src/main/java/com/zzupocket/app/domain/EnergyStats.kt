package com.zzupocket.app.domain

import java.time.LocalDate
import java.time.ZoneId

/** 一次电量读数（同一表计的余额采样） */
data class EnergySample(val ts: Long, val kwh: Double)

/** 某一天的用电量（度）。[estimated] = 这天有推断成分（跨天/间隔太长） */
data class DayUsage(
    val light: Double = 0.0,
    val ac: Double = 0.0,
    val estimated: Boolean = false,
) {
    val total: Double get() = light + ac
}

/**
 * 用电量统计。
 *
 * ⚠️ **学校不提供历史用电量**（P4-0 实测：两个房间都回"所选房间不支持查询历史用电量"，
 * 原因是"未配置厂商"）。所以用电量只能靠**本机采样余额 + 差分**推算：
 *
 *  - 只把**正向下降**算作用电；余额上升是充值/补差，自动剔除（`max(0, 上一 - 当前)`）；
 *    这样充值不会把当天的消耗抹平 —— 充值前已经发生的消耗仍被前面的区间计入。
 *  - 一次采样的差值归到**后一次读数所在的那一天**；若跨天或间隔过长，
 *    这一天会被标成 `estimated`（界面上用浅色/虚线区分，不假装精确）。
 *
 * 这套口径与社区项目 ZZU-Electricity-Monitor 的做法一致（它也是定时采样 + 同样累加）。
 */
object EnergyStats {

    /** 采样间隔超过这个时长就算"不够密"，归到的那天标成估算 */
    const val DENSE_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /**
     * 低于这个度数算「电量偏低」（欠费另算，是 `kwh < 0`）。
     * 阈值与 ZZU-Electricity-Monitor 一致。
     *
     * 放在这里是为了让**今天页和桌面小组件共用一个数** ——
     * 两边口径不一致的话，用户会在 App 里看到红色、桌面上却是普通颜色，比不提醒还让人困惑。
     */
    const val LOW_KWH = 10.0

    fun dayKey(ts: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        java.time.Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()

    /** 某一天 0 点的时间戳（自然日划分，和校园卡消费同一个口径） */
    fun dayStart(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        day.atStartOfDay(zone).toInstant().toEpochMilli()

    /** 相邻两次采样之间的用电量；余额上升（充值）返回 0 */
    fun usageBetween(previous: EnergySample, current: EnergySample): Double =
        (previous.kwh - current.kwh).coerceAtLeast(0.0)

    /**
     * 把一串采样换算成"每天的用电量"。
     * 传进来的顺序无所谓，内部会按时间排序。
     */
    fun daily(samples: List<EnergySample>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Double> {
        if (samples.size < 2) return emptyMap()
        val out = HashMap<LocalDate, Double>()
        val sorted = samples.sortedBy { it.ts }
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val curr = sorted[i]
            val used = usageBetween(prev, curr)
            if (used <= 0.0) continue
            val day = dayKey(curr.ts, zone)
            out[day] = (out[day] ?: 0.0) + used
        }
        return out
    }

    /** 哪些天的数据带估算成分（跨天采样或间隔太长） */
    fun estimatedDays(samples: List<EnergySample>, zone: ZoneId = ZoneId.systemDefault()): Set<LocalDate> {
        if (samples.size < 2) return emptySet()
        val out = HashSet<LocalDate>()
        val sorted = samples.sortedBy { it.ts }
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val curr = sorted[i]
            if (usageBetween(prev, curr) <= 0.0) continue
            val day = dayKey(curr.ts, zone)
            val spansDays = dayKey(prev.ts, zone) != day
            val longGap = curr.ts - prev.ts > DENSE_INTERVAL_MS
            if (spansDays || longGap) out.add(day)
        }
        return out
    }

    /** 双表合并成"每天：照明 + 空调" */
    fun mergeDaily(
        light: List<EnergySample>,
        ac: List<EnergySample>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<LocalDate, DayUsage> {
        val lightDaily = daily(light, zone)
        val acDaily = daily(ac, zone)
        val estimated = estimatedDays(light, zone) + estimatedDays(ac, zone)
        return (lightDaily.keys + acDaily.keys).associateWith { day ->
            DayUsage(
                light = lightDaily[day] ?: 0.0,
                ac = acDaily[day] ?: 0.0,
                estimated = day in estimated,
            )
        }
    }

    /** 最近 [days] 天（含今天）的用量序列，缺失的天补 0 —— 画柱状图要连续 */
    fun series(
        daily: Map<LocalDate, DayUsage>,
        today: LocalDate,
        days: Int,
    ): List<Pair<LocalDate, DayUsage>> =
        (days - 1 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            day to (daily[day] ?: DayUsage())
        }

    /** 日均用量（只统计有数据的天；一天都不够就返回 0） */
    fun averageDaily(daily: Map<LocalDate, DayUsage>): Double =
        averageDailyKwh(daily.mapValues { it.value.total })

    /**
     * 单个表计的日均用量（只统计有数据的天）。
     *
     * 和 [averageDaily] 用的是同一条规则（见上面那个委托），因为桌面小组件要按
     * **表计分别**算"还能用几天"：照明还剩 228 度、空调还剩 96 度，两者日均也不一样，
     * 合成一个总日均算出来的天数对哪一路都不准。
     */
    fun averageDailyKwh(daily: Map<LocalDate, Double>): Double {
        val days = daily.values.filter { it > 0.0 }
        if (days.isEmpty()) return 0.0
        return days.sum() / days.size
    }

    /** 按当前余额和日均用量估"还能用几天"；日均为 0 时返回 null */
    fun daysLeft(balanceKwh: Double, averageDaily: Double): Double? =
        if (averageDaily <= 0.0) null else (balanceKwh / averageDaily).coerceAtLeast(0.0)

    /**
     * 「还能用几天」的**统一口径**：按表计各自、最近这么多天的日均。
     *
     * 这个常数存在的唯一理由是**让 App 电费页与桌面小组件算出同一个数**。
     * 两边各自写窗口（曾经是电费页 120 天、小组件 7 天）时，同一个余额会算出
     * 差很多的天数 —— 真机实测同一笔余额：电费页「15.9 天」、小组件「可用约 28 天」，
     * 用户根本没法判断该信哪个（更糟的是电费页那个是错的，见 [recentDailyAverage]）。
     */
    const val DAYS_LEFT_WINDOW_DAYS = 7

    /**
     * **单个表计**最近 [days] 天（含今天）的日均用量。
     *
     * 为什么不能拿 [averageDaily] 顶替：那个是"照明 + 空调合计"的日均。
     * 用合计日均去除某一表的余额，等于假设"照明和空调的钱可以混着花"——
     * 两路的可用天数会一起被低估（真机上照明少算 12 天、空调少算 15 天）。
     * 只统计**有用量的天**（和 [averageDailyKwh] 同一条规则），
     * 所以"这几天一次都没用电"不会把日均摊薄成 0。
     *
     * @param samples 该表计的余额采样；比窗口旧的会被丢掉（传进来的是宽窗口也无所谓）
     */
    fun recentDailyAverage(
        samples: List<EnergySample>,
        days: Int = DAYS_LEFT_WINDOW_DAYS,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Double {
        if (days <= 0) return 0.0
        val since = dayStart(today.minusDays((days - 1).toLong()), zone)
        return averageDailyKwh(daily(samples.filter { it.ts >= since }, zone))
    }

    /** 余额 + 该表计采样 → 还能用几天（口径就是上面的 [recentDailyAverage]） */
    fun daysLeftFor(
        balanceKwh: Double,
        samples: List<EnergySample>,
        days: Int = DAYS_LEFT_WINDOW_DAYS,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Double? = daysLeft(balanceKwh, recentDailyAverage(samples, days, today, zone))

    /** 度数 → 电费（元） */
    fun cost(kwh: Double, pricePerKwh: Double): Double = kwh * pricePerKwh

    /** 默认电价：河南居民合表电价，用户可在设置里改 */
    const val DEFAULT_PRICE_PER_KWH = 0.568
}
