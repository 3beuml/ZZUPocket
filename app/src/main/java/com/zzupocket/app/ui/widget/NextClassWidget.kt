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
 *   今天 · 第 4 周
 *  ▌大学物理                    ← 本节行（4×2 同款 courseRow，字号大一号）
 *    南3 101        10:00-11:35
 *  ▌程序设计基础                 ← 第二行 = 下一节（字号小一号）
 *    南3 202        14:00-…
 * ```
 *
 * 三种形态（判断全在 [WidgetSchedule.nextClassPlan]，纯函数、有单测）：
 *  1. 今天还有没下课的课 → 头两节排两行 + 「今天 · 第 N 周」；
 *  2. 今天上完了 / 今天本来没课，但明天有 → 明天的头两节，表头「明日预告 · 第 N 周」；
 *  3. 今明都没有 → 居中一句「无课程」；假期 / 还没同步分别是「假期中」「还没有课表数据」。
 *
 * ⚠️ **别把它改成"整周多行列表"**：中间那版那么做过，真机上被否掉 ——
 * 今天上完、明天又没课时整个内容区只剩一句状态，看着像坏了；
 * 而且列表版把 4×2 的「明天的课看右边」文案带进了 2×2（2×2 没有右边）。
 * （2026-11-04 第二轮：用户要求两节课都用 4×2 的「色条 + 文字」行样式、圆气泡删除——
 * 已照办，这里只排到下一节为止，再多就是 4×2 的活。）
 *
 * 两行直接复用 [Widgets.courseRow]（4×2 的行构建），字号比 4×2 抬一档（2×2 只两行）；
 * 第二行按 [WidgetSchedule.nextClassSecondRow] 档位降级（满档竖排 / 紧凑单行 / 收 0），
 * 本节地点按
 * [WidgetSchedule.nextClassDetail] 收，矮盒子里不会被裁成半截字。
 * **不显示老师**（用户要求，App 内"格子里不显示老师"的约定延续到组件）。
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
                val (widthDp, heightDp) = Widgets.sizeDp(mgr, id)
                val plan = schedule.nextClassPlan()
                val rv = build(
                    context = context,
                    plan = plan,
                    colors = colors,
                    dark = dark,
                    click = click,
                    detail = WidgetSchedule.nextClassDetail(heightDp),
                    widthDp = widthDp,
                    heightDp = heightDp,
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
            widthDp: Int,
            heightDp: Int,
        ): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_next_class)
            WidgetStyle.tint(rv, R.id.card, colors.bg)
            // ⚠️ 卡片内边距 14→10（XML 是 14）：这 8dp 是 2×2 账本（NEXT_CLASS_CHROME_DP
            // =44 按 10dp 记）的一部分。竖排三行后两行课的自然高 59×2=118，
            // Meizu 实测画布 165dp 扣内边距 28 只剩 137dp 装不下；与 4×2 同招收
            // 内边距 14→10 后可用 121dp，两行竖排 118 恰好放下（2026-11-05 issue #13）。
            rv.setViewPadding(
                R.id.card,
                Widgets.dpToPx(context, 10),
                Widgets.dpToPx(context, 10),
                Widgets.dpToPx(context, 10),
                Widgets.dpToPx(context, 10),
            )
            rv.setOnClickPendingIntent(R.id.widget_root, click)

            rv.setTextColor(R.id.header, colors.text)
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

            // ⚠️ 三种形态的可见性**每次都要显式写一遍**（不归零会看到上一状态的残留部件）；
            // 行是 addView 进 @id/next_rows 的，所以每次先 removeAllViews 清场，
            // 状态态/低矮档才不会残留上一态的两行。
            rv.removeAllViews(R.id.next_rows)
            rv.setViewVisibility(R.id.container_status, if (plan.isStatus) View.VISIBLE else View.GONE)

            if (plan.hasCourse) {
                val first = checkNotNull(plan.firstCourse)
                // 2026-11-04 第二轮（用户要求）：两节都用「色条 + 文字」行、圆气泡删除。
                // 2×2 只两行，字号比 4×2 的 13/12 抬一档（本节 15/13，下一节 13/12）；
                // 矮盒子整组降一档。
                val (n1, s1) = if (heightDp <= 0 || heightDp >= 170) 15 to 13 else 14 to 12
                val n2 = n1 - 2
                val s2 = s1 - 1
                // 账本里"列宽"口径是格子可容宽：卡片内边距 20 先去掉
                val columnWidthDp = (widthDp - 20).coerceAtLeast(0)
                val firstLines = WidgetSchedule.nameLineCount(first.name, columnWidthDp, n1)

                // 第二行（下一节）的档位（nextClassSecondRow）：满档竖排三行 / 降档单行 /
                // 收 0。⚠️ 高度读不到（≤0）时是**画满**，不是不画—— 不让一个不可靠的
                // 读数把"下一节"整行藏掉（issue #13 HyperOS 实证的教训）。
                val second = plan.nextCourse?.let { next ->
                    val secondLines = WidgetSchedule.nameLineCount(next.name, columnWidthDp, n2)
                    (next to WidgetSchedule.nextClassSecondRow(heightDp, firstLines, secondLines))
                        .takeUnless { it.second == WidgetSchedule.NextClassPreviewStyle.HIDE }
                }

                // 可用高度 = 画布 - 内边距 20 - 表头 22 - 行距 2（nextClassAvailableDp）；
                // 有第二行就等分（单行独占），**并保证不低于各自的自然高**——硬塞低于
                // 自然高的显式高度就是裁字，宁可顶出底部也不裁（2026-11-01 的教训）。
                val available = WidgetSchedule.nextClassAvailableDp(heightDp)
                val nat1 = WidgetSchedule.stackedRowNaturalHeightDp(firstLines)
                val row1H = if (second != null) (available / 2 + available % 2).coerceAtLeast(nat1)
                else available.coerceAtLeast(nat1)
                val row2H = if (second != null) {
                    val secondStyle = second.second
                    val nat2 = when (secondStyle) {
                        WidgetSchedule.NextClassPreviewStyle.STACKED ->
                            WidgetSchedule.stackedRowNaturalHeightDp(
                                WidgetSchedule.nameLineCount(second.first.name, columnWidthDp, n2),
                            )
                        else -> WidgetSchedule.COURSE_ROW_HEIGHT_DP
                    }
                    (available - row1H).coerceAtLeast(nat2)
                } else 0

                // ⚠️ 行高**必须显式 setViewLayoutHeight**（周课表同款 API31 正经 API），
                // 不能只靠 courseRow 的"加内边距"式 stretch：那套的账本（naturalHeight）一旦
                // 算窄就会 extra≤0 跳过，内容全部顶到标题下面、下半留大片空白
                // （2026-11-04 桌面截图实证——单节课时整片空底）。row_root 本身带
                // gravity=center_vertical，显式高度直接让内容行内居中，不再依赖 padding。
                fun stretchedRow(
                    course: WidgetCourse,
                    targetH: Int,
                    nameDp: Int,
                    subDp: Int,
                    stacked: Boolean,
                    rowDetail: WidgetSchedule.RowDetail = detail,
                ): RemoteViews {
                    val row = Widgets.courseRow(
                        context = context,
                        row = WidgetSchedule.toRow(course),
                        colors = colors,
                        dark = dark,
                        detail = rowDetail,
                        rowHeightDp = 0, // 不用 padding 式 stretch，高度在下方显式设定
                        nameSizeDp = nameDp,
                        subSizeDp = subDp,
                        naturalHeightDp = if (stacked) {
                            WidgetSchedule.stackedRowNaturalHeightDp(
                                WidgetSchedule.nameLineCount(course.name, columnWidthDp, nameDp),
                            )
                        } else {
                            WidgetSchedule.rowNaturalHeightDp(
                                WidgetSchedule.nameLineCount(course.name, columnWidthDp, nameDp),
                            )
                        },
                        stacked = stacked,
                    )
                    if (targetH > 0) {
                        row.setViewLayoutHeight(
                            R.id.row_root, targetH.toFloat(), android.util.TypedValue.COMPLEX_UNIT_DIP,
                        )
                    }
                    return row
                }

                // 2×2 的两行一律用**竖排三行**行版式（课名 / 地点 / 时间段不挤一行，
                // issue #13 第三轮：HyperOS 上地点被省略号吃掉、时间段看不清）；
                // 第二行降档时例外——单行紧凑（课名+时间段，不画地点）。
                rv.addView(R.id.next_rows, stretchedRow(first, row1H, n1, s1, stacked = true))
                second?.let { (next, secondStyle) ->
                    when (secondStyle) {
                        WidgetSchedule.NextClassPreviewStyle.STACKED ->
                            rv.addView(R.id.next_rows, stretchedRow(next, row2H, n2, s2, stacked = true))
                        else ->
                            rv.addView(
                                R.id.next_rows,
                                stretchedRow(next, row2H, n2, s2, stacked = false, rowDetail = WidgetSchedule.RowDetail.NAME),
                            )
                    }
                }
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
