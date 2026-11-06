package com.zzupocket.app.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Context

/**
 * 「小组件几何」自体数据（诊断页用，2026-11-06 #13 五轮起）。
 *
 * 被动的根源一直是：适配判断需要的关键数据（启动器报的画布、我们实测的字体行高、
 * 用了哪个尺寸通道）只在发生问题的那台设备上，反馈只能靠用户截图反推。这里把
 * **自体数据一次收齐**，用户从诊断页截图就能定位三类问题——报数偏差 / 字体度量
 * 超纲 / 启动器对某 API 非标，不需要 adb、不需要下个版本试错。
 *
 * 只输出几何与度量，不含任何个人信息（课名/时刻表都不读）。
 */
object WidgetDiagnostics {

    private val providers = listOf(
        "下一节课" to NextClassWidget::class.java,
        "今日课表" to TodayClassesWidget::class.java,
        "周课表" to WeekClassesWidget::class.java,
        "电量与校园卡" to EnergyCardWidget::class.java,
    )

    fun collect(context: Context): String {
        val sb = StringBuilder()
        val cfg = context.resources.configuration
        val dm = context.resources.displayMetrics
        sb.appendLine("系统: fontScale=${cfg.fontScale} density=${dm.density} (${dm.widthPixels}x${dm.heightPixels}px)")
        // 渲染层用到的全部字号档位的实测行高（当前系统字体）
        sb.append(
            "实测行高(dp): " + listOf(10, 11, 12, 13, 14, 15).joinToString(", ") { size ->
                val reg = Widgets.textLineHeightDp(context, size)
                val bold = Widgets.textLineHeightDp(context, size, bold = true)
                "$size=$reg/${bold}粗"
            },
        )
        sb.appendLine()
        val mgr = AppWidgetManager.getInstance(context)
        if (mgr == null) {
            sb.appendLine("AppWidgetManager 不可用")
            return sb.toString()
        }
        var any = false
        for ((label, provider) in providers) {
            val ids = Widgets.idsOf(context, mgr, provider)
            if (ids.isEmpty()) continue
            any = true
            for (id in ids) {
                sb.appendLine("$label #$id:")
                val o = runCatching { mgr.getAppWidgetOptions(id) }.getOrNull()
                val minW = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: -1
                val maxH = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: -1
                val maxW = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) ?: -1
                val minH = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: -1
                sb.appendLine("  上报 MIN_WIDTH=$minW MAX_HEIGHT=$maxH MAX_WIDTH=$maxW MIN_HEIGHT=$minH")
                val sizes = runCatching {
                    @Suppress("DEPRECATION")
                    o?.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                }.getOrNull()
                if (sizes.isNullOrEmpty()) {
                    sb.appendLine("  SIZES: 未提供（走 MIN/MAX − ${Widgets.HEIGHT_OVERREPORT_DP} 修正）")
                } else {
                    val text = sizes.joinToString { "w=${it.width} h=${it.height}" }
                    sb.appendLine("  SIZES: $text")
                }
                val (w, h) = Widgets.sizeDp(mgr, id)
                sb.appendLine("  采用画布: ${w}x${h}dp")
            }
        }
        if (!any) sb.appendLine("（桌面上没有已添加的小组件）")
        return sb.toString()
    }
}
