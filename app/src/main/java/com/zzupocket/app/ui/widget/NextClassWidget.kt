package com.zzupocket.app.ui.widget

import android.app.PendingIntent
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
 * 「下一节课」桌面组件（2×2）—— 只回答一个问题：**我下一节是什么**。
 *
 * ```
 *   今天 · 第 4 周              ╭──╮
 *  ▓ 大学物理                   │ 3│   ← 圆气泡 = 这节课以及之后还有几节（含本节）
 *    10:00 - 11:35              ╰──╯
 *    南3 101
 *    → 14:00 程序设计基础
 * ```
 *
 * 三种形态（判断全在 [WidgetSchedule.nextClassPlan]，纯函数、有单测）：
 *  1. 今天还有没下课的课 → 第一节大字 + 「今天 · 第 N 周」；
 *  2. 今天上完了 / 今天本来没课，但明天有 → 顶上明天的第一节，表头「明日预告 · 第 N 周」；
 *  3. 今明都没有 → 居中一句「无课程」；假期 / 还没同步分别是「假期中」「还没有课表数据」。
 *
 * ⚠️ **别再把它改成"一行一节的列表"**：中间那版那么做过，真机上被否掉 ——
 * 今天上完、明天又没课时整个内容区只剩一句状态，看着像坏了；
 * 而且列表版把 4×2 的「明天的课看右边」文案带进了 2×2（2×2 没有右边）。
 *
 * 课名/时间段/地点/预告按盒子高度从下往上收（[WidgetSchedule.nextClassDetail] /
 * [WidgetSchedule.nextClassPreviews]），矮盒子里也不会被裁成半截字。
 * 一块 = 左侧课程色条 + 课名 + 完整时间段 + 地点，**不显示老师**（用户要求）。
 */
class NextClassWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        refresh(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        refresh(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    /** 桌面上最后一个本组件被删掉：如果别的课表组件也没了，就撤掉共用的那个闹钟 */
    override fun onDisabled(context: Context) {
        Widgets.onProviderDisabled(context, NextClassWidget::class.java)
    }

    private fun refresh(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        // onUpdate 是主线程回调，而且函数一返回进程随时可能被回收：
        // 读库要挪到别的线程，同时用 goAsync() 向系统声明"还没干完"
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val ctx = context.applicationContext
                val schedule = WidgetData.loadSchedule()
                render(ctx, mgr, ids, schedule, WidgetData.prefs())
                Widgets.scheduleNextBoundary(ctx, schedule.nextBoundaryMillis)
            } catch (t: Throwable) {
                // 组件刷新失败不该让用户看到崩溃，桌面保持上一次的内容即可
                Log.w(TAG, "刷新「下一节课」失败", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        private const val TAG = "NextClassWidget"

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
                val (_, heightDp) = Widgets.sizeDp(mgr, id)
                val plan = schedule.nextClassPlan(WidgetSchedule.nextClassPreviews(heightDp))
                val rv = build(
                    context = context,
                    plan = plan,
                    colors = colors,
                    dark = dark,
                    click = click,
                    detail = WidgetSchedule.nextClassDetail(heightDp),
                )
                runCatching { mgr.updateAppWidget(id, rv) }
            }
        }

        private fun build(
            context: Context,
            plan: WidgetNextClassPlan,
            colors: WidgetColors,
            dark: Boolean,
            click: PendingIntent,
            detail: WidgetSchedule.RowDetail,
        ): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_next_class)
            WidgetStyle.tint(rv, R.id.card, colors.bg)
            rv.setOnClickPendingIntent(R.id.widget_root, click)

            rv.setTextColor(R.id.header, colors.text)
            rv.setTextColor(R.id.bubble, colors.accent)
            rv.setTextColor(R.id.tv_course_name, colors.text)
            rv.setTextColor(R.id.tv_course_time, colors.dim)
            rv.setTextColor(R.id.tv_course_place, colors.hint)
            rv.setTextColor(R.id.preview, colors.hint)
            rv.setTextColor(R.id.status_title, colors.text)
            rv.setTextColor(R.id.status_message, colors.dim)

            rv.setTextViewText(R.id.header, plan.header)
            rv.setViewVisibility(R.id.header, if (plan.header.isBlank()) View.GONE else View.VISIBLE)

            // 假期：整块盖住（连表头一起换掉）
            rv.setViewVisibility(R.id.card, if (plan.isVacation) View.GONE else View.VISIBLE)
            rv.setViewVisibility(R.id.container_overlay, if (plan.isVacation) View.VISIBLE else View.GONE)
            if (plan.isVacation) {
                rv.setTextViewText(R.id.overlay_title, plan.statusTitle)
                rv.setTextViewText(R.id.overlay_message, plan.statusMessage)
                return rv
            }

            // ⚠️ 三种形态的可见性**每次都要显式写一遍**：桌面上的组件是拿新 RemoteViews
            // 重新应用的，不归零就会看到上一状态的残留部件（拾光专门有个 resetWidgetState 干这事）。
            //
            // 圆气泡在表头行里，**不属于** container_info / container_status 任何一边，
            // 所以必须单独写一次 —— 真机上就是这里漏了：状态态下气泡仍然挂着布局里的占位数字「3」。
            rv.setTextViewText(R.id.bubble, plan.remaining.toString())
            rv.setViewVisibility(
                R.id.bubble,
                if (plan.hasCourse && plan.remaining > 0) View.VISIBLE else View.GONE,
            )
            rv.setViewVisibility(R.id.container_info, if (plan.hasCourse) View.VISIBLE else View.GONE)
            rv.setViewVisibility(R.id.container_status, if (plan.isStatus) View.VISIBLE else View.GONE)

            if (plan.hasCourse) {
                rv.setTextViewText(R.id.tv_course_name, plan.name)
                rv.setTextViewText(R.id.tv_course_time, plan.timeRange)
                rv.setTextViewText(R.id.tv_course_place, plan.place)

                // 气泡颜色跟着主色走（可见性在上面统一处理）
                WidgetStyle.tint(rv, R.id.bubble, colors.accent)
                rv.setTextColor(R.id.bubble, WidgetStyle.textOn(colors.accent))

                // 色条按课程代码取色（与 App 课表格子同一套算法）
                WidgetStyle.tint(rv, R.id.bar, WidgetStyle.courseColor(plan.code, colors, dark))

                // 矮盒子从下往上收：先收地点，再收预告
                val full = detail == WidgetSchedule.RowDetail.FULL
                rv.setViewVisibility(
                    R.id.tv_course_place,
                    if (full && plan.place.isNotBlank()) View.VISIBLE else View.GONE,
                )
                rv.setTextViewText(R.id.preview, plan.preview)
                rv.setViewVisibility(
                    R.id.preview,
                    if (full && plan.preview.isNotBlank()) View.VISIBLE else View.GONE,
                )
            } else {
                rv.setTextViewText(R.id.status_title, plan.statusTitle)
                rv.setTextViewText(R.id.status_message, plan.statusMessage)
                rv.setViewVisibility(
                    R.id.status_message,
                    if (plan.statusMessage.isBlank()) View.GONE else View.VISIBLE,
                )
            }
            return rv
        }
    }
}
