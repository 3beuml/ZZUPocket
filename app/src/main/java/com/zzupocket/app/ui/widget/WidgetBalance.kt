package com.zzupocket.app.ui.widget

import com.zzupocket.app.domain.EnergyStats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 一个"数据块"（和课表里的课程块同一套视觉语言：左侧竖色条 + 名称 + 数值 + 小字）。
 *
 * ```
 *  ▌照明
 *    152.82 度        可用约 29 天
 * ```
 */
data class WidgetBlock(
    /** 「照明」「空调」「余额」「本周」 */
    val label: String,
    /** "152.82"；没有数据是 "—" */
    val value: String,
    /** "度" / "元" */
    val unit: String,
    /** 右侧那行小字：「可用约 29 天」「今日 3.20」「5 笔」…没有就是空串 */
    val hint: String,
    /** 数字用警示色（欠费 / 电量偏低 / 余额偏低） */
    val alert: Boolean,
)

/**
 * 「电量与校园卡」组件要显示的全部内容：左半电量、右半校园卡，各两个数据块。
 *
 * ```
 *                                      22:30 更新
 *  宿舍电量 · 柳园20号楼101   ┊  校园卡
 *  ▌照明                ┊  ▌余额
 *    152.82 度   可用约 29 天 ┊    36.50 元   今日 3.20
 *  ──────────────────────┊─────────────────────
 *  ▌空调                ┊  ▌本周
 *    80.21 度    可用约 21 天 ┊    87.60 元   5 笔
 * ```
 */
data class WidgetBalancePlan(
    /** 右上角：「22:30 更新」/「昨天 20:03」/「没设置房间」/「打开 App 更新」 */
    val update: String,
    val leftTitle: String,
    val rightTitle: String,
    /** 左半：照明 / 空调 */
    val leftBlocks: List<WidgetBlock>,
    /** 右半：余额 / 本周 */
    val rightBlocks: List<WidgetBlock>,
)

/**
 * 「电量 + 校园卡余额」组件的判断逻辑，纯函数（可单测）。
 *
 * ## 为什么是"左右两半 + 块"
 * 之前是"三行指标、数字右对齐占满整宽"。那个版面在**真实画布**（实测 342×195dp）里
 * 上部挤、下部空 —— 因为它是按官方老公式的 110dp 高度排的。
 * 现在改成左右两半、每半两个块：每块高度能吃到 ~68dp，数字可以放到 28~30dp，
 * 底部不再留空，而且**点击区变成两块大区域**（左半 → 电费页、右半 → 校园卡页），
 * 不再是那条 28dp 的窄条。
 *
 * ## 数字为什么独占一行
 * 上一版把数字和表计名挤在同一行，余额一到 `1288.5` 就被截断。
 * 现在数字在块里独占一行，列宽 ~148dp 放得下 `-1234.56 度`。
 *
 * ## 数据从哪来
 * 全部本地：电表余额与用量采样在 Room，校园卡余额是上次查到后存的缓存
 * （`CardBalanceStore`），今日/本周消费来自本地流水。组件**自己不打任何接口**，
 * 所以数字可能不新 —— 右上角如实标注更新时刻（取三份数据里**最旧**的那份，宁可保守）。
 */
object WidgetBalance {

    /** 校园卡余额低于这个数就标红：一顿饭的钱，低于它基本没法在食堂吃了 */
    const val CARD_LOW_YUAN = 10.0

    /** 数据超过这个时长算"较旧" */
    const val STALE_AFTER_MS = 24 * 60 * 60 * 1000L

    /**
     * 电量读数文案。口径与今天页的 `formatKwh` 一致（整数不带小数，否则两位小数）。
     */
    fun kwhText(v: Double?): String = when {
        v == null -> "—"
        v == v.toLong().toDouble() -> v.toLong().toString()
        else -> String.format(Locale.US, "%.2f", v)
    }

    /** 金额文案，固定两位小数（与校园卡页一致） */
    fun moneyText(v: Double?): String =
        if (v == null) "—" else String.format(Locale.US, "%.2f", v)

    /** 电量为负是欠费、低于阈值是偏低 —— 两种都用警示色（阈值与今天页共用 `EnergyStats.LOW_KWH`） */
    fun meterAlert(v: Double?): Boolean = v != null && v < EnergyStats.LOW_KWH

    fun cardAlert(v: Double?): Boolean = v != null && v < CARD_LOW_YUAN

    /**
     * 电量块右侧那行小字：还能用几天。
     *
     * 口径：余额 ÷ **该表计**最近 `EnergyStats.DAYS_LEFT_WINDOW_DAYS` 天的日均。
     * 窗口和算法都在 `EnergyStats` 里，App 电费页引的是同一个 —— 两处必须给出同一个数，
     * 否则用户会在"要不要充电费"这件事上被互相矛盾的两个数字坑到。
     * 写「**约**」是认真的：学校不给历史用电量，日均全靠本机每 4 小时采样推出来，
     * WorkManager 还会被系统压制。没有样本时明说没有样本，不编一个看起来像真的数字。
     */
    fun daysLeftText(balanceKwh: Double?, avgDailyKwh: Double): String = when {
        balanceKwh == null -> ""
        balanceKwh < 0 -> "已欠费"
        avgDailyKwh <= 0.0 -> "暂无用量样本"
        else -> {
            val days = EnergyStats.daysLeft(balanceKwh, avgDailyKwh) ?: return "暂无用量样本"
            when {
                days >= 999 -> "可用 999+ 天"
                days >= 100 -> "可用 ${days.roundToInt()} 天"
                else -> "可用约 ${days.roundToInt().coerceAtLeast(1)} 天"
            }
        }
    }

    /** 校园卡块的第二落点：今天花了多少 / 本周多少笔 */
    fun todaySpendText(spend: Double?): String =
        if (spend == null) "未同步" else "今日 ${moneyText(spend)}"

    fun weekCountText(count: Int?): String =
        if (count == null) "未同步" else "$count 笔"

    /**
     * 右上角那行小字：数据最新到什么时候。
     *
     * 取三份数据里**最旧**的那份 —— 报最新的一份会让用户以为整块都是新的，
     * 而校园卡余额可能已经是前天的了。宁可保守。
     */
    fun updateLabel(oldestAt: Long?, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (oldestAt == null || oldestAt <= 0L) return "打开 App 更新"
        val then = Instant.ofEpochMilli(oldestAt).atZone(zone)
        // 「今天」也从 nowMillis 推，而不是 LocalDate.now()：这样这个函数是纯的（可单测），
        // 也不会在跨零点/时区切换时算出"昨天"其实还是今天这种怪结果。
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val time = String.format(Locale.US, "%02d:%02d", then.hour, then.minute)
        val days = java.time.temporal.ChronoUnit.DAYS.between(then.toLocalDate(), today)
        return when {
            days <= 0L -> "$time 更新"
            days == 1L -> "昨天 $time"
            days < 7L -> "$days 天前"
            else -> "${then.monthValue}月${then.dayOfMonth}日"
        }
    }

    /**
     * 组装组件内容。
     *
     * @param roomLabel 房间可读名（如「柳园20号楼101」）；没设置就是空串
     * @param lightKwh 照明余额（度）；null = 没设置房间 / 还没有读数
     * @param lightAvgDaily 照明近 7 天日均用量（0 = 还没有可用样本）
     * @param cardYuan 校园卡余额（元）；null = 从来没查到过
     * @param cardTodaySpend 校园卡今日消费（元）；null = 本地还没有流水（从没同步过）
     * @param cardWeekSpend 本周消费（元）；null = 没有流水
     * @param cardWeekCount 本周笔数；null = 没有流水
     */
    fun build(
        roomLabel: String,
        lightKwh: Double?,
        acKwh: Double?,
        lightAvgDaily: Double,
        acAvgDaily: Double,
        lightAt: Long?,
        acAt: Long?,
        cardYuan: Double?,
        cardAt: Long?,
        cardTodaySpend: Double?,
        cardWeekSpend: Double?,
        cardWeekCount: Int?,
        nowMillis: Long,
        /**
         * 只有一路表计（亿达学生公寓、洛阳校区、留学生公寓那种整间一个表）：
         * 左半只显示一个「电量」块，第二格留空 —— 不摆一个空的"空调"格。
         * 布局是 4 个固定格子（左 2 右 2），所以**必须仍然给满 4 个块**，
         * 否则第二格会残留上一次的文字。
         */
        singleMeter: Boolean = false,
    ): WidgetBalancePlan {
        val hasRoom = roomLabel.isNotBlank() || lightKwh != null || acKwh != null
        val leftTitle = if (roomLabel.isNotBlank()) "宿舍电量 · $roomLabel" else "宿舍电量"

        // 单表计时用有值的那一路（亿达填的是照明那一路）
        val onlyKwh = lightKwh ?: acKwh
        val onlyAvg = if (lightKwh != null) lightAvgDaily else acAvgDaily

        val leftBlocks = if (singleMeter) {
            listOf(
                WidgetBlock(
                    label = "电量",
                    value = kwhText(onlyKwh),
                    unit = "度",
                    hint = if (hasRoom) daysLeftText(onlyKwh, onlyAvg) else "",
                    alert = meterAlert(onlyKwh),
                ),
                WidgetBlock(label = "", value = "", unit = "", hint = "", alert = false),
            )
        } else {
            listOf(
                WidgetBlock(
                    label = "照明",
                    value = kwhText(lightKwh),
                    unit = "度",
                    hint = if (hasRoom) daysLeftText(lightKwh, lightAvgDaily) else "",
                    alert = meterAlert(lightKwh),
                ),
                WidgetBlock(
                    label = "空调",
                    value = kwhText(acKwh),
                    unit = "度",
                    hint = if (hasRoom) daysLeftText(acKwh, acAvgDaily) else "",
                    alert = meterAlert(acKwh),
                ),
            )
        }

        val rightBlocks = listOf(
            WidgetBlock(
                label = "余额",
                value = moneyText(cardYuan),
                unit = "元",
                // 没同步过流水时明说"未同步"，绝不显示成 0.00 假装今天没花钱
                hint = if (cardYuan == null && cardTodaySpend == null) "打开 App 更新"
                else todaySpendText(cardTodaySpend),
                alert = cardAlert(cardYuan),
            ),
            WidgetBlock(
                label = "本周",
                value = moneyText(cardWeekSpend),
                unit = "元",
                hint = weekCountText(cardWeekCount),
                alert = false,
            ),
        )

        val update = when {
            // 房间没配之前两个电表都是空的，先把最该做的事说出来
            !hasRoom -> "没设置房间"
            else -> updateLabel(listOfNotNull(lightAt, acAt, cardAt).minOrNull(), nowMillis)
        }

        return WidgetBalancePlan(
            update = update,
            leftTitle = leftTitle,
            rightTitle = "校园卡",
            leftBlocks = leftBlocks,
            rightBlocks = rightBlocks,
        )
    }
}
