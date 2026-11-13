package com.zzupocket.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material3.Card
import com.zzupocket.app.ui.components.GlassCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 首页模块的定义与解析（issue #19：模块可隐藏、可调序）。
 *
 * 时间轴（[TODAY_MODULE_TIMELINE]）是首页的核心，**不可隐藏**但可以调序；
 * 其余模块都能隐藏——有同学根本用不到校园卡。
 */
object TodayModules {

    const val TODAY_MODULE_TIMELINE = "timeline"
    const val TODAY_MODULE_SCHEDULE = "customSchedule"
    const val TODAY_MODULE_LIBRARY = "library"
    const val TODAY_MODULE_ROOMS = "rooms"
    const val TODAY_MODULE_ELECTRICITY = "electricity"
    const val TODAY_MODULE_CARD = "card"

    /** 默认顺序（= 0.36.x 的固定版式） */
    val DEFAULT_ORDER = listOf(
        TODAY_MODULE_TIMELINE,
        TODAY_MODULE_SCHEDULE,
        TODAY_MODULE_LIBRARY,
        TODAY_MODULE_ROOMS,
        TODAY_MODULE_ELECTRICITY,
        TODAY_MODULE_CARD,
    )

    /** 展示名（自定义编辑器里用） */
    val LABELS = mapOf(
        TODAY_MODULE_TIMELINE to "今天的时间轴",
        TODAY_MODULE_SCHEDULE to "今天的日程（空时自动隐藏）",
        TODAY_MODULE_LIBRARY to "图书馆 · 座位预约",
        TODAY_MODULE_ROOMS to "空教室查询",
        TODAY_MODULE_ELECTRICITY to "宿舍电量",
        TODAY_MODULE_CARD to "校园卡",
    )

    /** 可隐藏的模块（时间轴除外） */
    val HIDEABLE = setOf(
        TODAY_MODULE_SCHEDULE,
        TODAY_MODULE_LIBRARY,
        TODAY_MODULE_ROOMS,
        TODAY_MODULE_ELECTRICITY,
        TODAY_MODULE_CARD,
    )

    /**
     * 把用户的自定义（顺序 CSV + 隐藏集合）解析成实际渲染顺序。
     * 规则：① 时间轴恒在最前不可隐藏；② 用户顺序里的未知 id 丢弃；
     * ③ 将来新版本新增的模块（不在用户顺序里）**追加到末尾**——升级不能把新功能藏没。
     */
    fun resolved(userOrder: List<String>, hidden: Set<String>): List<Pair<String, Boolean>> {
        val known = DEFAULT_ORDER.toSet()
        val ordered = (userOrder.filter { it in known } + DEFAULT_ORDER.filter { it !in userOrder })
            .distinct()
            .toMutableList()
        // 时间轴钉在最前：调序时它必须排第一（编辑器里不给它下移按钮）
        ordered.remove(TODAY_MODULE_TIMELINE)
        ordered.add(0, TODAY_MODULE_TIMELINE)
        // 时间轴不可隐藏：哪怕隐藏集合里混进了它，也强制可见
        return ordered.map { id -> id to (id == TODAY_MODULE_TIMELINE || id !in hidden) }
    }
}

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
    GlassCard(modifier = modifier.fillMaxWidth().clickable(onClick = onOpenSchedule)) {
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
