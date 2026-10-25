package com.zzupocket.app.ui.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.zzupocket.app.MainActivity
import com.zzupocket.app.R
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.ui.nav.AppRoutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 两个课表小组件共用的东西：统一刷新入口、尺寸换算、点击打开 App、以及"该变的时候变一次"的闹钟。
 */
object Widgets {

    private const val TAG = "Widgets"

    /** 我们自己定的"刷新组件"广播（由下面的闹钟发出），不是系统的 APPWIDGET_UPDATE */
    const val ACTION_REFRESH = "com.zzupocket.app.action.REFRESH_WIDGETS"

    private const val REQUEST_REFRESH = 4200

    /**
     * 把桌面上所有组件全部重画一遍。
     *
     * 触发时机：
     *  · **打开 App 时**（`MainActivity`）—— 用户刚同步完课表、刚改完配色、刚查完余额，
     *    桌面应该立刻是新的；
     *  · **课程边界闹钟**（见 [scheduleNextBoundary]）—— 上课/下课的那一刻，
     *    让「下一节」正好在该变的时候变。
     *
     * 课表数据只读一次、几个组件共用，避免口径不一致（也少查一遍库）。
     * 一个组件都没加时直接返回，不做任何多余的事。
     */
    suspend fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        val mgr = AppWidgetManager.getInstance(appContext) ?: return
        val nextIds = idsOf(appContext, mgr, NextClassWidget::class.java)
        val todayIds = idsOf(appContext, mgr, TodayClassesWidget::class.java)
        val balanceIds = idsOf(appContext, mgr, EnergyCardWidget::class.java)
        if (nextIds.isEmpty() && todayIds.isEmpty() && balanceIds.isEmpty()) return

        withContext(Dispatchers.Default) {
            val prefs = WidgetData.prefs()

            if (nextIds.isNotEmpty() || todayIds.isNotEmpty()) {
                val schedule = runCatching { WidgetData.loadSchedule() }.getOrElse {
                    Log.w(TAG, "读取课表失败，本轮不刷新课表组件", it)
                    null
                }
                if (schedule != null) {
                    if (nextIds.isNotEmpty()) {
                        NextClassWidget.render(appContext, mgr, nextIds, schedule, prefs)
                    }
                    if (todayIds.isNotEmpty()) {
                        TodayClassesWidget.render(appContext, mgr, todayIds, schedule, prefs)
                    }
                    scheduleNextBoundary(appContext, schedule.nextBoundaryMillis)
                }
            }

            if (balanceIds.isNotEmpty()) {
                runCatching { WidgetData.loadBalance(prefs) }
                    .onSuccess { EnergyCardWidget.render(appContext, mgr, balanceIds, it, prefs) }
                    .onFailure { Log.w(TAG, "读取电量/余额失败，本轮不刷新该组件", it) }
            }
        }
    }

    /** 某个组件当前在桌面上有几个实例（返回的是 dp 无关的 id 列表） */
    fun idsOf(context: Context, mgr: AppWidgetManager, provider: Class<*>): IntArray =
        runCatching { mgr.getAppWidgetIds(ComponentName(context, provider)) }
            .getOrNull()
            ?: IntArray(0)

    /** dp → px（撑行高、缩字号时要用） */
    fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    /**
     * 画一行课程（4×2 与 2×2 共用同一套行，两个组件的观感才一致）。
     *
     * 一行 = 左侧课程色条 + 课名（大）+ 一行「地点（左）／时间段（右）」小字。
     * **不显示老师**（用户要求）。色条按课程代码取色（与 App 课表格子同一套算法）。
     *
     * @param rowHeightDp 这一行应该占的高度（dp，按实测画布与**实际行数**算）；
     *   行内容只有约 50dp 高，而 4×2 的实测画布有 195dp —— 不撑的话两行只占 100dp，
     *   下面近百 dp 全空（用户报的"下部分很空"）。
     */
    fun courseRow(
        context: Context,
        row: WidgetCourseRow,
        colors: WidgetColors,
        dark: Boolean,
        detail: WidgetSchedule.RowDetail,
        rowHeightDp: Int,
        nameSizeDp: Int,
        subSizeDp: Int,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_course_row)
        rv.setTextViewText(R.id.row_name, row.course.name)
        rv.setTextViewText(R.id.row_place, row.place)
        rv.setTextViewText(R.id.row_time, row.timeRange)
        // 字号按画布分档动态设（XML 里是标准档基线；矮盒子用紧凑档防裁字）
        rv.setTextViewTextSize(R.id.row_name, android.util.TypedValue.COMPLEX_UNIT_DIP, nameSizeDp.toFloat())
        rv.setTextViewTextSize(R.id.row_place, android.util.TypedValue.COMPLEX_UNIT_DIP, subSizeDp.toFloat())
        rv.setTextViewTextSize(R.id.row_time, android.util.TypedValue.COMPLEX_UNIT_DIP, subSizeDp.toFloat())

        rv.setTextColor(R.id.row_name, colors.text)
        rv.setTextColor(R.id.row_place, colors.hint)
        rv.setTextColor(R.id.row_time, colors.dim)
        rv.setViewVisibility(
            R.id.row_place,
            if (detail == WidgetSchedule.RowDetail.FULL && row.place.isNotBlank()) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            },
        )

        WidgetStyle.tint(rv, R.id.row_bar, WidgetStyle.courseColor(row.course.code, colors, dark))
        stretch(
            rv = rv,
            viewId = R.id.row_root,
            context = context,
            targetHeightDp = rowHeightDp,
            naturalHeightDp = NATURAL_COURSE_ROW_HEIGHT_DP,
            basePadTopDp = COURSE_ROW_PAD_DP,
            basePadBottomDp = COURSE_ROW_PAD_DP,
        )
        return rv
    }

    /** widget_course_row.xml 里原本的上下内边距（撑高时要把它加回去，别把它覆盖没了） */
    private const val COURSE_ROW_PAD_DP = 6

    /**
     * 把一块内容"撑"到指定高度：靠**加内边距**，不是靠 minHeight。
     *
     * ⚠️ 这里踩过一个坑，写下来免得再踩：
     * `setInt(viewId, "setMinimumHeight", px)` 是**反射调用**，而 Android 只允许反射调用
     * 带 `@RemotableViewMethod` 注解的方法（`View.setMinimumHeight` 没有这个注解），
     * 于是调用被静默丢掉 —— 实测块和行都没被撑开，底部照样空 30dp。
     * 而 `setBackgroundTintList`（着色用的）有注解，所以着色一直是好的。
     *
     * `RemoteViews.setViewPadding` 是 API 31 起的**正经 API**（不走反射），所以可靠；
     * 它一次设置四个边，所以左边右边要按布局原值传进去。
     *
     * @param naturalHeightDp 不撑时这块内容自然有多高（内容 + 布局里原有的上下内边距）
     */
    fun stretch(
        rv: RemoteViews,
        viewId: Int,
        context: Context,
        targetHeightDp: Int,
        naturalHeightDp: Int,
        basePadTopDp: Int = 0,
        basePadBottomDp: Int = 0,
        basePadHorizontalDp: Int = 0,
    ) {
        if (targetHeightDp <= 0) return
        val extra = (targetHeightDp - naturalHeightDp) / 2
        if (extra <= 0) return
        rv.setViewPadding(
            viewId,
            dpToPx(context, basePadHorizontalDp),
            dpToPx(context, basePadTopDp + extra),
            dpToPx(context, basePadHorizontalDp),
            dpToPx(context, basePadBottomDp + extra),
        )
    }

    /** 课程行不撑时的自然高度：内容 33.5dp + 布局里的上下内边距 8+8dp（见 widget_course_row.xml） */
    const val NATURAL_COURSE_ROW_HEIGHT_DP = 46

    /** 数据块不撑时的自然高度：块名行 16 + 间距 2 + 数字行 33 ≈ 51dp（见 widget_energy_card.xml） */
    const val NATURAL_BLOCK_HEIGHT_DP = 51

    /**
     * 组件现在的实际尺寸（dp）。
     *
     * ## ⚠️ 实测：`AppWidgetManager` 报的高度**比真实画布大 ~30dp**
     * 把桌面的 view tree 逐层挖出来对比过（4×2，MEIZU 21 Note / Android 16）：
     *
     * ```
     * AppWidgetHostView               211.7dp   ← 启动器给的外框
     *   FrameLayout                    201.8dp
     *     我们的根容器（LinearLayout）   164.6dp   ← **真正能用的画布**
     * ```
     * 而 `OPTION_APPWIDGET_MIN_WIDTH / MAX_HEIGHT` 报的是 **342×195**。
     * 也就是说高度多报了 30dp（宽度也差 5dp 左右）—— 按 195 排版面、再加内边距去"撑满"，
     * 多出来的部分会被父容器直接截掉，表现就是"怎么调都不动"。
     *
     * 所以这里返回**修正过的真实高度**（减掉 [HEIGHT_OVERREPORT_DP]）。宁可算小一点：
     * 算小了只是底部留几 dp 余量，算大了会裁字。
     *
     * 宽度用 `MIN_WIDTH`（竖屏下它就是真实宽度），高度用 `MAX_HEIGHT` 再修正。
     * 组件被拉大拉小时系统会回调 `onAppWidgetOptionsChanged`，据此重新算行数与字号。
     *
     * 这行日志把原始值和修正值都写进 logcat（一次刷新每个组件一行，开销可忽略），
     * 以后遇到"声明值 ≠ 真实值"这类问题不用再靠反推。
     */
    fun sizeDp(mgr: AppWidgetManager, appWidgetId: Int): Pair<Int, Int> {
        val o = runCatching { mgr.getAppWidgetOptions(appWidgetId) }.getOrNull()
        val w = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val rawH = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
        val h = if (rawH > 0) (rawH - HEIGHT_OVERREPORT_DP).coerceAtLeast(1) else 0
        Log.i(TAG, "组件 id=$appWidgetId 画布 ${w}x${h}dp（options 报的是 ${w}x${rawH}，高度多报 $HEIGHT_OVERREPORT_DP）")
        return w to h
    }

    /** `AppWidgetManager` 报的高度比真实画布多出来的部分（实测值，见 [sizeDp]） */
    private const val HEIGHT_OVERREPORT_DP = 30

    /**
     * 点组件打开 App，并落到指定页面。
     *
     * ## ⚠️ 不同目标的 requestCode 必须不同
     * Android 判断两个 PendingIntent 是不是"同一个"，只看**组件、action、data、category**，
     * **不看 extras**。三个目标如果都用同一个 requestCode，系统会认为是同一个 PendingIntent，
     * 于是**后建的那个的 extras 会覆盖先建的** —— 表现就是"点哪个都跳同一个页面"，
     * 而且这种 bug 在代码里完全看不出来，只在真机上现形。
     * 所以这里按路由分配固定的 requestCode。
     *
     * @param route 目标路由（[AppRoutes]）；null / [AppRoutes.TODAY] 就是今天页
     */
    fun openApp(context: Context, route: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (route != null && route != AppRoutes.TODAY) {
                putExtra(AppRoutes.EXTRA_ROUTE, route)
            }
        }
        return PendingIntent.getActivity(
            context,
            requestCodeOf(route),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 路由 → PendingIntent 的 requestCode（见 [openApp] 的注释：必须互不相同） */
    private fun requestCodeOf(route: String?): Int = when (route) {
        null -> 0
        AppRoutes.TODAY -> 0
        AppRoutes.SCHEDULE -> 1
        AppRoutes.ELECTRICITY -> 2
        AppRoutes.CARD -> 3
        else -> 9
    }

    /**
     * 在下一个上下课时间点定一个闹钟，让组件"正好该变的时候"变一次。
     *
     * 为什么不能用 `updatePeriodMillis` 顶替：它的下限是 30 分钟，上课之后桌面会挂着
     * 半小时的「下一节 10:00」——显示错的比不显示更糟（这条口径从上一版组件沿用下来）。
     *
     * 用 `setAndAllowWhileIdle` 而不是 `setExactAndAllowWhileIdle`：后者要
     * SCHEDULE_EXACT_ALARM 权限（Android 12+ 还得用户去系统设置里点同意），
     * 而组件刷新差几分钟完全无所谓，不值得为它多要一个权限。
     *
     * @param atMillis 目标时刻；null 表示今天没有课了，撤销闹钟（交给系统的定时刷新）
     */
    fun scheduleNextBoundary(context: Context, atMillis: Long?) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = refreshIntent(context)
        runCatching { am.cancel(pi) }
        if (atMillis == null) return
        val triggerAt = atMillis + 1_000L
        if (triggerAt <= System.currentTimeMillis() + 5_000L) return
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }.onFailure { Log.w(TAG, "定课程边界闹钟失败", it) }
    }

    private fun cancelBoundary(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(refreshIntent(context)) }
    }

    private fun refreshIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetRefreshReceiver::class.java).setAction(ACTION_REFRESH)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_REFRESH,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 某个组件被删光了。
     *
     * 只有课表那两个组件会定"上下课边界闹钟"，所以**只有在另一个课表组件也没了的时候**
     * 才撤销 —— 否则用户删掉"今日课表"会连带把"下一节课"的自动刷新一起关掉。
     * （电量组件不用这个闹钟：它显示的是余额，什么时候刷新都一样。）
     */
    fun onProviderDisabled(context: Context, provider: Class<*>) {
        val appContext = context.applicationContext
        val mgr = AppWidgetManager.getInstance(appContext) ?: return
        val others = listOf(NextClassWidget::class.java, TodayClassesWidget::class.java)
            .filter { it != provider }
        val anyLeft = others.any { idsOf(appContext, mgr, it).isNotEmpty() }
        if (!anyLeft) cancelBoundary(appContext)
    }
}

/**
 * [Widgets.ACTION_REFRESH] 的接收者：课程边界闹钟到点了，重画一遍组件。
 *
 * 用 `goAsync()` 是因为 `onReceive` 一返回，进程随时可能被回收，
 * 而重画组件要读一次 Room；`goAsync` 相当于向系统声明"我还没干完"。
 */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Widgets.ACTION_REFRESH) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Widgets.refreshAll(context)
            } catch (t: Throwable) {
                Log.w("WidgetRefreshReceiver", "闹钟刷新组件失败", t)
            } finally {
                pending.finish()
            }
        }
    }
}
