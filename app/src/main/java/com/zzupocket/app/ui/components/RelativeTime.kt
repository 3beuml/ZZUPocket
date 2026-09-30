package com.zzupocket.app.ui.components

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "3 小时前" 这类相对时间。
 *
 * 采样时间这类信息写"距上次采样 3 小时"比写绝对时间更有用 ——
 * 用户关心的是"数据新不新"。
 */
object RelativeTime {

    private val HM = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val FULL = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())

    fun ago(ts: Long, now: Long = System.currentTimeMillis()): String {
        if (ts <= 0L) return "未知"
        val diff = (now - ts).coerceAtLeast(0L)
        val minutes = diff / 60_000L
        val hours = diff / 3_600_000L
        val days = diff / 86_400_000L
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "$minutes 分钟前"
            hours < 24 -> "$hours 小时前"
            days < 7 -> "$days 天前"
            else -> FULL.format(Date(ts))
        }
    }

    /** "12:40" */
    fun timeOfDay(ts: Long): String = if (ts <= 0L) "—" else HM.format(Date(ts))
}
