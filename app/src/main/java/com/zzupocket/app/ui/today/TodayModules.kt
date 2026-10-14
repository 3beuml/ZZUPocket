package com.zzupocket.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * 「今天的日程」——只列**自己在课表里加的日程**（自定义日程），
 * 不和教务课程混在一起：教务课是学校排的，自定义日程是自己安排的，看的时候心理预期不一样。
 *
 * 点击跳到课表（在那里可以改时间或删掉）。
 */
@Composable
fun TodayCustomScheduleCard(
    entries: List<TimelineEntry>,
    onOpenSchedule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    Card(modifier = modifier.fillMaxWidth().clickable(onClick = onOpenSchedule)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.EditCalendar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("今天的日程", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
            entries.forEach { entry ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        text = TodayTimeline.clock(entry.startMin),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(46.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val detail = listOfNotNull(
                            TodayTimeline.clock(entry.endMin).let { "至 $it" },
                            entry.subtitle.takeIf { it.isNotBlank() },
                        ).joinToString(" · ")
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Text(
                text = "这些是你自己在课表里加的日程，点进去可以改时间或删掉。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 「近 7 天用电」——学校不给历史用电量，这里全部是**本机采样**推算出来的
 * （和电费页同一套数据），所以断网也能看。
 *
 * 微小柱状图：每根柱子按当天照明 + 空调堆叠，高度按 7 天里的最大值归一化。
 */
@Composable
fun TodayEnergyTrendCard(
    bars: List<UsageTrendBar>,
    totalKwh: Double,
    cost: Double,
    avgKwh: Double,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 整间一个表的楼（亿达学生公寓）：只有一路电量，图例不能写"照明/空调"。
     * 柱子本身就是"有数据的那一路往上长"，所以只换图例文案即可。
     */
    singleMeter: Boolean = false,
) {
    Card(modifier = modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("近 7 天用电", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }

            if (bars.isEmpty() || bars.all { it.total <= 0.0 }) {
                Text(
                    text = "还没有用电数据。打开 App 时会自动采样一次，攒够两天就能看出趋势。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Text(
                text = "合计 ${fmt(totalKwh)} 度 · 约 ¥${fmt(cost)} · 日均 ${fmt(avgKwh)} 度",
                style = MaterialTheme.typography.bodyMedium,
            )

            DivergingTrendChart(bars)

            // 两个表计的合计分开写：它们是独立账户，混在一起看不出谁更费。
            // ⚠️ 单表计的楼（亿达）没有"照明/空调"之分，这里必须写「用电量」，否则就是拿照明顶替。
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (singleMeter) {
                    LegendDot(
                        MaterialTheme.colorScheme.primary,
                        "用电量 ${fmt(bars.sumOf { it.light + it.ac })} 度",
                    )
                } else {
                    LegendDot(MaterialTheme.colorScheme.primary, "照明 ${fmt(bars.sumOf { it.light })} 度")
                    if (bars.any { it.ac > 0.0 }) {
                        Spacer(Modifier.width(14.dp))
                        LegendDot(MaterialTheme.colorScheme.tertiary, "空调 ${fmt(bars.sumOf { it.ac })} 度")
                    }
                }
            }
        }
    }
}

/**
 * 上下分离的柱状图：**基线在中间**，照明从基线往上长、空调从基线往下长。
 *
 * 这样一眼就能看出"哪天照明多、哪天开空调多"，而且两边用**同一个刻度**
 * （都按 7 天里最大的那一边归一化），所以上下柱子的长度可以直接比。
 *
 * 近 7 天完全没有空调用电时，下半部分不画（也不占高度），照明柱子直接用满高度。
 */
@Composable
private fun DivergingTrendChart(bars: List<UsageTrendBar>) {
    val hasAc = bars.any { it.ac > 0.0 }
    val maxValue = maxOf(
        bars.maxOf { it.light },
        if (hasAc) bars.maxOf { it.ac } else 0.0,
    ).coerceAtLeast(0.1)

    val railHeight = if (hasAc) 44.dp else 84.dp
    val barWidthFraction = 0.62f

    Column(Modifier.fillMaxWidth()) {
        // 上半：照明（贴着基线往上长）
        Row(
            modifier = Modifier.fillMaxWidth().height(railHeight),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            bars.forEach { bar ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val h = (railHeight.value * (bar.light / maxValue)).dp
                    if (h > 0.5.dp) {
                        Box(
                            Modifier
                                .fillMaxWidth(barWidthFraction)
                                .height(h)
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    } else {
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }

        // 基线
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        // 下半：空调（贴着基线往下长）——没有空调用量就整段不画
        if (hasAc) {
            Row(
                modifier = Modifier.fillMaxWidth().height(railHeight),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                bars.forEach { bar ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        val h = (railHeight.value * (bar.ac / maxValue)).dp
                        if (h > 0.5.dp) {
                            Box(
                                Modifier
                                    .fillMaxWidth(barWidthFraction)
                                    .height(h)
                                    .clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
                                    .background(MaterialTheme.colorScheme.tertiary),
                            )
                        } else {
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }
        }

        // 日期标签
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            bars.forEach { bar ->
                Text(
                    text = bar.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 1.234 → "1.23"；整数就不带小数点 */
private fun fmt(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.2f", v)
