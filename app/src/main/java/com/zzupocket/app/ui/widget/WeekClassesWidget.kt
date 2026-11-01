package com.zzupocket.app.ui.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.zzupocket.app.R
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.ui.nav.AppRoutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 「一周课表」桌面组件（4×5，按节次对齐、一天一列）。
 *
 * ```
 *                                              第 8 周
 *   一 11.2     二 11.3    今 11.4    四 11.5    五 11.6
 *  ▌高等数学A  ▌大学物理   （空）     ▌编译原理   ▌线性代数
 *    南2 206     南3 101                南4 103     南1 302
 *    8:00-9:40   10:00-11:35            10:10-…     14:00-…
 *  ▌体育       （跨节的课一格占多行）
 * ```
 *
 * ## 为什么是 4×5
 * 4 列宽在 4 列/5 列网格的启动器上都放得下（5 列宽的声明在 4 列网格上根本加不上）；
 * 5 行高 ≈ 一屏 55%，装得下一周 5 个大节 + 列头。真实画布运行时按
 * [Widgets.sizeDp] 的修正值读，列数/行高/字号全部自适应（拉到 4×6 照常用）。
 *
 * ## 与 4×2 的分工
 * 4×2 答"现在和明天怎么样"（列表式、字大）；这里答"这周整体怎么排"
 * （网格、按节次对齐、跨节的课一格占多行）。判断全在 [WidgetSchedule.weekPlan]
 * （纯函数、有单测），这里只负责画。
 */
class WeekClassesWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        refresh(context, appWidgetManager, appWidgetIds)
    }

    /** 拉大拉小：重新算列宽/行高/字号 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        refresh(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onDisabled(context: Context) {
        Widgets.onProviderDisabled(context, WeekClassesWidget::class.java)
    }

    private fun refresh(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val ctx = context.applicationContext
                val week = WidgetData.loadWeekSchedule()
                render(ctx, mgr, ids, week, WidgetData.prefs())
            } catch (t: Throwable) {
                Log.w(TAG, "刷新「一周课表」失败", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        private const val TAG = "WeekClassesWidget"

        fun render(
            context: Context,
            mgr: AppWidgetManager,
            ids: IntArray,
            bundle: WidgetData.WidgetWeekBundle,
            prefs: UiPrefs,
        ) {
            val colors = WidgetStyle.colors(context, prefs)
            val dark = WidgetStyle.isDark(context, prefs)
            val click = Widgets.openApp(context, AppRoutes.SCHEDULE)
            ids.forEach { id ->
                val (widthDp, heightDp) = Widgets.sizeDp(mgr, id)
                val plan = bundle.weekPlan()
                val rv = build(context, plan, colors, dark, click, widthDp, heightDp)
                runCatching { mgr.updateAppWidget(id, rv) }
            }
        }

        private fun build(
            context: Context,
            plan: WidgetSchedule.WidgetWeekPlan,
            colors: WidgetColors,
            dark: Boolean,
            click: android.app.PendingIntent,
            widthDp: Int,
            heightDp: Int,
        ): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_week_classes)
            WidgetStyle.tint(rv, R.id.card, colors.bg)
            // 卡片内边距 14→10（与 4×2 同款账本：WEEK_CHROME_DP 按 10dp 记）
            val pad10 = Widgets.dpToPx(context, 10)
            rv.setViewPadding(R.id.card, pad10, pad10, pad10, pad10)
            rv.setOnClickPendingIntent(R.id.widget_root, click)

            // 整块状态（假期 / 未同步课表）
            rv.setViewVisibility(R.id.card, if (plan.hasOverlay) View.GONE else View.VISIBLE)
            rv.setViewVisibility(R.id.container_overlay, if (plan.hasOverlay) View.VISIBLE else View.GONE)
            if (plan.hasOverlay) {
                rv.setTextViewText(R.id.overlay_title, plan.overlayTitle)
                rv.setTextColor(R.id.overlay_title, colors.text)
                rv.setTextViewText(R.id.overlay_message, plan.overlayMessage)
                rv.setTextColor(R.id.overlay_message, colors.dim)
                return rv
            }

            rv.setTextViewText(R.id.week, plan.week)
            rv.setTextColor(R.id.week, colors.dim)
            rv.setViewVisibility(R.id.week, if (plan.week.isBlank()) View.GONE else View.VISIBLE)

            val rowCount = plan.slotCount
            val rowHeightDp = WidgetSchedule.weekRowHeightDp(heightDp, rowCount)
            val columnWidthDp = if (plan.columns.isEmpty()) 0 else {
                ((widthDp - 20 - (plan.columns.size - 1) * 4) / plan.columns.size).coerceAtLeast(0)
            }
            val nameDp = WidgetSchedule.weekNameSizeDp(columnWidthDp)
            val nameLines = WidgetSchedule.weekNameMaxLines(columnWidthDp, rowHeightDp)
            val showPlace = WidgetSchedule.weekShowPlace(columnWidthDp, rowHeightDp)
            val showTime = WidgetSchedule.weekShowTime(columnWidthDp, rowHeightDp)

            rv.removeAllViews(R.id.week_columns)
            plan.columns.forEach { column ->
                val columnRv = RemoteViews(context.packageName, R.layout.widget_week_column)
                columnRv.setTextViewText(R.id.week_day_header, column.header)
                columnRv.setTextColor(
                    R.id.week_day_header,
                    if (column.isToday) colors.accent else colors.dim,
                )
                columnRv.removeAllViews(R.id.week_cells)

                column.cells.forEach { cell ->
                    when {
                        cell == null -> {
                            // 空槽（或被跨节格覆盖）：等高的隐形占位，行线才对得齐
                            val spacer = RemoteViews(context.packageName, R.layout.widget_week_cell)
                            spacer.setViewVisibility(R.id.week_cell_name, View.GONE)
                            spacer.setViewVisibility(R.id.week_cell_place, View.GONE)
                            spacer.setViewVisibility(R.id.week_cell_time, View.GONE)
                            spacer.setViewVisibility(R.id.week_cell_bar, View.GONE)
                            if (rowHeightDp > 0) {
                                spacer.setViewLayoutHeight(
                                    R.id.week_cell_root, rowHeightDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP,
                                )
                            }
                            columnRv.addView(R.id.week_cells, spacer)
                        }

                        else -> {
                            val cellRv = RemoteViews(context.packageName, R.layout.widget_week_cell)
                            cellRv.setTextViewText(R.id.week_cell_name, cell.name)
                            cellRv.setTextViewText(R.id.week_cell_place, cell.place)
                            cellRv.setTextViewText(R.id.week_cell_time, cell.timeRange)
                            cellRv.setTextViewTextSize(
                                R.id.week_cell_name, TypedValue.COMPLEX_UNIT_DIP, nameDp.toFloat(),
                            )
                            cellRv.setTextViewTextSize(
                                R.id.week_cell_place, TypedValue.COMPLEX_UNIT_DIP, (nameDp - 1).toFloat(),
                            )
                            cellRv.setTextViewTextSize(
                                R.id.week_cell_time, TypedValue.COMPLEX_UNIT_DIP, (nameDp - 1.5f),
                            )
                            cellRv.setInt(R.id.week_cell_name, "setMaxLines", nameLines)
                            cellRv.setViewVisibility(
                                R.id.week_cell_place,
                                if (showPlace && cell.place.isNotBlank()) View.VISIBLE else View.GONE,
                            )
                            cellRv.setViewVisibility(
                                R.id.week_cell_time,
                                if (showTime) View.VISIBLE else View.GONE,
                            )
                            cellRv.setTextColor(R.id.week_cell_name, colors.text)
                            cellRv.setTextColor(R.id.week_cell_place, colors.hint)
                            cellRv.setTextColor(R.id.week_cell_time, colors.dim)
                            WidgetStyle.tint(
                                cellRv,
                                R.id.week_cell_bar,
                                WidgetStyle.courseColor(cell.code, colors, dark),
                            )
                            // 格高 = 行高 × 占槽数（setViewLayoutHeight 是 API31 正经 API）
                            if (rowHeightDp > 0) {
                                cellRv.setViewLayoutHeight(
                                    R.id.week_cell_root,
                                    (rowHeightDp * cell.slotSpan).coerceAtLeast(28).toFloat(),
                                    TypedValue.COMPLEX_UNIT_DIP,
                                )
                            }
                            columnRv.addView(R.id.week_cells, cellRv)
                        }
                    }
                }
                rv.addView(R.id.week_columns, columnRv)
            }

            // 周末条（恒显示）：今天那行用主题色、有课用正文色、没课用弱色
            rv.removeAllViews(R.id.weekend_strip)
            plan.weekendLines.forEach { line ->
                val lineRv = RemoteViews(context.packageName, R.layout.widget_week_line)
                lineRv.setTextViewText(R.id.week_line, line.text)
                lineRv.setTextColor(
                    R.id.week_line,
                    when {
                        line.isToday -> colors.accent
                        line.hasCourse -> colors.text
                        else -> colors.dim
                    },
                )
                rv.addView(R.id.weekend_strip, lineRv)
            }
            return rv
        }
    }
}
