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
private val UNIT_COL_WIDTH = 30.dp

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
                // 左上角不显示"课表"二字，直接放学期切换
                title = { SemesterButton(state.selectedSemester) { showSemesters = true } },
                actions = {
                    IconButton(onClick = { vm.openPlanEditor() }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加日程")
                    }
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新课表")
                    }
                    IconButton(onClick = onOpenDisplaySettings) {
                        Icon(Icons.Filled.Tune, contentDescription = "课表显示设置")
                    }
                    IconButton(onClick = { state.selectedSemester?.id?.let(onOpenOverview) }) {
                        Icon(Icons.Filled.ListAlt, contentDescription = "课程总览")
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
                state.weeks.isEmpty() -> NoSemesterBlock(
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
                    onGmsRefresh = vm::loadGmsWeek,
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
                    )
                    // 周次还没解析出来之前**不要挂翻页器**：它的初始页取自 state.weekIndex，
                    // 而 bootstrap 会先给 weeks（19 周）、后给真实周次，中间那一小段
                    // weekIndex 还是默认的 1 —— 于是翻页器从第 1 周挂载、再一路动画滚到本周，
                    // 动画每经过一页都会被写回 ViewModel（详见 WeekPager 的注释）。
                    // 等解析完再挂，初始页直接就是本周，没有"从第 1 周滚过来"这一出。
                    val weekReady = state.weekResolvedOn != null || !state.loading
                    if (weekReady) {
                        WeekPager(
                            state = state,
                            vm = vm,
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
            weeks = state.weeks,
            current = state.weekIndex,
            onPick = { vm.goToWeek(it); showWeekPicker = false },
            onDismiss = { showWeekPicker = false },
        )
    }

    state.detailCourse?.let { course ->
        CourseDetailSheet(course = course, onDismiss = vm::closeDetail)
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
) {
    val shadow = wallpaperShadow(onWallpaper, MaterialTheme.colorScheme.surface)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable(onClick = onPick),
        ) {
            // 只留周次。原来下面还有一行「9/21 - 9/27」的日期区间，用户要求删掉 ——
            // "这是哪几天"改由星期表头下面那行日期回答（每一列都有，翻到哪一周都看得出来）。
            Text(
                text = "第 $week 周 / 共 $total 周",
                style = MaterialTheme.typography.titleMedium.copy(shadow = shadow),
                fontWeight = FontWeight.SemiBold,
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
    state: ScheduleUiState,
    vm: ScheduleViewModel,
    prefs: UiPrefs,
    onSessionClick: (Session) -> Unit,
) {
    val weeks = state.weeks
    val initial = weeks.indexOf(state.weekIndex).coerceAtLeast(0)
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
                weeks.getOrNull(page)?.let { if (it != state.weekIndex) vm.goToWeek(it) }
            }
    }
    // ② ViewModel 变化（点周次选择、隔天自动回到本周）→ 滚过去。
    //    这是单向的：①不会回写中间态，所以不会再打架。
    LaunchedEffect(state.weekIndex, weeks) {
        val idx = weeks.indexOf(state.weekIndex)
        if (idx >= 0 && idx != pagerState.currentPage) pagerState.animateScrollToPage(idx)
    }

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        val week = weeks.getOrNull(page)
        if (week != null) {
            ScheduleGrid(
                sessions = state.sessionsOf(week),
                periods = state.periods,
                prefs = prefs,
                // 星期表头下面那行日期要用它：算不出来（没学期/没起始日）就不显示日期
                weekMonday = ScheduleText.mondayOf(state.selectedSemester?.startDate, week),
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
            Row(Modifier.fillMaxWidth()) {
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
                            Text(
                                text = it.startText,
                                fontSize = 8.sp,
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
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 2.dp),
        ) {
            Text(
                text = session.courseName,
                fontSize = prefs.courseFontSp.sp,
                lineHeight = (prefs.courseFontSp + 2).sp,
                // 合并后的块更高，可以多放几行
                maxLines = if (span >= 2) 4 else if (prefs.density == GridDensity.COMPACT) 2 else 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
            )
            val secondary = buildList {
                // 老师不显示在格子上了（点开课程详情能看到），一是格子太小、二是学校数据里
                // 教师名往往很长；这里只保留地点，并用 tidyPlace 把"南2 南2_206"收拾成"南2 206"
                if (prefs.courseTextMode != CourseTextMode.NAME) {
                    ScheduleText.tidyPlace(session.roomText)?.let { add(it) }
                }
            }
            secondary.forEach { line ->
                Text(
                    text = line,
                    fontSize = prefs.roomFontSp.sp,
                    lineHeight = (prefs.roomFontSp + 1.5f).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
internal fun CourseDetailSheet(course: Course, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(course.nameZh, style = MaterialTheme.typography.headlineSmall)
            if (course.teachers.isNotEmpty()) {
                DetailRow("教师", course.teachers.joinToString("、"))
            }
            val rooms = course.roomTexts.mapNotNull { ScheduleText.tidyPlace(it) }.distinct()
            if (rooms.isNotEmpty()) DetailRow("地点", rooms.joinToString("、"))
            DetailRow("时间", ScheduleText.timeSlots(course.slots))
            DetailRow("周次", ScheduleText.compressWeeks(course.weeks))
            if (course.code.isNotBlank()) DetailRow("课程代码", course.code)
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
