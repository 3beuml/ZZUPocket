package com.zzupocket.app.ui.widget

import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.data.repo.ElectricityRepository
import com.zzupocket.app.domain.EnergySample
import com.zzupocket.app.domain.EnergyStats
import com.zzupocket.app.domain.CardStats
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime

/**
 * 小组件一次刷新要用到的全部课表数据。
 *
 * 打包成一个结构而不是让几个组件各查一遍库：它们看的是同一批数据，
 * 一次读到内存里，判断口径也不可能出现两边不一致。
 */
data class WidgetScheduleBundle(
    /** 库里到底有没有课表数据（区分"今天没课"和"还没同步过课表"） */
    val hasSchedule: Boolean,
    /** 今天属于第几教学周；假期/学期外为 null */
    val week: Int?,
    /** 明天属于第几教学周：周日跨周时「明日预告」表头要跟明天的课走 */
    val tomorrowWeek: Int? = null,
    /** 今天 / 明天（用日期拼「今天 9.29 周二」这种列头） */
    val todayDate: LocalDate,
    val tomorrowDate: LocalDate,
    /** 今天的课 */
    val today: List<WidgetCourse>,
    /** 明天的课 */
    val tomorrow: List<WidgetCourse>,
    /** 现在几点（当天第几分钟） */
    val nowMin: Int,
    /** 下一个上下课时间点（毫秒时间戳），用来定"该变的时候变一次"的闹钟 */
    val nextBoundaryMillis: Long?,
) {
    /** 4×2 课表：左列今天、右列明天（尺寸知道时按换行感知账本裁行，防最后一行被裁字） */
    fun twoDayPlan(
        maxRows: Int,
        columnWidthDp: Int = 0,
        columnHeightDp: Int = 0,
        rowStyle: String = "auto",
    ): WidgetTwoDayPlan =
        WidgetSchedule.twoDayPlan(
            hasSchedule = hasSchedule,
            week = week,
            todayDate = todayDate,
            tomorrowDate = tomorrowDate,
            today = today,
            tomorrow = tomorrow,
            nowMin = nowMin,
            maxRows = maxRows,
            columnWidthDp = columnWidthDp,
            columnHeightDp = columnHeightDp,
            rowStyle = rowStyle,
        )

    /** 2×2：两行 4×2 同款行样式（本节 + 下一节）；今天没了就顶明天的头两节 */
    fun nextClassPlan(): WidgetNextClassPlan = WidgetSchedule.nextClassPlan(
        hasSchedule = hasSchedule,
        week = week,
        today = today,
        tomorrow = tomorrow,
        nowMin = nowMin,
        tomorrowWeek = tomorrowWeek,
    )
}

/** 「电量与校园卡」组件要用的数据 */
data class WidgetBalanceBundle(
    val roomLabel: String,
    val lightKwh: Double?,
    val acKwh: Double?,
    /** 近 7 天日均用量（0 = 还没有样本） */
    val lightAvgDaily: Double,
    val acAvgDaily: Double,
    val lightAt: Long?,
    val acAt: Long?,
    val cardYuan: Double?,
    val cardAt: Long?,
    /** 校园卡今日消费；null = 本地还没有流水（从没同步过） */
    val cardTodaySpend: Double?,
    /** 校园卡本周消费与笔数；null = 本地还没有流水 */
    val cardWeekSpend: Double?,
    val cardWeekCount: Int?,
    val nowMillis: Long,
    /** 只有一路表计（亿达学生公寓那种整间一个表）→ 左半只显示一个「电量」块 */
    val singleMeter: Boolean = false,
) {
    fun plan(): WidgetBalancePlan = WidgetBalance.build(
        roomLabel = roomLabel,
        lightKwh = lightKwh,
        acKwh = acKwh,
        lightAvgDaily = lightAvgDaily,
        acAvgDaily = acAvgDaily,
        lightAt = lightAt,
        acAt = acAt,
        cardYuan = cardYuan,
        cardAt = cardAt,
        cardTodaySpend = cardTodaySpend,
        cardWeekSpend = cardWeekSpend,
        cardWeekCount = cardWeekCount,
        nowMillis = nowMillis,
        singleMeter = singleMeter,
    )
}

/**
 * 小组件的取数。
 *
 * 两条铁律（沿用上一版组件的口径，别"顺手优化"掉）：
 *
 *  1. **只读本地数据**（课表、电量读数、校园卡流水都在 Room；校园卡余额在上次查到的缓存里）。
 *     小组件里不打任何网络请求 —— 桌面是系统在后台唤醒我们去画的，在那里发请求
 *     等于让用户莫名其妙地耗电，而且网络一慢组件就白屏。拉数据交给 App 打开时
 *     （`Widgets.refreshAll`）和已有的定时任务，组件只负责把库里已有的东西画出来。
 *  2. 判"第几周、今天有哪些课"全部走 [com.zzupocket.app.data.repo.ScheduleRepository]，
 *     也就是和 App 课表页同一套口径（优先按真实日期匹配，学校调休自动正确）。
 */
object WidgetData {

    /** 读一次界面偏好（主题、深浅色、课程配色、房间名） */
    suspend fun prefs(): UiPrefs = runCatching { AppGraph.uiPrefsStore.flow.first() }
        .getOrDefault(UiPrefs())

    suspend fun loadSchedule(): WidgetScheduleBundle {
        val repo = AppGraph.scheduleRepository
        val prefsStore = AppGraph.uiPrefsStore
        val prefs = prefs()
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
        val nowMillis = System.currentTimeMillis()

        // 本科 + 研究生统一口径（研究生账号没有学期行，也照样能取到今天/明天的课）
        val hasSchedule = com.zzupocket.app.data.repo.TodayTimetable
            .hasAnySchedule(repo, prefsStore)
        val periods = repo.periods()
        val week = com.zzupocket.app.data.repo.TodayTimetable.currentWeekAt(repo, prefsStore, today)
        val tomorrowWeek = com.zzupocket.app.data.repo.TodayTimetable.currentWeekAt(repo, prefsStore, tomorrow)

        val todayCourses = com.zzupocket.app.data.repo.TodayTimetable
            .sessionsOn(repo, prefsStore, today)
            .mapNotNull {
                WidgetSchedule.toCourse(
                    it,
                    periods,
                    timeAsPeriod = prefs.widgetTimeAsPeriod,
                    hidePlace = !prefs.widgetShowPlace,
                    hideTime = !prefs.widgetShowTime,
                )
            }
            .sortedBy { it.startMin }

        val tomorrowCourses = com.zzupocket.app.data.repo.TodayTimetable
            .sessionsOn(repo, prefsStore, tomorrow)
            .mapNotNull {
                WidgetSchedule.toCourse(
                    it,
                    periods,
                    timeAsPeriod = prefs.widgetTimeAsPeriod,
                    hidePlace = !prefs.widgetShowPlace,
                    hideTime = !prefs.widgetShowTime,
                )
            }
            .sortedBy { it.startMin }

        return WidgetScheduleBundle(
            hasSchedule = hasSchedule,
            week = week,
            tomorrowWeek = tomorrowWeek,
            todayDate = today,
            tomorrowDate = tomorrow,
            today = todayCourses,
            tomorrow = tomorrowCourses,
            nowMin = nowMin,
            nextBoundaryMillis = WidgetSchedule.nextBoundaryMillis(todayCourses, nowMin, nowMillis),
        )
    }

    /** 「周课表」组件要用的数据：本周每天一列（周一..周日），只读本地 */
    data class WidgetWeekBundle(
        /** 库里到底有没有课表数据（区分"假期"和"还没同步过"） */
        val hasSchedule: Boolean,
        /** 本周是第几教学周；假期/学期外为 null */
        val week: Int?,
        /** 周一..周日（1..7）各自的课（已按节次排序） */
        val coursesByDay: Map<Int, List<WidgetCourse>>,
        /** 今天（决定"今"列头与高亮） */
        val today: LocalDate,
        /** 周末明细条开关（小组件设置页） */
        val showWeekendStrip: Boolean = true,
    ) {
        fun weekPlan(): WidgetSchedule.WidgetWeekPlan = WidgetSchedule.weekPlan(
            hasSchedule = hasSchedule,
            week = week,
            coursesByDay = coursesByDay,
            today = today,
            showWeekendStrip = showWeekendStrip,
        )
    }

    /**
     * 「周课表」组件要用的数据。
     *
     * ⚠️ 七天各查一次库——**只读本地**（组件铁律不变），courseSessionDao 有内存外的主键索引，
     * 7 次 bySemester 全量扫描的代价在桌面刷新场景可以忽略（几十毫秒级）。
     * 周一取自今天的 ISO 周（`previousOrSame(MONDAY)`，与教务 weekday 1..7 口径一致）。
     */
    suspend fun loadWeekSchedule(): WidgetWeekBundle {
        val repo = AppGraph.scheduleRepository
        val prefsStore = AppGraph.uiPrefsStore
        val prefs = prefs()
        val today = LocalDate.now()
        val monday = today.minusDays(((today.dayOfWeek.value + 6) % 7).toLong())
        val hasSchedule = com.zzupocket.app.data.repo.TodayTimetable
            .hasAnySchedule(repo, prefsStore)
        val periods = repo.periods()
        val coursesByDay = (1..7).associateWith { day ->
            val date = monday.plusDays((day - 1).toLong())
            com.zzupocket.app.data.repo.TodayTimetable
                .sessionsOn(repo, prefsStore, date)
                .mapNotNull {
                    WidgetSchedule.toCourse(
                        it,
                        periods,
                        timeAsPeriod = prefs.widgetTimeAsPeriod,
                        // 周课表格子里的地点/时刻有独立开关
                        hidePlace = !prefs.widgetWeekShowPlace,
                        hideTime = !prefs.widgetWeekShowTime,
                    )
                }
                .sortedBy { it.startUnit }
        }
        return WidgetWeekBundle(
            hasSchedule = hasSchedule,
            week = com.zzupocket.app.data.repo.TodayTimetable.currentWeekAt(repo, prefsStore, today),
            coursesByDay = coursesByDay,
            today = today,
            showWeekendStrip = prefs.widgetWeekendStrip,
        )
    }

    /**
     * 电量、可用天数与校园卡余额/消费。
     *
     * 全部来自本地：
     *  · 电表余额 = Room 里最近一条读数；
     *  · 「可用约 N 天」= 余额 ÷ 近 7 天日均，日均由本机采样算（和电费页同一套 `EnergyStats`）；
     *  · 校园卡余额 = 上次成功查询时的缓存（`CardBalanceStore`）；
     *  · 校园卡今日/本周消费 = 本地流水里算。
     * 这里**不发任何请求**。
     */
    suspend fun loadBalance(prefs: UiPrefs): WidgetBalanceBundle {
        val repo = AppGraph.electricityRepository
        val nowMillis = System.currentTimeMillis()
        val card = runCatching { AppGraph.cardBalanceStore.latest() }.getOrNull()
        val cardTotals = runCatching { cardTotals() }.getOrNull()

        val (lightRoom, acRoom) = runCatching { repo.effectiveRooms() }.getOrDefault("" to "")
        if (lightRoom.isBlank() && acRoom.isBlank()) {
            return WidgetBalanceBundle(
                roomLabel = (prefs.lightRoomLabel ?: prefs.acRoomLabel).orEmpty(),
                lightKwh = null, acKwh = null,
                lightAvgDaily = 0.0, acAvgDaily = 0.0,
                lightAt = null, acAt = null,
                cardYuan = card?.yuan, cardAt = card?.at,
                cardTodaySpend = cardTotals?.today, cardWeekSpend = cardTotals?.week,
                cardWeekCount = cardTotals?.weekCount,
                nowMillis = nowMillis,
            )
        }

        // 房间名：组件只有一行位置，所以先取照明那一路的名字（两路不同楼时电费页会标出来），
        // 其次用房表按**那一个**房间 ID 反查（得到 "柳园20号楼101" 这种）
        val label = (prefs.lightRoomLabel ?: prefs.acRoomLabel)?.takeIf { it.isNotBlank() }
            ?: runCatching { AppGraph.roomCatalog.labelForRoom(lightRoom.ifBlank { acRoom }) }
                .getOrNull().orEmpty()

        val light = if (lightRoom.isBlank()) null
        else runCatching { repo.latest(ElectricityRepository.ACCOUNT_TYPE_LIGHT, lightRoom) }.getOrNull()
        val ac = if (acRoom.isBlank()) null
        else runCatching { repo.latest(ElectricityRepository.ACCOUNT_TYPE_AC, acRoom) }.getOrNull()

        // 「可用约 N 天」的窗口与算法都在 EnergyStats 里（DAYS_LEFT_WINDOW_DAYS / recentDailyAverage），
        // App 电费页用的是同一个 —— 两处各写一份窗口正是"组件说 28 天、App 说 15.9 天"的来源。
        val since = EnergyStats.dayStart(
            LocalDate.now().minusDays((EnergyStats.DAYS_LEFT_WINDOW_DAYS - 1).toLong()),
        )
        val lightAvg = if (lightRoom.isBlank()) 0.0 else runCatching {
            EnergyStats.recentDailyAverage(
                repo.readingsSince(ElectricityRepository.ACCOUNT_TYPE_LIGHT, lightRoom, since)
                    .map { EnergySample(it.ts, it.kwh) },
            )
        }.getOrDefault(0.0)
        val acAvg = if (acRoom.isBlank()) 0.0 else runCatching {
            EnergyStats.recentDailyAverage(
                repo.readingsSince(ElectricityRepository.ACCOUNT_TYPE_AC, acRoom, since)
                    .map { EnergySample(it.ts, it.kwh) },
            )
        }.getOrDefault(0.0)

        return WidgetBalanceBundle(
            roomLabel = label,
            lightKwh = light?.kwh,
            acKwh = ac?.kwh,
            lightAvgDaily = lightAvg,
            acAvgDaily = acAvg,
            lightAt = light?.ts,
            acAt = ac?.ts,
            cardYuan = card?.yuan,
            cardAt = card?.at,
            cardTodaySpend = cardTotals?.today,
            cardWeekSpend = cardTotals?.week,
            cardWeekCount = cardTotals?.weekCount,
            nowMillis = nowMillis,
            // 只有一路设了房间号 = 整间一个表那种楼 → 组件也只显示一个「电量」
            singleMeter = (lightRoom.isNotBlank() && acRoom.isBlank()) ||
                (lightRoom.isBlank() && acRoom.isNotBlank()),
        )
    }

    /** 校园卡的今日/本周消费（元）与本周笔数 */
    private data class CardTotals(val today: Double, val week: Double, val weekCount: Int)

    /**
     * 校园卡今日/本周花了多少。本地流水里算，不联网。
     *
     * 一条流水都没有（从没同步过）时返回 null —— 那种情况下"今日 0.00"是**假信息**，
     * 界面上要说"未同步"而不是假装今天没花钱。
     */
    private suspend fun cardTotals(): CardTotals? {
        val all = AppGraph.cardRepository.all()
        if (all.isEmpty()) return null
        val today = CardStats.dayKey(System.currentTimeMillis())
        val weekStart = CardStats.weekStart(today)
        val spends = all.filter { it.isSpend }
        val todaySpend = spends.filter { CardStats.dayKey(it.ts) == today }.sumOf { -it.amount }
        val weekSpends = spends.filter { CardStats.dayKey(it.ts) >= weekStart }
        return CardTotals(
            today = todaySpend,
            week = weekSpends.sumOf { -it.amount },
            weekCount = weekSpends.size,
        )
    }
}
