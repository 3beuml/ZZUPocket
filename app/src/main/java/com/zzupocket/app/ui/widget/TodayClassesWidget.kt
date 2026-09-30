package com.zzupocket.app.ui.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.zzupocket.app.R
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.ui.nav.AppRoutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 「今明课表」桌面组件（4×2，**左列今天、右列明天**）。
 *
 * ```
 *                                                        第 5 周
 *  今天 9.29 周二                    明天 9.30 周三
 *   剩余 2 节                         共有 3 节
 *  ▌大学物理                        ┊ ▌编译原理实验
 *    南3 101        10:00-11:35     ┊   中2414       08:00-09:40
 *  ────────────────────────────────┊──────────────────────────────
 *  ▌程序设计基础                    ┊ ▌线性代数
 *    南1 302        14:00-15:35     ┊   南2 206       16:00-17:35
 * ```
 *
 * ## 结构与逻辑都照拾光课程表的「近日课程4x2」(`DoubleDaysNativeRenderer`)
 *  · 左列 = 今天里**还没下课**的课（正在上的那节算在内），footer「剩余 N 节」；
 *  · 右列 = 明天**全部**（不看钟点），footer「共有 N 节」；
 *  · 某列没课 → 整列居中「无课程」；假期 → 整块换成「假期中」+「期待新学期」；
 *  · 一块 = 左侧课程色条 + **课名**（主角）+ 一行「地点（左）／时间段（右）」小字；
 *  · **不显示老师**（用户要求）。
 *  判断全在 [WidgetSchedule.twoDayPlan]（纯函数、有单测），这里只负责画。
 *
 * ## 盒子高度是"实测"的
 * provider 里声明的尺寸只是给启动器猜格子用的老提示；真实画布要读 `AppWidgetManager`
 * 的实测值（这台机器上 4×2 = 342×195dp，而官方老公式说是 250×110dp）。
 * 显示几行、每行撑多高，都按实测高度算（[WidgetSchedule.columnRows] / [courseRowHeightDp]），
 * 所以盒子里有多少空间就吃多少，不会"上挤下空"。
 */
class TodayClassesWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        refresh(context, appWidgetManager, appWidgetIds)
    }

    /** 拉大拉小：重新算能放几行、每行撑多高 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        refresh(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onDisabled(context: Context) {
        Widgets.onProviderDisabled(context, TodayClassesWidget::class.java)
    }

    private fun refresh(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val ctx = context.applicationContext
                val schedule = WidgetData.loadSchedule()
                render(ctx, mgr, ids, schedule, WidgetData.prefs())
                Widgets.scheduleNextBoundary(ctx, schedule.nextBoundaryMillis)
            } catch (t: Throwable) {
                Log.w(TAG, "刷新「今明课表」失败", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        private const val TAG = "TodayClassesWidget"

        fun render(
            context: Context,
            mgr: AppWidgetManager,
            ids: IntArray,
            schedule: WidgetScheduleBundle,
            prefs: UiPrefs,
        ) {
            val colors = WidgetStyle.colors(context, prefs)
            val dark = WidgetStyle.isDark(context, prefs)
            val click = Widgets.openApp(context, AppRoutes.SCHEDULE)
            ids.forEach { id ->
                val (widthDp, heightDp) = Widgets.sizeDp(mgr, id)
                val rows = WidgetSchedule.columnRows(heightDp)
                val detail = WidgetSchedule.rowDetail((widthDp - 28 - 17) / 2)   // 单列宽
                val plan = schedule.twoDayPlan(rows)
                val rv = build(context, plan, colors, dark, click, heightDp, detail)
                runCatching { mgr.updateAppWidget(id, rv) }
            }
        }

        private fun build(
            context: Context,
            plan: WidgetTwoDayPlan,
            colors: WidgetColors,
            dark: Boolean,
            click: android.app.PendingIntent,
            heightDp: Int,
            detail: WidgetSchedule.RowDetail,
        ): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_today_classes)
            WidgetStyle.tint(rv, R.id.card, colors.bg)
            rv.setOnClickPendingIntent(R.id.widget_root, click)

            // 整块状态（假期 / 还没同步过课表）：两列都不画，换成居中一句话
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

            bindColumn(
                context, rv, plan.today, colors, dark, heightDp, detail,
                headerId = R.id.today_header, footerId = R.id.today_footer,
                rowsId = R.id.today_rows, emptyId = R.id.today_empty,
            )
            bindColumn(
                context, rv, plan.tomorrow, colors, dark, heightDp, detail,
                headerId = R.id.tomorrow_header, footerId = R.id.tomorrow_footer,
                rowsId = R.id.tomorrow_rows, emptyId = R.id.tomorrow_empty,
            )
            return rv
        }

        /**
         * 画一列。
         *
         * ⚠️ 三种形态（列表 / 空态 / 页面其它部分）的可见性**每次都要显式写一遍**：
         * 桌面上的组件是拿新 RemoteViews 重新应用的，不归零就会看到上一状态的残留部件
         * （拾光那边专门有个 `resetWidgetState()` 干这事，就是踩过这个坑）。
         */
        private fun bindColumn(
            context: Context,
            rv: RemoteViews,
            column: WidgetDayPlan,
            colors: WidgetColors,
            dark: Boolean,
            heightDp: Int,
            detail: WidgetSchedule.RowDetail,
            headerId: Int,
            footerId: Int,
            rowsId: Int,
            emptyId: Int,
        ) {
            rv.setTextViewText(headerId, column.header)
            rv.setTextColor(headerId, colors.dim)

            rv.setTextViewText(footerId, column.footer)
            rv.setTextColor(footerId, colors.hint)
            rv.setViewVisibility(footerId, if (column.footer.isBlank()) View.GONE else View.VISIBLE)

            rv.removeAllViews(rowsId)
            if (column.rows.isEmpty()) {
                rv.setViewVisibility(rowsId, View.GONE)
                rv.setViewVisibility(emptyId, View.VISIBLE)
                rv.setTextViewText(emptyId, column.emptyText)
                rv.setTextColor(emptyId, colors.hint)
            } else {
                rv.setViewVisibility(rowsId, View.VISIBLE)
                rv.setViewVisibility(emptyId, View.GONE)
                // 行高按**这一列实际有几行**算：只有 2 节课时行高就按 2 算，
                // 否则底部会空出一整行的位置（实测踩过）
                val rowHeightDp = WidgetSchedule.courseRowHeightDp(heightDp, column.rows.size)
                column.rows.forEachIndexed { index, row ->
                    rv.addView(rowsId, Widgets.courseRow(context, row, colors, dark, detail, rowHeightDp))
                    // 行与行之间插一条 1dp 分隔线（与拾光一致）
                    if (index < column.rows.lastIndex) {
                        rv.addView(rowsId, RemoteViews(context.packageName, R.layout.widget_row_divider))
                    }
                }
            }
        }
    }
}
