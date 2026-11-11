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
 *  ▌大学物理                    ← 本节行（紧凑三行，字号大一号）
 *    南3 101
 *    10:00-11:35
 *  ▌程序设计基础                 ← 第二行 = 下一节（字号小一号）
 *    南3 202
 *    14:00-15:40
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
 * 两行直接复用 [Widgets.courseRow]（4×2 的行构建）；两行档位由
 * [WidgetSchedule.nextClassRowPair] 的**永不蒸发阶梯**决定
 * （满档竖排 / 紧凑三行 / 单行一行式——任何尺寸下两节课都至少以最低档存在）。
 * 紧凑三行档的地点恒单行，过长由 [WidgetSchedule.fitPlaceText] 先缩字号（下限 9dp）
 * 再裁左边保留尾部教室号；本节地点按 [WidgetSchedule.nextClassDetail] 收。
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

        /** 诊断页之外的 logcat 口径：判档输入与结果一行打全，远程排障对齐「小组件几何」卡片 */
        private fun logTier(
            heightDp: Int,
            chrome: Int,
            avail: Int,
            nat1: Int,
            nat2: Int,
            pair: Pair<WidgetSchedule.NextClassPreviewStyle, WidgetSchedule.NextClassPreviewStyle>,
        ) {
            Log.i(
                TAG,
                "2×2判档 画布=$heightDp chrome=$chrome 可用=$avail nat1=$nat1 nat2=$nat2 " +
                    "→ (行1=${pair.first}/行2=${pair.second})",
            )
        }

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
                    rowMode = prefs.widgetRowMode,
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
            /** 用户行样式偏好（WidgetSchedule.ROW_MODE_*）；判档时传给阶梯 */
            rowMode: String = WidgetSchedule.ROW_MODE_AUTO,
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
                // 紧凑三行（#13 七轮）：课名 13/12、小字 11/10，比六轮的 15/13 降一档，
                // 用省下的高度换「地点独立成行」；150dp 及以上用标准档，更矮才整组再降一档。
                val (n1, s1) = if (heightDp <= 0 || heightDp >= 150) 13 to 11 else 12 to 10
                val n2 = n1 - 1
                val s2 = s1 - 1
                // 账本里"列宽"口径是格子可容宽：卡片内边距 20 先去掉
                val columnWidthDp = (widthDp - 20).coerceAtLeast(0)

                // ── 各档**实测**自然高与行高（#13 四→七轮）────────────────────
                // 行高按当前系统字体现场量（RemoteViews 量不了，但渲染在本进程，
                // Paint 即真实字体；HyperOS=M 不同度量同口径），加余量吸收粗体/反锯齿零头。
                // 五轮起行高一律自然高 + padding 摊余，不再 setViewLayoutHeight
                // ——显式高度只要小于真实内容就裁字（rc1 病根）。
                // 七轮：紧凑档换三行（课名 1 行 + 地点 + 时间段），课名强制单行、
                // 行距 0、单边内边距 2dp、余量 1dp；地点缩字号只会让行更矮，账本按
                // 未缩的 lhS 记属于"多记"，方向安全。满档竖排仍只给拉高的 2×3。
                val lhN1 = Widgets.textLineHeightDp(context, n1, bold = true)
                val lhS1 = Widgets.textLineHeightDp(context, s1)
                val lhN2 = Widgets.textLineHeightDp(context, n2, bold = true)
                val lhS2 = Widgets.textLineHeightDp(context, s2)
                fun stackedNat(lines: Int, lhN: Int, lhS: Int): Int =
                    lines * lhN + (2 + lhS) + (2 + lhS) + 10 + 2 // 课名 + (距+地点) + (距+时间段) + 行内边距5+5 + 余量
                fun compactNat(lhN: Int, lhS: Int): Int =
                    lhN + lhS + lhS + WidgetSchedule.NEXT_CLASS_TRIPLE_PAD_DP * 2 + 1 // 课名1行 + 地点 + 时间段 + 上下内边距2+2 + 余量1
                fun miniNat(lhN: Int, lhS: Int): Int =
                    maxOf(lhN, lhS) + 10 + 2 // 课名与时间段同一行 + 行内边距5+5 + 余量
                // 固定开销也实测：内边距 20（setViewPadding 10×2）+ 表头行高（12dp 粗体）+ 行距 2
                val chrome = 20 + Widgets.textLineHeightDp(context, 12, bold = true) + 2
                val avail = WidgetSchedule.nextClassAvailableDp(heightDp, chrome)

                // 课名换行数按实测宽度估（列宽扣色条 3dp + 间距 8dp）；紧凑三行档课名强制单行，
                // 只有满档竖排仍按实测行数计高。地点适配再留 2dp 给 TextView 度量误差。
                val usableNameWidth = (columnWidthDp - 11).coerceAtLeast(0)
                val placeWidthDp = (usableNameWidth - 2).coerceAtLeast(1)
                fun nameLinesOf(name: String, sizeDp: Int): Int =
                    Widgets.textLineCount(context, name, sizeDp, bold = true, usableNameWidth).coerceIn(1, 2)

                // 阶梯不变：满/满只给高画布（2×3）；标准 2×2 从紧凑/紧凑起判，课永不蒸发。
                val naturals = WidgetSchedule.NextClassRowNaturals(
                    firstStacked = stackedNat(nameLinesOf(first.name, n1), lhN1, lhS1),
                    firstCompact = compactNat(lhN1, lhS1),
                    firstMini = miniNat(lhN1, lhS1),
                    secondStacked = stackedNat(
                        nameLinesOf(plan.nextCourse?.name.orEmpty(), n2),
                        lhN2,
                        lhS2,
                    ),
                    secondCompact = compactNat(lhN2, lhS2),
                    secondMini = miniNat(lhN2, lhS2),
                )
                val (firstStyle, secondStyle) =
                    WidgetSchedule.nextClassRowPair(avail, naturals, rowMode)
                val nat1 = WidgetSchedule.nextClassRowNatDp(naturals, firstStyle, first = true)
                val nat2 = WidgetSchedule.nextClassRowNatDp(naturals, secondStyle, first = false)
                logTier(heightDp, chrome, avail, nat1, nat2, firstStyle to secondStyle)

                // 余量按行分摊（各档自然高已吃实测，target ≥ 自然高才摊，cap 防巨大空框）；
                // 这些 target 进 courseRow 只做 padding stretch —— 行变大、内容居中不裁。
                val surplus = (avail - nat1 - nat2).coerceAtLeast(0)
                val row1H = nat1 + (if (nat1 + nat2 > 0) surplus * nat1 / (nat1 + nat2) else 0)
                    .coerceAtLeast(0).coerceAtMost(WidgetSchedule.MAX_STRETCH_DP)
                val row2H = nat2 + (if (nat1 + nat2 > 0) surplus * nat2 / (nat1 + nat2) else 0)
                    .coerceAtLeast(0).coerceAtMost(WidgetSchedule.MAX_STRETCH_DP)

                fun stretchedRow(
                    course: WidgetCourse,
                    targetH: Int,
                    naturalH: Int,
                    nameDp: Int,
                    subDp: Int,
                    stacked: Boolean,
                    mini: Boolean,
                    triple: Widgets.CourseRowTriple? = null,
                    rowDetail: WidgetSchedule.RowDetail = detail,
                ): RemoteViews {
                    return Widgets.courseRow(
                        context = context,
                        row = WidgetSchedule.toRow(course),
                        colors = colors,
                        dark = dark,
                        detail = rowDetail,
                        rowHeightDp = targetH.coerceAtLeast(naturalH), // padding 式 stretch：行只可能变大
                        nameSizeDp = nameDp,
                        subSizeDp = subDp,
                        naturalHeightDp = naturalH,
                        stacked = stacked,
                        mini = mini,
                        // 紧凑三行档 / MINI 都把课名收成单行（三行档账本按 1 行记）
                        nameMaxLines = if (mini || triple != null) 1 else 2,
                        triple = triple,
                    )
                }

                val compactTriple = Widgets.CourseRowTriple(
                    usableWidthDp = placeWidthDp,
                    minSizeDp = WidgetSchedule.NEXT_CLASS_PLACE_MIN_DP,
                )
                // 两行各按档位选版式：满档=竖排三行；紧凑=紧凑三行；MINI=单行一行式
                rv.addView(
                    R.id.next_rows,
                    stretchedRow(
                        first,
                        row1H,
                        nat1,
                        n1,
                        s1,
                        stacked = firstStyle == WidgetSchedule.NextClassPreviewStyle.STACKED,
                        mini = firstStyle == WidgetSchedule.NextClassPreviewStyle.MINI,
                        triple = if (firstStyle == WidgetSchedule.NextClassPreviewStyle.COMPACT) compactTriple else null,
                        rowDetail = if (firstStyle == WidgetSchedule.NextClassPreviewStyle.MINI) {
                            WidgetSchedule.RowDetail.NAME
                        } else {
                            detail
                        },
                    ),
                )
                plan.nextCourse?.let { next ->
                    rv.addView(
                        R.id.next_rows,
                        stretchedRow(
                            next,
                            row2H,
                            nat2,
                            n2,
                            s2,
                            stacked = secondStyle == WidgetSchedule.NextClassPreviewStyle.STACKED,
                            mini = secondStyle == WidgetSchedule.NextClassPreviewStyle.MINI,
                            triple = if (secondStyle == WidgetSchedule.NextClassPreviewStyle.COMPACT) compactTriple else null,
                            rowDetail = if (secondStyle == WidgetSchedule.NextClassPreviewStyle.MINI) {
                                WidgetSchedule.RowDetail.NAME
                            } else {
                                detail
                            },
                        ),
                    )
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
