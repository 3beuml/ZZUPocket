package com.zzupocket.app.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.ScheduleText
import com.zzupocket.app.core.WeekMath
import com.zzupocket.app.data.gms.GmsScheduleSource
import com.zzupocket.app.data.prefs.BlockBorder
import com.zzupocket.app.data.prefs.CourseColorMode
import com.zzupocket.app.data.prefs.CourseTextMode
import com.zzupocket.app.data.prefs.GridDensity
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.data.repo.ScheduleRepository
import com.zzupocket.app.domain.Course
import com.zzupocket.app.domain.FocusRecord
import com.zzupocket.app.domain.FocusStats
import com.zzupocket.app.domain.Period
import com.zzupocket.app.domain.Phase
import com.zzupocket.app.domain.Semester
import com.zzupocket.app.domain.Session
import com.zzupocket.app.domain.toSlotLines
import java.time.LocalDate
import java.time.LocalTime

private val CELL_HEIGHT = 54.dp
private val UNIT_COL_WIDTH = 56.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onOpenLogin: () -> Unit,
    onOpenOverview: (Int) -> Unit,
    onOpenDisplaySettings: () -> Unit,
    vm: ScheduleViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by AppGraph.uiPrefsStore.flow.collectAsStateWithLifecycle(initialValue = UiPrefs())
    var showSemesters by remember { mutableStateOf(false) }
    var showWeekPicker by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    Scaffold(
        // 壁纸画在外壳层，这里必须透明，否则页面自己的底色会把壁纸盖掉
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                // 左上角不显示"课表"二字，直接放学期切换；研究生视图显示研究生学期名
                title = {
                    if (state.gradMode) {
                        Text(
                            text = state.gmsSemester?.displayName ?: "研究生课表",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    } else {
                        SemesterButton(state.selectedSemester) { showSemesters = true }
                    }
                },
                actions = {
                    if (!state.gradMode) {
                        IconButton(onClick = { vm.openPlanEditor() }) {
                            Icon(Icons.Filled.Add, contentDescription = "添加日程")
                        }
                    }
                    IconButton(onClick = { if (state.gradMode) vm.loadGmsWeek(force = true) else vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新课表")
                    }
                    // 顶栏只留一个设置入口：教学周、显示、背景、入学年、课程总览都在里面
                    IconButton(onClick = onOpenDisplaySettings) {
                        Icon(Icons.Filled.Tune, contentDescription = "课程表设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 研究生账号在本科教务里本来就没有角色：那条 401/403 说明对他是噪音，
            // 而且与下面的「研究生课表」区块自相矛盾（一边说没权限、一边请他登录研究生系统）。
            // 切到研究生视图后不再显示这条错误；研究生链路的错误由 grad 区块自己的 gmsError 负责。
            state.error?.takeIf { !state.gradMode }?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // 缓存提示：一行小灰字，点了重试。刻意不用红色 —— 红色错误会让人以为账号出问题
            // （历史 issue #2 反复重登就是这么来的）。刷新成功且内容没变时这里是 null，什么都不显示。
            state.cacheNotice?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // 登录过期 → 点它去重新登录（重试是没用的，只会再失败一次）；
                            // 其他失败（网络/教务挂了）→ 重试
                            if (state.cacheNoticeNeedsLogin) {
                                vm.openGmsLogin()
                            } else if (state.gradMode) {
                                vm.loadGmsWeek(force = true)
                            } else {
                                vm.refresh()
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            state.notice?.let { msg ->
                Text(
                    text = "$msg（点一下收起）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.dismissNotice() }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            when {
                // 研究生：整学期数据已经映射成本科的 Session/Course，直接复用同一套周网格。
                // 没有数据时才退回下面的登录/空态区块。
                state.gradMode && state.gmsSessions.isNotEmpty() -> {
                    WeekHeader(
                        week = state.gmsWeekIndex,
                        total = state.gmsWeekCount,
                        onPick = { showWeekPicker = true },
                        onWallpaper = prefs.backgroundPath != null,
                        confirmed = state.weekConfirmed,
                        onOpenSettings = onOpenDisplaySettings,
                    )
                    WeekPager(
                        weeks = state.gmsWeeks,
                        weekIndex = state.gmsWeekIndex,
                        sessionsOf = state::gmsSessionsOf,
                        periods = state.gmsPeriods,
                        weekMondayOf = { week ->
                            state.weekStart
                                ?.let { WeekMath.dateOf(it, week, 1, state.weekStartDay) }
                                ?: ScheduleText.mondayOf(state.gmsSemester?.startDate, week)
                        },
                        onWeekSelected = vm::goToGmsWeek,
                        prefs = prefs,
                        onSessionClick = vm::onSessionClick,
                    )
                }

                // gradMode 优先：从 0.31.0 覆盖升级的账号可能已有学期缓存，weeks 非空；
                // 只看 weeks 会让研究生区块和底部兜底入口都被本科周表格挡住。
                state.gradMode || state.weeks.isEmpty() -> NoSemesterBlock(
                    loggedIn = state.loggedIn,
                    loading = state.loading,
                    gradMode = state.gradMode,
                    gmsLessons = state.gmsLessons,
                    gmsBusy = state.gmsBusy,
                    gmsError = state.gmsError,
                    gmsDialogOpen = state.gmsDialogOpen,
                    onLogin = onOpenLogin,
                    onRefresh = vm::refresh,
                    onGmsLogin = vm::openGmsLogin,
                    onGmsRefresh = { vm.loadGmsWeek(force = true) },
                    onGmsDialogDismiss = vm::closeGmsLogin,
                    onGmsAdopt = vm::adoptGmsSession,
                    onGradFallback = vm::useGradSchedule,
                )

                else -> {
                    WeekHeader(
                        week = state.weekIndex,
                        total = state.weekCount,
                        onPick = { showWeekPicker = true },
                        onWallpaper = prefs.backgroundPath != null,
                        confirmed = state.weekConfirmed,
                        onOpenSettings = onOpenDisplaySettings,
                    )
                    // 周次还没解析出来之前**不要挂翻页器**：它的初始页取自 state.weekIndex，
                    // 而 bootstrap 会先给 weeks（19 周）、后给真实周次，中间那一小段
                    // weekIndex 还是默认的 1 —— 于是翻页器从第 1 周挂载、再一路动画滚到本周，
                    // 动画每经过一页都会被写回 ViewModel（详见 WeekPager 的注释）。
                    // 等解析完再挂，初始页直接就是本周，没有"从第 1 周滚过来"这一出。
                    val weekReady = state.weekResolvedOn != null || !state.loading
                    if (weekReady) {
                        WeekPager(
                            weeks = state.weeks,
                            weekIndex = state.weekIndex,
                            sessionsOf = state::sessionsOf,
                            periods = state.periods,
                            weekMondayOf = { week ->
                                state.weekStart
                                    ?.let { WeekMath.dateOf(it, week, 1, state.weekStartDay) }
                                    ?: ScheduleText.mondayOf(state.selectedSemester?.startDate, week)
                            },
                            onWeekSelected = vm::goToWeek,
                            prefs = prefs,
                            // 点课程块 → 教务课开详情，自定义日程开编辑框
                            onSessionClick = vm::onSessionClick,
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }

    if (showSemesters) {
        SemesterSheet(
            semesters = state.semesters,
            selectedId = state.selectedSemester?.id,
            onPick = { vm.selectSemester(it); showSemesters = false },
            onDismiss = { showSemesters = false },
        )
    }

    if (showWeekPicker) {
        WeekPickerDialog(
            weeks = if (state.gradMode) state.gmsWeeks else state.weeks,
            current = if (state.gradMode) state.gmsWeekIndex else state.weekIndex,
            onPick = {
                if (state.gradMode) vm.goToGmsWeek(it) else vm.goToWeek(it)
                showWeekPicker = false
            },
            onDismiss = { showWeekPicker = false },
        )
    }

    state.detailCourse?.let { course ->
        CourseDetailSheet(
            course = course,
            onDismiss = vm::closeDetail,
            overrides = state.detailOverrides,
            onApplyEdits = vm::applyDisplayEdits,
            onResetOverrides = vm::resetDisplayOverrides,
        )
    }

    if (state.planEditorOpen) {
        PlanEditDialog(
            initial = state.editingPlan,
            semesterId = state.selectedSemester?.id ?: 0,
            availableWeeks = state.weeks.ifEmpty { (1..18).toList() },
            maxUnit = state.periods.size.coerceAtLeast(10),
            canStartFocus = true,
            currentWeek = state.weekIndex,
            onDismiss = vm::closePlanEditor,
            onSave = vm::savePlan,
            onDelete = { plan -> vm.deletePlan(plan.code) },
            onStartFocus = { plan ->
                vm.closePlanEditor()
                startFocusFor(name = plan.name, contextRef = "plan:${plan.code}")
            },
        )
    }

    if (state.pendingPlan != null) {
        PlanConflictDialog(
            conflicts = state.pendingConflicts,
            onConfirm = vm::confirmPendingPlan,
            onDismiss = vm::dismissPendingPlan,
        )
    }
}

/**
 * 从课表跳到番茄钟：标签自动用课名/日程名，并记下关联对象。
 * 这样统计里能算出"这门课实际专注了多久"。
 */
private fun startFocusFor(name: String, contextRef: String) {
    AppGraph.pomodoroEngine.start(
        phase = Phase.FOCUS,
        tagId = null,
        tagName = name,
        contextRef = contextRef,
    )
}

@Composable
private fun SemesterButton(semester: Semester?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        Text(
            text = semester?.displayName ?: "选择学期",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(Icons.Filled.ArrowDropDown, contentDescription = "切换学期")
    }
}

@Composable
private fun WeekHeader(
    week: Int,
    total: Int,
    onPick: () -> Unit,
    onWallpaper: Boolean = false,
    /** 周次参数是否已确认；false 时表头下面给一行小灰字提醒（不抢眼，但看得见） */
    confirmed: Boolean = true,
    onOpenSettings: (() -> Unit)? = null,
) {
    val shadow = wallpaperShadow(onWallpaper, MaterialTheme.colorScheme.surface)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(onClick = onPick),
            ) {
                // 只留周次。原来下面还有一行「9/21 - 9/27」的日期区间，用户要求删掉 ——
                // "这是哪几天"改由星期表头下面那行日期回答（每一列都有，翻到哪一周都看得出来）。
                // 也刻意不再显示「9/7 起」：用户觉得表头上挂基准日太啰嗦，基准改到设置页里看。
                Text(
                    text = "第 $week 周 / 共 $total 周",
                    style = MaterialTheme.typography.titleMedium.copy(shadow = shadow),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (!confirmed && onOpenSettings != null) {
            // 只在"周次是自动猜的、还没确认"时出现，确认后永久消失。
            // 用一行小灰字而不是按钮：它是个提醒，不该抢周次的视觉重量。
            Text(
                text = "周次为自动推断，可在课程表设置里确认",
                style = MaterialTheme.typography.labelSmall.copy(shadow = shadow),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** 壁纸开着时给直接压在照片上的字加一层极轻投影 —— 比给文字垫实色块自然得多 */
private fun wallpaperShadow(enabled: Boolean, surface: Color): Shadow? = if (enabled) {
    Shadow(
        color = surface.copy(alpha = 0.7f),
        offset = Offset(0f, 1.5f),
        blurRadius = 4f,
    )
} else {
    null
}

/** 星期表头行的高度：周几（约 16dp）+ 日期小字（约 12dp）+ 一点余量 */
private val WEEKDAY_HEADER_HEIGHT = 38.dp

@Composable
private fun WeekPager(
    weeks: List<Int>,
    weekIndex: Int,
    sessionsOf: (Int) -> List<Session>,
    periods: List<Period>,
    weekMondayOf: (Int) -> LocalDate?,
    onWeekSelected: (Int) -> Unit,
    prefs: UiPrefs,
    onSessionClick: (Session) -> Unit,
) {
    val initial = weeks.indexOf(weekIndex).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initial, pageCount = { weeks.size })

    // ① 用户滑动 → 通知 ViewModel。
    //
    // ⚠️ 两个要点，都是真机上踩出来的（课表页停在上一周）：
    //  · 只听 **settledPage**（松手落定后的页），不听 currentPage：后者在动画过程中
    //    会逐页变化，等于把动画的中间态写回 ViewModel；
    //  · **程序化滚动期间（isScrollInProgress）一律不写**：否则"滚到本周"的动画
    //    每经过一页就改一次 weekIndex，而 weekIndex 一变又会取消正在跑的动画
    //    （下面 ② 重新执行）—— 两个 effect 互相驱动，最后稳定停在中途某一周。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }
            .collect { (page, scrolling) ->
                if (scrolling) return@collect
                weeks.getOrNull(page)?.let { if (it != weekIndex) onWeekSelected(it) }
            }
    }
    // ② ViewModel 变化（点周次选择、隔天自动回到本周）→ 滚过去。
    //    这是单向的：①不会回写中间态，所以不会再打架。
    LaunchedEffect(weekIndex, weeks) {
        val idx = weeks.indexOf(weekIndex)
        if (idx >= 0 && idx != pagerState.currentPage) pagerState.animateScrollToPage(idx)
    }

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        val week = weeks.getOrNull(page)
        if (week != null) {
            ScheduleGrid(
                sessions = sessionsOf(week),
                periods = periods,
                prefs = prefs,
                // 星期表头下面那行日期要用它：算不出来（没学期/没起始日）就不显示日期
                weekMonday = weekMondayOf(week),
                onSessionClick = onSessionClick,
            )
        }
    }
}

@Composable
private fun ScheduleGrid(
    sessions: List<Session>,
    periods: List<Period>,
    prefs: UiPrefs,
    /** 这一周周一的日期；null = 算不出来（没选学期/没同步），表头就只显示周几 */
    weekMonday: java.time.LocalDate?,
    onSessionClick: (Session) -> Unit,
) {
    val days = 1..prefs.dayCount
    val cellH = prefs.cellHeightDp.dp
    val gap = if (prefs.blockBorder == BlockBorder.NONE) 0.75.dp else 1.dp
    val dark = isSystemInDarkTheme()
    val todayIso = LocalDate.now().toString()
    val today = LocalDate.now()
    val now = LocalTime.now()
    // 有壁纸时，星期表头和节次时间这些直接压在照片上的小字加一层轻投影
    val labelShadow = wallpaperShadow(
        prefs.backgroundPath != null,
        MaterialTheme.colorScheme.surface,
    )
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    // ⚠️ 表头**不放在可滚动区里**：往下翻课表时"周几 + 日期"要一直看得见
    // （用户要求固定住；它只是一行普通 Row，不吃横向拖动，所以左右翻周的手感不受影响）。
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(WEEKDAY_HEADER_HEIGHT)) {
            Spacer(Modifier.width(UNIT_COL_WIDTH))
            for (d in days) {
                val date = weekMonday?.plusDays((d - 1).toLong())
                val isToday = date != null && date == today
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = ScheduleText.weekdayName(d).removePrefix("周"),
                        style = MaterialTheme.typography.labelMedium.copy(shadow = labelShadow),
                        color = labelColor,
                    )
                    // 日期：10-01 这种写法（带月份、补零）。今天那一列用主色 + 加粗标出来
                    if (date != null) {
                        Text(
                            text = ScheduleText.dayLabel(date),
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            color = if (isToday) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                labelColor
                            },
                            style = LocalTextStyle.current.copy(shadow = labelShadow),
                        )
                    }
                }
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val gridLine = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            Row(
                modifier = Modifier.fillMaxWidth().drawBehind {
                    val unitW = UNIT_COL_WIDTH.toPx()
                    val rowH = cellH.toPx()
                    val gapPx = gap.toPx()
                    val stroke = 0.7.dp.toPx()
                    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                    // 横向虚线：每节课的上边界 + 最后一节的下边界
                    for (i in 0 until 10) {
                        val y = (rowH + gapPx) * i
                        drawLine(gridLine, Offset(0f, y), Offset(size.width, y), stroke, pathEffect = dash)
                    }
                    drawLine(
                        gridLine,
                        Offset(0f, size.height),
                        Offset(size.width, size.height),
                        stroke,
                        pathEffect = dash,
                    )
                    // 纵向虚线：时间列与各天之间
                    val dayCount = days.count()
                    val dayW = (size.width - unitW) / dayCount
                    for (i in 0..dayCount) {
                        val x = unitW + dayW * i
                        drawLine(gridLine, Offset(x, 0f), Offset(x, size.height), stroke, pathEffect = dash)
                    }
                },
            ) {
                // 左侧节次列：固定 10 格，与右侧各天严格对齐
                Column(
                    modifier = Modifier.width(UNIT_COL_WIDTH),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    for (unit in 1..10) {
                        Column(
                            modifier = Modifier.fillMaxWidth().height(cellH),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = "$unit",
                                style = MaterialTheme.typography.labelMedium.copy(shadow = labelShadow),
                            )
                            periods.firstOrNull { it.indexNo == unit }?.let {
                                // 起止放在同一行，避免行高不够时和时间数字叠在一起
                                Text(
                                    text = "${it.startText}-${it.endText}",
                                    fontSize = 7.sp,
                                    lineHeight = 9.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = labelColor,
                                    style = LocalTextStyle.current.copy(shadow = labelShadow),
                                )
                            }
                        }
                    }
                }

                // 每天一列 —— 只有按列排布才可能把跨节次的课画成一个连续的整块
                for (day in days) {
                    DayColumn(
                        modifier = Modifier.weight(1f),
                        day = day,
                        sessions = sessions,
                        periods = periods,
                        prefs = prefs,
                        cellH = cellH,
                        gap = gap,
                        dark = dark,
                        todayIso = todayIso,
                        now = now,
                        onSessionClick = onSessionClick,
                    )
                }
            }
        Spacer(Modifier.height(16.dp))
        } // 关闭可滚动区（表头在上面，不跟着滚）
    } // 关闭外层 Column
} // 关闭 ScheduleGrid

/**
 * 一天的列。
 *
 * 跨节次的课只画**一个**块：块高 = 节数 × 行高 + 被吃掉的间隙，
 * 并跳过被覆盖的格子。因为 `总高 = 行高 × 节数 + 间隙 × (格数 - 1)` 在合并前后相等，
 * 各天与左侧节次列仍然严格对齐。
 */
@Composable
private fun DayColumn(
    modifier: Modifier,
    day: Int,
    sessions: List<Session>,
    periods: List<Period>,
    prefs: UiPrefs,
    cellH: Dp,
    gap: Dp,
    dark: Boolean,
    todayIso: String,
    now: LocalTime,
    onSessionClick: (Session) -> Unit,
) {
    val corner = RoundedCornerShape(6.dp)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        var unit = 1
        while (unit <= 10) {
            val session = sessions.firstOrNull { it.weekday == day && unit in it.startUnit..it.endUnit }
            if (session == null) {
                Box(Modifier.fillMaxWidth().height(cellH))
                unit += 1
                continue
            }
            val span = (session.endUnit - session.startUnit + 1).coerceIn(1, 11 - unit)
            CourseBlock(
                session = session,
                span = span,
                height = cellH * span + gap * (span - 1),
                prefs = prefs,
                corner = corner,
                periods = periods,
                dark = dark,
                live = prefs.liveOutlineEnabled && isLiveNow(session, periods, todayIso, now),
                onClick = onSessionClick,
            )
            unit += span
        }
    }
}

@Composable
private fun CourseBlock(
    session: Session,
    span: Int,
    height: Dp,
    prefs: UiPrefs,
    corner: Shape,
    periods: List<Period>,
    dark: Boolean,
    live: Boolean,
    onClick: (Session) -> Unit,
) {
    val bg = when {
        // 自定义日程用用户自己挑的颜色，不受"课程配色"设置影响 ——
        // 挑完就得一直是他挑的那个色，不能被"单色模式"改掉
        session.source == ScheduleRepository.SOURCE_CUSTOM ->
            planBlockColor(session.color, dark)
        prefs.colorMode == CourseColorMode.SINGLE -> MaterialTheme.colorScheme.secondaryContainer
        else -> courseColor(session.courseCode, dark)
    }
    // 「正在上的课」描边（借鉴 Sked 的 liveCourseOutline）；否则按设置画实线/虚线/不画
    val decoration = when {
        live -> Modifier.border(prefs.liveOutlineWidth.dp, MaterialTheme.colorScheme.primary, corner)
        prefs.blockBorder == BlockBorder.SOLID ->
            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, corner)
        prefs.blockBorder == BlockBorder.DASHED ->
            Modifier.dashedBorder(MaterialTheme.colorScheme.outline)
        else -> Modifier
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(bg, corner)
            .then(decoration)
            .clickable { onClick(session) },
    ) {
        // 实验/见习标记：右上角小角标。以前用文字后缀「（实验）」，窄格里会把课名
        // 挤到截断（issue #6 的「微机原理及接口技术（实…」），角标不占文字区。
        if (session.experiment) {
            Text(
                text = "实",
                fontSize = 7.sp,
                lineHeight = 8.sp,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 1.dp, end = 1.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
                    .padding(horizontal = 2.dp),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 2.dp).align(Alignment.Center),
        ) {
            // 长课名（研究生常是班级名）自动小一号：行数不变、每行装得更多，
            // 比"放开行数撑爆格子"稳（issue #6）
            val nameFont = (prefs.courseFontSp + ScheduleText.fontAdjust(session.courseName))
                .coerceAtLeast(prefs.courseFontSp - 2)
            Text(
                text = session.courseName,
                fontSize = nameFont.sp,
                lineHeight = (nameFont + 2).sp,
                // 合并后的块更高可以多放几行；单节块 3 行也放得下
                // （紧凑 34.5dp + 副行 ≈ 44dp < 48dp；标准 39 + 10.5 < 54）
                maxLines = if (span >= 2) 4 else 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
            )
            val secondary = buildList {
                // 本科格子按作者的设计只放地点（教师名长、格子小，详情页能看到）。
                // 研究生课表用户要求看到主讲老师；单节课的块太矮，只在跨节次（span >= 2）时加，
                // 避免把课名挤掉。
                if (session.source == GmsScheduleSource.SOURCE && span >= 2) {
                    session.teacherName?.takeIf { it.isNotBlank() }
                        ?.let { add(SlotText.Kind.TEACHER to it) }
                }
                if (prefs.courseTextMode != CourseTextMode.NAME) {
                    add(SlotText.Kind.ROOM to ScheduleText.tidyPlace(session.roomText))
                }
            }
            secondary.forEachIndexed { index, (kind, line) ->
                if (line == null) return@forEachIndexed
                // 地点自适应（issue #6）：超长先缩一号、再「保头保尾」缩写（楼名…房号），
                // 行数按块高给（跨节 3 行、单节 1 行）；教师名维持单行。
                val text = if (kind == SlotText.Kind.ROOM) {
                    ScheduleText.abbreviatePlace(
                        line,
                        maxWeighted = if (span >= 2) 26 else 12,
                    ) ?: line
                } else {
                    line
                }
                val roomFont = if (kind == SlotText.Kind.ROOM) {
                    (prefs.roomFontSp + ScheduleText.fontAdjust(text))
                        .coerceAtLeast(prefs.roomFontSp - 1)
                } else {
                    prefs.roomFontSp
                }
                val maxLines = when {
                    kind != SlotText.Kind.ROOM -> 1
                    span >= 2 -> 3
                    else -> 1
                }
                Text(
                    text = text,
                    fontSize = roomFont.sp,
                    lineHeight = (roomFont + 1.5f).sp,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 副行的种类：教师 / 地点（地点才享受换行与缩写待遇） */
private object SlotText {
    enum class Kind { TEACHER, ROOM }
}

/**
 * 编辑框里的一个教室字段：同一时段里**教室相同**的教学周合成一组；
 * 单双周换教室（单周 302、双周 405）会拆成多组、各自带周次，
 * 覆盖键随之带上周次，互不串扰。[wholeSlot] = 该时段只有一种教室 → 一条覆盖管全部周。
 */
internal data class RoomEditSlot(
    val weekday: Int,
    val startUnit: Int,
    val endUnit: Int,
    val room: String?,
    val weeks: List<Int>,
    val wholeSlot: Boolean,
) {
    /** 与 [DisplayOverridesView.rooms] / [ScheduleViewModel.applyDisplayEdits] 约定的键 */
    val editKey: String
        get() = "$weekday|$startUnit" +
            (if (wholeSlot) "" else "|" + weeks.joinToString(","))

    /** 初始值：精确周的覆盖 → 全部周的覆盖 → 教务原值 */
    fun initialValue(rooms: Map<String, String>): String =
        rooms[editKey] ?: rooms["$weekday|$startUnit"] ?: room.orEmpty()
}

/** 按「时段 → 教室」两级归并出可编辑字段（与详情页分时段明细同一套思路） */
internal fun roomEditSlots(sessions: List<Session>): List<RoomEditSlot> =
    sessions.groupBy { it.weekday to it.startUnit }
        .flatMap { (slot, sameSlot) ->
            val byRoom = sameSlot.groupBy { it.roomText ?: "" }
            val groups = (if (byRoom.size == 1) listOf(sameSlot) else byRoom.values.toList())
                .map { g ->
                    val head = g.first()
                    RoomEditSlot(
                        weekday = slot.first,
                        startUnit = slot.second,
                        endUnit = head.endUnit,
                        room = head.roomText,
                        weeks = g.map { it.weekIndex }.distinct().sorted(),
                        wholeSlot = byRoom.size == 1,
                    )
                }
            groups
        }
        .sortedWith(compareBy({ it.weekday }, { it.startUnit }, { it.weeks.firstOrNull() ?: 0 }))

/**
 * 编辑一门课的**本地显示**（issue #6 保底）：课程名 + 各时段教室。
 * 只改本机显示，教务刷新不会覆盖；留空 = 恢复教务原值。
 * 同一时段不同周教室不同时按教室拆成多个字段（键带周次），不会互相覆盖。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditDisplayDialog(
    course: Course,
    overrides: DisplayOverridesView?,
    onDismiss: () -> Unit,
    onSave: (String, Map<String, String>) -> Unit,
) {
    var name by remember(course) { mutableStateOf(overrides?.name ?: course.nameZh) }
    val slots = remember(course) { roomEditSlots(course.sessions) }
    val rooms = remember(course) {
        val initial = slots.associate { it.editKey to it.initialValue(overrides?.rooms ?: emptyMap()) }
        mutableStateMapOf(*initial.toList().toTypedArray())
    }
    // 多教室的时段（单双周换教室这类）要提示一次，用户才知道为什么拆成了几个字段
    val multiSlots = remember(course) {
        slots.groupBy { it.weekday to it.startUnit }
            .filterValues { it.size > 1 }
            .map { it.key }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑显示（仅本机）") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "改的是显示，不改教务数据；刷新课表不会被覆盖。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    label = { Text("课程名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                slots.forEachIndexed { i, s ->
                    val key = s.editKey
                    if ((s.weekday to s.startUnit) in multiSlots &&
                        slots.getOrNull(i - 1)?.let { it.weekday to it.startUnit } != (s.weekday to s.startUnit)
                    ) {
                        Text(
                            text = "该时段不同周教室不同，已按周拆分：",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        text = if (s.wholeSlot) {
                            "${ScheduleText.weekdayName(s.weekday)} ${s.startUnit}-${s.endUnit} 节"
                        } else {
                            "${ScheduleText.weekdayName(s.weekday)} ${s.startUnit}-${s.endUnit} 节 · " +
                                ScheduleText.compressWeeks(s.weeks)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedTextField(
                        value = rooms[key].orEmpty(),
                        onValueChange = { rooms[key] = it.take(20) },
                        label = { Text("教室（清空恢复默认）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), rooms.toMap()) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 这节课此刻正在上 —— 供「正在上的课」描边使用。按真实日期判断，调休也正确。 */
private fun isLiveNow(
    s: Session,
    periods: List<Period>,
    todayIso: String,
    now: LocalTime,
): Boolean {
    if (s.date != todayIso) return false
    val startText = periods.firstOrNull { it.indexNo == s.startUnit }?.startText ?: s.startTime ?: return false
    val endText = periods.firstOrNull { it.indexNo == s.endUnit }?.endText ?: s.endTime ?: return false
    val start = runCatching { LocalTime.parse(startText) }.getOrNull() ?: return false
    val end = runCatching { LocalTime.parse(endText) }.getOrNull() ?: return false
    return !now.isBefore(start) && !now.isAfter(end)
}

/** 按课程代码取色：色相由哈希决定，明度按深浅色模式调整，保证文字始终可读 */
private fun courseColor(code: String, dark: Boolean): Color {
    val hue = (((code.hashCode() % 360) + 360) % 360).toFloat()
    return if (dark) {
        Color.hsl(hue, 0.35f, 0.26f)
    } else {
        Color.hsl(hue, 0.55f, 0.88f)
    }
}

/**
 * 圆角虚线描边。
 *
 * Compose 没有内置虚线组件，用 drawBehind + dashPathEffect 自己画。
 * 虚线用来把相邻、颜色接近的课程块分开，避免连成一片看不出边界。
 */
private fun Modifier.dashedBorder(
    color: Color,
    dash: Dp = 4.dp,
    gap: Dp = 3.dp,
    strokeWidth: Dp = 1.dp,
    radius: Dp = 6.dp,
): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx(), radius.toPx()),
        style = Stroke(
            width = strokeWidth.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), gap.toPx())),
        ),
    )
}

@Composable
private fun NoSemesterBlock(
    loggedIn: Boolean,
    loading: Boolean,
    gradMode: Boolean,
    gmsLessons: List<com.zzupocket.app.data.gms.GradLesson>,
    gmsBusy: Boolean,
    gmsError: String?,
    gmsDialogOpen: Boolean,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onGmsLogin: () -> Unit,
    onGmsRefresh: () -> Unit,
    onGmsDialogDismiss: () -> Unit,
    onGmsAdopt: () -> Unit,
    onGradFallback: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            // 研究生：本科教务对这个账号没有角色，课表在研究生院那套系统里。
            // 这里显示的是「本周课表」—— 那个接口由服务端按当前周返回，所以不需要日期。
            gradMode -> {
                Text("研究生课表", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "你的账号在本科教务里没有课表权限 —— 研究生课表在研究生院系统里。" +
                        "登录用的是学校统一身份认证，密码输在学校自己的页面上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                when {
                    gmsBusy -> CircularProgressIndicator(modifier = Modifier.size(26.dp))
                    gmsLessons.isEmpty() -> Button(onClick = onGmsLogin) { Text("登录研究生系统") }
                    else -> {
                        gmsLessons
                            .sortedWith(compareBy({ it.weekday }, { it.period }))
                            .forEach { l ->
                                Text(
                                    "周${weekdayZh(l.weekday)} · 第${l.period}节 · ${l.courseName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onGmsRefresh) { Text("刷新本周课表") }
                    }
                }
                if (gmsError != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        gmsError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (gmsDialogOpen) {
                    com.zzupocket.app.ui.diag.GmsProbeDialog(
                        onDismiss = onGmsDialogDismiss,
                        onReport = { onGmsAdopt() },
                        loginMode = true,
                    )
                }
            }

            loading -> {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(12.dp))
                Text("正在获取课表…", style = MaterialTheme.typography.bodyMedium)
            }

            !loggedIn -> {
                Text("登录后查看课表", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onLogin) {
                    Icon(Icons.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("登录")
                }
            }

            else -> {
                Text("暂时没有课表数据", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRefresh) { Text("重新获取") }
            }
        }

        // 兜底入口：学籍探测判不出来（校外网络、接口异常等）时，这里是他唯一的出路。
        // 做成次要的文字按钮放在最下面：本科生课表正常时压根到不了这个页面，不会被打扰。
        if (!gradMode) {
            Spacer(Modifier.height(22.dp))
            TextButton(onClick = onGradFallback) {
                Text("我是研究生，课表在研究生院系统")
            }
        }
    }
}

/** 1..7 → 一..日 */
private fun weekdayZh(d: Int): String = when (d) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    4 -> "四"
    5 -> "五"
    6 -> "六"
    else -> "日"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseDetailSheet(
    course: Course,
    onDismiss: () -> Unit,
    /** 该课的显示覆盖（issue #6 保底）；有则显示「已自定义」与编辑/恢复入口 */
    overrides: DisplayOverridesView? = null,
    onApplyEdits: ((Course, String, Map<String, String>) -> Unit)? = null,
    onResetOverrides: ((Course) -> Unit)? = null,
) {
    var editOpen by remember(course) { mutableStateOf(false) }
    if (editOpen && onApplyEdits != null) {
        EditDisplayDialog(
            course = course,
            overrides = overrides,
            onDismiss = { editOpen = false },
            onSave = { name, rooms ->
                onApplyEdits(course, name, rooms)
                editOpen = false
            },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    course.nameZh,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (overrides?.any == true) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "已自定义",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            // 显示编辑（issue #6 保底）：楼名重复、地点太长这类显示问题，用户可自己改成顺眼的
            if (onApplyEdits != null) {
                Row {
                    TextButton(onClick = { editOpen = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (overrides?.any == true) "改显示" else "编辑显示")
                    }
                    if (overrides?.any == true && onResetOverrides != null) {
                        TextButton(onClick = { onResetOverrides(course) }) { Text("恢复默认") }
                    }
                }
            }
            if (course.teachers.isNotEmpty()) {
                DetailRow("教师", course.teachers.joinToString("、"))
            }
            val rooms = course.roomTexts.mapNotNull { ScheduleText.tidyPlace(it) }.distinct()
            if (rooms.isNotEmpty()) DetailRow("地点", rooms.joinToString("、"))
            val clock = course.sessions.firstOrNull()?.let { s ->
                listOfNotNull(s.startTime, s.endTime).takeIf { it.size == 2 }?.joinToString("–")
            }
            DetailRow("时间", listOfNotNull(ScheduleText.timeSlots(course.slots), clock).joinToString(" · "))
            DetailRow("周次", ScheduleText.compressWeeks(course.weeks))
            // 研究生课程没有课程号时，mapper 会用课程名当 key；别把课程名再显示成"课程代码"
            if (course.code.isNotBlank() && course.code != course.nameZh) {
                DetailRow("课程代码", course.code)
            }
            course.openDepartment?.takeIf { it.isNotBlank() }?.let { DetailRow("开课院系", it) }

            // 计划 vs 实际：计划 = 每周课表时长，实际 = 这门课累计的番茄钟时长
            CourseFocusSection(course = course, onStartFocus = onDismiss)

            // 按「时段 → (地点, 教师)」归并：整学期都一样就只列一行，真的不同才拆开。
            // 只有一行时没必要显示（上面的汇总已经说清楚了）。
            val slotLines = course.sessions.toSlotLines()
            if (slotLines.size > 1) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("分时段明细", style = MaterialTheme.typography.titleSmall)
                slotLines.forEach { line ->
                    val unitText =
                        if (line.startUnit == line.endUnit) "${line.startUnit}节"
                        else "${line.startUnit}-${line.endUnit}节"
                    val detail = listOfNotNull(
                        line.room,
                        line.teacher,
                        ScheduleText.compressWeeks(line.weeks),
                    ).joinToString(" · ")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(
                            text = "${ScheduleText.weekdayName(line.weekday)} $unitText",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(104.dp),
                        )
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 课程详情里的「计划 vs 实际」。
 *
 * 计划 = 每周课表上这门课的时长；实际 = 这门课累计的番茄钟专注时长。
 * 关联靠 [Session] 的 contextRef（`course:<key>`）—— 从课表点"开始专注"时写进去的。
 */
@Composable
private fun CourseFocusSection(course: Course, onStartFocus: () -> Unit) {
    val records by AppGraph.focusRepository
        .observeRecords(0L)
        .collectAsStateWithLifecycle(initialValue = emptyList<FocusRecord>())

    val ref = "course:${course.key}"
    val focusedSeconds = records
        .filter { it.contextRef == ref && it.phase == Phase.FOCUS }
        .sumOf { it.actualSeconds }
    val plannedMinutes = course.sessions
        .distinctBy { Triple(it.weekday, it.startUnit, it.endUnit) }
        .sumOf { minutesBetween(it.startTime, it.endTime) }

    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text("计划 vs 实际", style = MaterialTheme.typography.titleSmall)
    if (plannedMinutes > 0) {
        DetailRow("每周课时", FocusStats.humanDuration(plannedMinutes * 60))
    }
    DetailRow(
        "番茄钟累计",
        if (focusedSeconds > 0) FocusStats.humanDuration(focusedSeconds) else "还没有记录",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            startFocusFor(name = course.nameZh, contextRef = ref)
            onStartFocus()
        }) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("开始专注（标签：${course.nameZh}）")
        }
    }
}

/** "08:00" + "09:40" → 100 分钟；拿不到时间就返回 0 */
private fun minutesBetween(start: String?, end: String?): Int {
    val a = parseHhmm(start) ?: return 0
    val b = parseHhmm(end) ?: return 0
    return (b - a).coerceAtLeast(0)
}

private fun parseHhmm(text: String?): Int? {
    val raw = text?.trim().orEmpty()
    val parts = raw.split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    return h * 60 + m
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SemesterSheet(
    semesters: List<Semester>,
    selectedId: Int?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text(
                "选择学期",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            semesters.groupBy { it.schoolYear }.forEach { (year, list) ->
                Text(
                    text = year.ifBlank { "其他" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                list.forEach { s ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable { onPick(s.id) },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = s.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (s.isCurrent) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "本学期",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (s.id == selectedId) {
                                Text(
                                    "当前",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekPickerDialog(
    weeks: List<Int>,
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转到教学周") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                weeks.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { w ->
                            val selected = w == current
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    .clickable { onPick(w) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$w", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
