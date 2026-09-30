package com.zzupocket.app.ui.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.zzupocket.app.domain.FocusHeatmap
import com.zzupocket.app.domain.FocusStats
import java.time.LocalDate

/**
 * 打卡热力图：一列一周、周一在上，横滑 53 周。
 *
 * 底色常驻、强度用主题色叠加 —— 没学的那天也留一块底色，
 * 这样才看得出"这个月基本没动"和"这个月天天都有"的差别。
 */
@Composable
fun FocusHeatmap(
    secondsByDay: Map<LocalDate, Int>,
    today: LocalDate,
    modifier: Modifier = Modifier,
    weeks: Int = FocusHeatmap.DEFAULT_WEEKS,
    selectedDay: LocalDate? = null,
    onDayClick: ((LocalDate) -> Unit)? = null,
) {
    val labelStyle = MaterialTheme.typography.labelSmall
    val cell = with(LocalDensity.current) { labelStyle.fontSize.toDp() * 1.5f }
    val gap = cell / 6
    val headerHeight = cell * 1.2f
    val shape = RoundedCornerShape(cell / 5)
    val baseColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    val overlayColor = MaterialTheme.colorScheme.primary
    val todayRing = MaterialTheme.colorScheme.outline
    val selectedRing = MaterialTheme.colorScheme.primary

    val grid = remember(today, weeks) { FocusHeatmap.build(today, weeks) }
    // 打开时直接停在最近的几周，不用手动滑半天
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (grid.size - 5).coerceAtLeast(0),
    )

    Row(modifier) {
        Column(
            modifier = Modifier.padding(top = headerHeight),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            listOf("一", "", "三", "", "五", "", "日").forEach { label ->
                Box(Modifier.height(cell), contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        style = labelStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            itemsIndexed(grid) { _, week ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.height(headerHeight), contentAlignment = Alignment.Center) {
                        FocusHeatmap.monthLabel(week)?.let { label ->
                            Text(
                                text = label,
                                style = labelStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        week.days.forEach { day ->
                            if (day == null) {
                                // 未来的日子：同样大的透明占位，不然最后一列会矮一截
                                Box(Modifier.size(cell))
                            } else {
                                val tier = FocusStats.heatTier(secondsByDay[day] ?: 0)
                                val ring = when {
                                    day == selectedDay -> selectedRing
                                    day == today -> todayRing
                                    else -> null
                                }
                                Box(
                                    Modifier
                                        .size(cell)
                                        .clip(shape)
                                        .background(baseColor)
                                        .then(
                                            if (tier > 0) {
                                                Modifier.background(
                                                    overlayColor.copy(
                                                        alpha = (FocusHeatmap.intensity(tier) + 0.2f)
                                                            .coerceAtMost(1f),
                                                    ),
                                                )
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .then(
                                            if (ring != null) {
                                                Modifier.border(
                                                    width = if (day == selectedDay) 1.5.dp else 1.dp,
                                                    color = ring,
                                                    shape = shape,
                                                )
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .then(
                                            if (onDayClick != null) {
                                                Modifier.clickable { onDayClick(day) }
                                            } else {
                                                Modifier
                                            },
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 强度图例：少 ▢▢▢▢▢ 多 */
@Composable
fun HeatmapLegend(modifier: Modifier = Modifier) {
    val labelStyle = MaterialTheme.typography.labelSmall
    val cell = with(LocalDensity.current) { labelStyle.fontSize.toDp() * 1.5f }
    val gap = cell / 6
    val shape = RoundedCornerShape(cell / 5)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        Text("少", style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        (0..4).forEach { tier ->
            Box(
                Modifier
                    .size(cell)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                    .then(
                        if (tier > 0) {
                            Modifier.background(
                                MaterialTheme.colorScheme.primary.copy(
                                    alpha = (FocusHeatmap.intensity(tier) + 0.2f).coerceAtMost(1f),
                                ),
                            )
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        Text("多", style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.size(cell))
        Text(
            text = "1 格 ≈ 半小时",
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

