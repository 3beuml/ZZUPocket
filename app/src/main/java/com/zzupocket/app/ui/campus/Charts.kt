package com.zzupocket.app.ui.campus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zzupocket.app.domain.DayUsage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max

private val AXIS_FMT = DateTimeFormatter.ofPattern("M/d")

/** 柱状图里的一根柱子：同一天的照明 + 空调 */
data class UsageBar(
    val day: LocalDate,
    val light: Double,
    val ac: Double,
    val estimated: Boolean,
) {
    val total: Double get() = light + ac
}

/**
 * 每日用电量**堆叠柱状图**：一根柱子里下面那段是照明、上面那段是空调，
 * 柱顶高度 = 当天总用电量。点柱子看当天明细。
 *
 * 估算日（采样跨天或间隔过长）整根柱子画虚线边框 + 半透明，
 * 不把推算出来的数当成实测值。
 */
@Composable
fun StackedUsageBarChart(
    bars: List<UsageBar>,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    selected: LocalDate? = null,
    onSelect: ((LocalDate) -> Unit)? = null,
) {
    val lightColor = MaterialTheme.colorScheme.primary
    val acColor = MaterialTheme.colorScheme.tertiary
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val selectionColor = MaterialTheme.colorScheme.secondary

    if (bars.isEmpty()) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text(
                text = "还没有采样数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val maxTotal = bars.maxOf { it.total }.coerceAtLeast(0.1)

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(bars) {
                    if (onSelect == null) return@pointerInput
                    detectTapGestures { offset ->
                        val slot = size.width / bars.size
                        val index = (offset.x / slot).toInt().coerceIn(0, bars.size - 1)
                        onSelect(bars[index].day)
                    }
                },
        ) {
            val slot = size.width / bars.size
            val barWidth = (slot * 0.62f).coerceAtLeast(2f)
            val chartHeight = size.height - 6f

            drawLine(
                color = baseline,
                start = Offset(0f, chartHeight),
                end = Offset(size.width, chartHeight),
                strokeWidth = 2f,
            )

            bars.forEachIndexed { index, bar ->
                val left = slot * index + (slot - barWidth) / 2f
                val radius = CornerRadius(barWidth / 4f)
                if (bar.total <= 0.0) return@forEachIndexed

                val lightHeight = (chartHeight * (bar.light / maxTotal)).toFloat().coerceAtLeast(0f)
                val acHeight = (chartHeight * (bar.ac / maxTotal)).toFloat().coerceAtLeast(0f)
                val totalHeight = lightHeight + acHeight
                val top = chartHeight - totalHeight
                val alpha = if (bar.estimated) 0.5f else 1f
                val isSelected = bar.day == selected

                // 下面那段：照明
                if (lightHeight > 0f) {
                    drawRoundRect(
                        color = lightColor,
                        topLeft = Offset(left, chartHeight - lightHeight),
                        size = Size(barWidth, lightHeight + radius.y),
                        cornerRadius = radius,
                        alpha = alpha,
                    )
                }
                // 上面那段：空调（压在照明上面）
                if (acHeight > 0f) {
                    drawRect(
                        color = acColor,
                        topLeft = Offset(left, top),
                        size = Size(barWidth, acHeight),
                        alpha = alpha,
                    )
                }
                // 柱顶盖一个圆角，整根柱子看起来才是完整的
                drawRoundRect(
                    color = if (acHeight > 0f) acColor else lightColor,
                    topLeft = Offset(left, top),
                    size = Size(barWidth, radius.y * 2),
                    cornerRadius = radius,
                    alpha = alpha,
                )

                if (isSelected) {
                    drawRoundRect(
                        color = selectionColor,
                        topLeft = Offset(left - 2f, top - 2f),
                        size = Size(barWidth + 4f, totalHeight + 4f),
                        cornerRadius = radius,
                        style = Stroke(width = 2.5f),
                    )
                } else if (bar.estimated) {
                    drawRoundRect(
                        color = lightColor,
                        topLeft = Offset(left, top),
                        size = Size(barWidth, totalHeight),
                        cornerRadius = radius,
                        style = Stroke(
                            width = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
                        ),
                    )
                }
            }
        }
    }
}

/**
 * 单个表计的余额折线。
 *
 * **一个表计一张图、各用各的量纲** —— 之前把照明和空调画在同一张图里，
 * 两者相差 80 度，各自的波动被压成直线，完全看不出走势。
 * 充值时余额往上跳，会打一个醒目的标记（那正是这张图最有价值的信息）。
 */
@Composable
fun MeterBalanceChart(
    series: List<Pair<Long, Double>>,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 130.dp,
) {
    val sorted = remember(series) { series.sortedBy { it.first } }
    val rechargeColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant

    if (sorted.size < 2) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = color)
            Text(
                text = "至少要有两次采样才能看出趋势（现在 ${sorted.size} 次）",
                style = MaterialTheme.typography.bodySmall,
                color = hintColor,
            )
        }
        return
    }

    val values = sorted.map { it.second }
    val rawMin = values.min()
    val rawMax = values.max()
    val span = rawMax - rawMin
    // 加一点上下留白：余额几乎不动时，线不会贴在边上，也不会因为太直看成 bug
    val pad = max(span * 0.15, 0.05)
    val viewMin = rawMin - pad
    val viewMax = rawMax + pad
    val change = values.last() - values.first()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = color,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "%.2f 度".format(values.last()),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = if (change <= -0.005) {
                "近期共减少 %.2f 度".format(-change)
            } else if (change >= 0.005) {
                "近期共增加 %.2f 度（充值）".format(change)
            } else {
                "近期基本没有变化"
            },
            style = MaterialTheme.typography.labelSmall,
            color = hintColor,
        )

        Canvas(Modifier.fillMaxWidth().height(height)) {
            val chartHeight = size.height - 4f
            val count = sorted.size
            fun xOf(index: Int) = if (count <= 1) 0f else size.width * index / (count - 1)
            fun yOf(value: Double) =
                chartHeight - ((value - viewMin) / (viewMax - viewMin)).toFloat() * chartHeight

            // 三条参考横线
            listOf(0f, 0.5f, 1f).forEach { fraction ->
                val y = chartHeight * fraction
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }

            val path = Path()
            sorted.forEachIndexed { index, (_, value) ->
                val x = xOf(index)
                val y = yOf(value)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = 3f))

            sorted.forEachIndexed { index, (_, value) ->
                drawCircle(color = color, radius = 3f, center = Offset(xOf(index), yOf(value)))
            }

            // 充值跳变：余额比上一次高的地方，画一个竖虚线 + 空心圈
            for (i in 1 until sorted.size) {
                if (sorted[i].second - sorted[i - 1].second > 0.01) {
                    val x = xOf(i)
                    drawLine(
                        color = rechargeColor,
                        start = Offset(x, 0f),
                        end = Offset(x, chartHeight),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                    )
                    drawCircle(
                        color = rechargeColor,
                        radius = 5f,
                        center = Offset(x, yOf(sorted[i].second)),
                        style = Stroke(width = 2.5f),
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth()) {
            Text(
                text = "低 %.2f".format(rawMin),
                style = MaterialTheme.typography.labelSmall,
                color = hintColor,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "高 %.2f".format(rawMax),
                style = MaterialTheme.typography.labelSmall,
                color = hintColor,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 横轴稀疏标签：只显示首、中、尾三个日期 */
@Composable
fun DateAxisLabels(days: List<LocalDate>, modifier: Modifier = Modifier) {
    if (days.isEmpty()) return
    val labels = listOf(days.first(), days[days.size / 2], days.last())
    Row(modifier.fillMaxWidth().padding(top = 4.dp)) {
        labels.forEachIndexed { index, day ->
            Text(
                text = day.format(AXIS_FMT),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = when (index) {
                    0 -> TextAlign.Start
                    labels.lastIndex -> TextAlign.End
                    else -> TextAlign.Center
                },
            )
        }
    }
}

/** 图例小方块 */
@Composable
fun LegendItem(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** 当天明细里用的一行（日期 → 照明/空调/合计/电费） */
fun usageDetailText(usage: DayUsage?, price: Double, day: LocalDate): String {
    val u = usage ?: DayUsage()
    val total = u.total
    return buildString {
        append("${day.format(AXIS_FMT)}：")
        append("照明 %.2f 度 ¥%.2f · ".format(u.light, u.light * price))
        append("空调 %.2f 度 ¥%.2f · ".format(u.ac, u.ac * price))
        append("合计 %.2f 度 ¥%.2f".format(total, total * price))
    }
}

/** 商户占比用的横条 */
@Composable
fun ShareBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
