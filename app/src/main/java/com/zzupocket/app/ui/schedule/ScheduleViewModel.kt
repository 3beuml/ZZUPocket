package com.zzupocket.app.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppError
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.Enrollment
import com.zzupocket.app.core.ScheduleText
import com.zzupocket.app.core.WeekMath
import com.zzupocket.app.core.toUserMessage
import com.zzupocket.app.data.auth.AuthRepository
import com.zzupocket.app.data.gms.GmsScheduleMapper
import com.zzupocket.app.data.gms.GmsWeekAlign
import com.zzupocket.app.data.gms.GradLesson
import com.zzupocket.app.data.prefs.UiPrefsStore
import com.zzupocket.app.data.repo.ScheduleRepository
import com.zzupocket.app.domain.Course
import com.zzupocket.app.domain.Period
import com.zzupocket.app.domain.Plan
import com.zzupocket.app.domain.PlanSpec
import com.zzupocket.app.domain.Semester
import com.zzupocket.app.domain.Session
import com.zzupocket.app.domain.WeekAlignment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ScheduleUiState(
    val started: Boolean = false,
    val loading: Boolean = false,
    val loggedIn: Boolean = false,

    // ── 研究生课表（gmsstu / 研究生院那套系统）────────────────────────────
    /**
     * 学籍判定为"非本科"（本科教务拒绝了这个账号）。此时课表不在本科教务里，
     * 而要走进研究生系统 —— 见 `domain.AcademicTrack` 与 `data.gms.GmsScheduleSource`。
     */
    val gradMode: Boolean = false,
    /** 研究生本周课表（服务端按当前周返回，所以没有日期也能正确显示） */
    val gmsLessons: List<GradLesson> = emptyList(),
    /**
     * 研究生整学期课表：已映射成本科的 [Session]/[Course]，直接复用同一套周网格。
     * 为空时课表页退回原来的登录/空态区块。
     */
    val gmsSemester: Semester? = null,
    val gmsSessions: List<Session> = emptyList(),
    val gmsCourses: List<Course> = emptyList(),
    /** 研究生视图当前教学周（优先由落库锚点按日期推算） */
    val gmsWeekIndex: Int = 1,
    /** 当前学期在研究生系统里的 term value（写锚点用） */
    val gmsTermValue: String? = null,
    /**
     * 周次是否已被锚定（落库过或用户手动校正过）。
     *
     * false 时说明这次是"猜"的：界面要给出「校正周次」入口，用户点一次就永久锚定。
     */
    val gmsAnchored: Boolean = false,

    // ── 教学周参数（本科/研究生共用；按学期存）─────────────────────────
    /** 第 1 周的起始日；null = 还没定（界面显示"待设置"） */
    val weekStart: LocalDate? = null,
    /** 每周起始日：周一/周日 */
    val weekStartDay: com.zzupocket.app.core.WeekStartDay = com.zzupocket.app.core.WeekStartDay.MONDAY,
    /** 本学期总周次（用户可改；null = 用数据里的最大周） */
    val weekTotal: Int? = null,
    /** 周次参数是否已确认（教务校历或用户设置 ⇒ true；纯推断 ⇒ false） */
    val weekConfirmed: Boolean = false,
    /** 周次参数来源，界面据此标注 */
    val weekSource: com.zzupocket.app.core.WeekSource = com.zzupocket.app.core.WeekSource.INFERRED,
    /** 研究生视图的节次时刻（本科 periods 为空时用官方作息表兜底） */
    val gmsPeriods: List<Period> = emptyList(),
    /** gmsWeekIndex 是按哪一天对齐的；跨天后重新拉一次 */
    val gmsWeekResolvedOn: LocalDate? = null,
    val gmsBusy: Boolean = false,
    val gmsError: String? = null,
    /** WebView 登录器是否打开（状态放这儿，转屏/重组都不丢） */
    val gmsDialogOpen: Boolean = false,
    val error: String? = null,
    /** 提示（不是错误），比如过滤后没有学期而回退到全部 */
    val notice: String? = null,
    /** 数据库里的全部学期，未经过滤；改入学年时用它重新过滤 */
    val allSemesters: List<Semester> = emptyList(),
    val semesters: List<Semester> = emptyList(),
    val selectedSemester: Semester? = null,
    val weekIndex: Int = 1,
    /**
     * [weekIndex] 是**按哪一天**算出来的。
     *
     * 光存周次是不够的：跨天（尤其跨周）之后必须重新对准当前周，而判断"该不该重算"
     * 只能靠这个日期。用户手动翻周不会改它 —— 同一天内翻的周是他自己的选择，
     * 隔天则应该自动回到当前周（真机问题：今天页第 4 周、课表页停在第 3 周）。
     * null = 还没解析出周次（课表页据此先不挂周次翻页器，见 WeekPager 的注释）。
     */
    val weekResolvedOn: LocalDate? = null,
    /** 整学期全部课；翻周时在内存里过滤，不闪空白也不发请求 */
    val allSessions: List<Session> = emptyList(),
    val periods: List<Period> = emptyList(),
    val courses: List<Course> = emptyList(),
    /** 本学期自己加的日程 */
    val plans: List<Plan> = emptyList(),
    /** 日程编辑框：null 且 [planEditorOpen] 为 true = 新建 */
    val editingPlan: Plan? = null,
    val planEditorOpen: Boolean = false,
    /** 撞课确认：待保存的内容 + 撞到的课名 */
    val pendingPlan: PlanSpec? = null,
    val pendingConflicts: List<String> = emptyList(),
    val detailCourse: Course? = null,
) {
    /** 该学期有哪些教学周（升序） */
    val weeks: List<Int> get() = selectedSemester?.weekIndices.orEmpty()
    val weekCount: Int get() = weeks.size

    fun sessionsOf(week: Int): List<Session> = allSessions.filter { it.weekIndex == week }

    /** 研究生视图有哪些教学周（升序） */
    val gmsWeeks: List<Int> get() = gmsSemester?.weekIndices.orEmpty()
    val gmsWeekCount: Int get() = gmsSemester?.weekCount ?: 0

    fun gmsSessionsOf(week: Int): List<Session> = gmsSessions.filter { it.weekIndex == week }
}

class ScheduleViewModel : ViewModel() {

    private val auth: AuthRepository = AppGraph.authRepository
    private val repo: ScheduleRepository = AppGraph.scheduleRepository
    private val uiPrefs: UiPrefsStore = AppGraph.uiPrefsStore

    private val _state = MutableStateFlow(ScheduleUiState())
    val state: StateFlow<ScheduleUiState> = _state.asStateFlow()

    init {
        // 0.31.6 及以前那枚"自动推断"的周次锚点会带着 +1 的错误，进 App 时先清理一次
        viewModelScope.launch { runCatching { uiPrefs.migrateLegacyWeekAnchor() } }
        viewModelScope.launch { bootstrap() }
        viewModelScope.launch { observeEnrollmentYear() }
        // 学籍判定：本科教务认不认这个账号。判为"非本科"就切到研究生视图。
        viewModelScope.launch {
            if (detectGradMode() && com.zzupocket.app.core.AppGraph.gmsScheduleSource.hasSession()) {
                loadGmsWeek()
            }
        }
    }

    // ── 研究生课表（gmsstu / 研究生院那套系统）──────────────────────────

    /** 学籍只判一次（每个 ViewModel 生命周期内） */
    private var gradProbed = false

    /**
     * 学籍判定：拿同一枚令牌问一次本科教务的用户信息接口。
     *
     * 200 ⇒ 本科生（走现有链路，行为不变）；401/403 ⇒ 非本科（研究生）⇒ 课表走 gms。
     * **网络失败或意外状态码一律不猜**（保持本科链路），免得把本科生的课表弄没。
     */
    private suspend fun detectGradMode(): Boolean {
        if (gradProbed) return _state.value.gradMode
        gradProbed = true
        val track = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val token = auth.requireCasToken()
                com.zzupocket.app.data.eas.UndergradIdentityProbe
                    .probe(token, com.zzupocket.app.core.net.HttpClients.eas).track
            }
        }.getOrNull()
        val grad = track == com.zzupocket.app.domain.AcademicTrack.NOT_UNDERGRAD
        if (grad) _state.update { it.copy(gradMode = true) }
        return grad
    }

    fun openGmsLogin() = _state.update { it.copy(gmsDialogOpen = true) }

    /**
     * 手动切到研究生视图（兜底入口）。
     *
     * 学籍探测只在「本科教务明确回 401/403」时敢判成研究生；校外网络异常、接口改版等情况
     * 一律判成不确定、保持本科视图 —— 那时用户会卡在「暂时没有课表数据」，找不到研究生入口。
     * 这个入口让他自己声明一次，探测结果不再回改（gradProbed 置位）。
     */
    fun useGradSchedule() {
        gradProbed = true
        _state.update { it.copy(gradMode = true, error = null, gmsError = null) }
        if (com.zzupocket.app.core.AppGraph.gmsScheduleSource.hasSession()) {
            loadGmsWeek(force = true)
        } else {
            openGmsLogin()
        }
    }

    fun closeGmsLogin() = _state.update { it.copy(gmsDialogOpen = false) }

    /**
     * WebView 登录结束后调用：从 WebView 的 CookieManager 取回会话并采用，然后拉本周课表。
     *
     * 密码全程输在**学校自己的页面**上，我们只取它落下的会话 Cookie（不接触、不保存密码）。
     */
    fun adoptGmsSession() {
        _state.update { it.copy(gmsDialogOpen = false, gmsBusy = true, gmsError = null) }
        viewModelScope.launch {
            val cookie = runCatching {
                android.webkit.CookieManager.getInstance()
                    .getCookie(com.zzupocket.app.data.gms.GmsScheduleSource.COOKIE_URL)
            }.getOrNull()
            val adopted = com.zzupocket.app.core.AppGraph.gmsScheduleSource.onWebViewLogin(cookie)
            if (!adopted) {
                _state.update {
                    it.copy(gmsBusy = false, gmsError = "没拿到研究生系统的登录会话 —— 可能还没登录成功")
                }
                return@launch
            }
            // force：刚拿到的会话一定是新的，别被上一次还在跑的加载挡住
            loadGmsWeek(force = true)
        }
    }

    /**
     * 正在跑的研究生课表加载。
     *
     * ⚠️ 这里用 Job 而不是 `gmsBusy` 当并发闸门：0.31.4 曾经是
     * 「adoptGmsSession 先置 gmsBusy=true，再调 loadGmsWeek，而 loadGmsWeek 第一行
     * `if (gmsBusy) return`」——第一次走到就被自己挡住，gmsBusy 永远停在 true，
     * 界面无限转圈且不给任何错误（issue #2 报告者复现）。用 Job 判断就不会自锁。
     */
    private var gmsJob: kotlinx.coroutines.Job? = null

    /** 研究生课表三次请求的总预算；超了就给一句人话，别让界面无限转圈 */
    private val GMS_TIMEOUT_MS = 30_000L

    /**
     * 拉研究生整学期课表并映射成本科网格。
     *
     * 需要三次请求：学期列表 → 整学期课表 → 本周课表（只用它对当前教学周）。
     * 本周课表失败不阻塞整学期展示，只是表头不显示日期。
     *
     * @param force true = 取消在跑的那次重新拉（刷新按钮、登录完成后都该用它）
     */
    fun loadGmsWeek(force: Boolean = false) {
        if (!force && gmsJob?.isActive == true) return
        gmsJob?.cancel()
        _state.update { it.copy(gmsBusy = true, gmsError = null) }
        gmsJob = viewModelScope.launch {
            val result = runCatching {
                // 兜底超时：网络卡住时给一句人话，而不是让界面永远转圈
                kotlinx.coroutines.withTimeout(GMS_TIMEOUT_MS) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val source = AppGraph.gmsScheduleSource
                        val terms = source.terms()
                        val term = terms.firstOrNull { it.selected } ?: terms.firstOrNull()
                            ?: throw AppError.Parse("研究生系统没有返回学期")
                        val entries = source.weekEntries(term.value)
                        val current = runCatching { source.currentWeek() }.getOrDefault(emptyList())
                        Triple(term, entries, current)
                    }
                }
            }
            result.onSuccess { (term, entries, current) ->
                if (entries.isEmpty()) {
                    _state.update {
                        it.copy(gmsBusy = false, gmsError = "研究生系统没有返回本学期课表数据")
                    }
                    return@onSuccess
                }
                val today = LocalDate.now()
                val guess = GmsWeekAlign.guess(entries, current)
                val periods = _state.value.periods.ifEmpty { GmsScheduleMapper.defaultPeriods() }
                val sessions = GmsScheduleMapper.toSessions(entries, periods)
                val courses = GmsScheduleMapper.toCourses(sessions)
                val dataWeeks = entries.map { it.weekIndex }.distinct().sorted()

                // 周次参数：已设置就用；没有就把"唯一解"的推断结果填进去（标记待确认，绝不当成事实）
                var settings = runCatching {
                    uiPrefs.weekSettings(GmsScheduleMapper.GRAD_SEMESTER_ID)
                }.getOrDefault(com.zzupocket.app.core.WeekSettings())
                if (settings.weekOneStart == null) {
                    guess.anchoredWeekOrNull()?.let { inferred ->
                        settings = settings.copy(
                            weekOneStart = WeekMath.weekOneStart(today, inferred, settings.startDay),
                            totalWeeks = dataWeeks.maxOrNull(),
                            confirmed = false,
                            source = com.zzupocket.app.core.WeekSource.INFERRED,
                        )
                        runCatching {
                            uiPrefs.saveWeekSettings(GmsScheduleMapper.GRAD_SEMESTER_ID, settings)
                        }
                    }
                }
                val semester = GmsScheduleMapper.toSemester(
                    term = term,
                    entries = entries,
                    current = current,
                    today = today,
                    anchoredMonday = settings.weekOneStart,
                    anchoredWeek = null,
                )
                val week = if (settings.weekOneStart != null) {
                    WeekMath.weekIndexAt(settings.weekOneStart, today)
                } else {
                    GmsWeekAlign.currentWeekIndex(entries, current) ?: 1
                }.coerceIn(
                    semester.weekIndices.firstOrNull() ?: 1,
                    semester.weekIndices.lastOrNull() ?: Int.MAX_VALUE,
                )
                _state.update {
                    it.copy(
                        gmsBusy = false,
                        gmsError = null,
                        gmsTermValue = term.value,
                        gmsLessons = current,
                        gmsSemester = semester,
                        gmsSessions = sessions,
                        gmsCourses = courses,
                        gmsWeekIndex = week,
                        gmsPeriods = periods,
                        gmsWeekResolvedOn = today,
                        gmsAnchored = settings.confirmed,
                        periods = periods,
                        weekStart = settings.weekOneStart,
                        weekStartDay = settings.startDay,
                        weekTotal = settings.totalWeeks ?: dataWeeks.maxOrNull(),
                        weekConfirmed = settings.confirmed,
                        weekSource = settings.source,
                        notice = if (settings.weekOneStart == null) {
                            "还没定教学周基准，先按第 $week 周显示；去「课程表设置」设一下开学日期"
                        } else if (!settings.confirmed) {
                            "周次基准是自动推断的，可能不准；去「课程表设置」确认一下"
                        } else null,
                    )
                }
                // 顺手落库（0.31.8 只写不读：读路径等周次验证通过后再接，所以对界面零影响）
                runCatching {
                    repo.saveTimetable(
                        source = com.zzupocket.app.data.gms.GmsScheduleSource.SOURCE,
                        semesterId = GmsScheduleMapper.GRAD_SEMESTER_ID,
                        sessions = sessions,
                        courses = courses,
                    )
                }
            }.onFailure { e ->
                // 被新一次刷新取消：静默，不要报错（新任务已经在跑）
                if (e is kotlinx.coroutines.CancellationException &&
                    e !is kotlinx.coroutines.TimeoutCancellationException
                ) {
                    return@onFailure
                }
                val msg = if (e is kotlinx.coroutines.TimeoutCancellationException) {
                    "研究生系统 ${GMS_TIMEOUT_MS / 1000} 秒内没有响应，请检查网络后重试"
                } else {
                    e.toUserMessage()
                }
                _state.update { it.copy(gmsBusy = false, gmsError = msg) }
                // 记一次失败原因：将来「有缓存但刷新失败」时要能说清是会话过期还是教务不可用
                runCatching {
                    repo.markSyncError(
                        com.zzupocket.app.data.gms.GmsScheduleSource.SOURCE,
                        GmsScheduleMapper.GRAD_SEMESTER_ID,
                        msg,
                    )
                }
            }
        }
    }

    fun onResume() {
        // 从「课程表设置」改完周次参数回来：先把新参数读进来（纯本地，不联网）
        viewModelScope.launch { reloadWeekSettings() }
        if (!_state.value.started) {
            viewModelScope.launch { bootstrap() }
        } else if (_state.value.loggedIn != auth.isLoggedIn()) {
            viewModelScope.launch { bootstrap() }
        } else if (_state.value.gradMode) {
            // 研究生周次是用「本周课表」反推的；跨天后重新对齐一次。
            // 同一天切回课表页不重复拉取（3 个请求），避免无谓流量。
            if (_state.value.gmsWeekResolvedOn != LocalDate.now() && gmsJob?.isActive != true) {
                loadGmsWeek()
            }
        } else {
            // 切回课表页时不重建数据，但要**对准当前周**：跨天/跨周之后，
            // 停在旧周上会让用户看到上周的课（WeekAlignment 里有完整说明）。
            resyncWeekIfDayChanged()
        }
    }

    /**
     * 隔天就把显示周次重新对准当前周；同一天内用户手动翻过的周保留。
     *
     * 纯本地计算（`resolveCurrentWeek` 不发请求），所以直接在 resume 里同步调用。
     */
    private fun resyncWeekIfDayChanged() {
        val s = _state.value
        val semester = s.selectedSemester ?: return
        val today = LocalDate.now()
        if (!WeekAlignment.needsRealign(s.weekResolvedOn, today)) return
        val week = WeekAlignment.realigned(
            resolvedOn = s.weekResolvedOn,
            today = today,
            storedWeek = s.weekIndex,
            currentWeek = repo.resolveCurrentWeek(semester, today),
            firstWeek = semester.firstWeek,
        )
        _state.update { it.copy(weekIndex = week, weekResolvedOn = today) }
    }

    private suspend fun bootstrap() {
        // ⚠️ loggedIn 必须在**任何网络请求之前**就写进状态，否则会骗用户去反复重登：
        // 以前这里是"先 refreshSemesters()，直到最后才把 loggedIn 写进状态"，于是教务回 401/403
        // 时异常被 catch 掉，loggedIn 就停在默认的 false —— 界面同时显示红色「教务登录态已失效」
        // 与「登录后查看课表 + 登录按钮」（那两处分别由 error 与 !loggedIn 决定渲染），
        // 用户重登一次、界面一模一样，形成死循环（2026-10-14 用户报告）。
        // 本地令牌有效就按"已登录"渲染；教务被拒是另一回事，交给 error 说明。
        val loggedIn = auth.isLoggedIn()
        _state.update { it.copy(started = true, loading = true, error = null, loggedIn = loggedIn) }
        try {
            var all = repo.semesters()
            if (all.isEmpty() && loggedIn) {
                repo.refreshSemesters()
                all = repo.semesters()
            }
            val (visible, notice) = applyYearFilter(all)
            val selected = visible.firstOrNull { it.isCurrent } ?: visible.firstOrNull()
            _state.update {
                it.copy(
                    loading = selected == null,
                    loggedIn = loggedIn,
                    error = null,
                    notice = notice,
                    allSemesters = all,
                    semesters = visible,
                    selectedSemester = selected,
                )
            }
            if (selected != null) loadSemester(selected, loggedIn, forceSync = false)
        } catch (e: Throwable) {
            _state.update { it.copy(loading = false, error = e.toUserMessage()) }
        }
    }

    /**
     * 只保留入学年及以后的学期。
     *
     * 入学年**只认手动设置**（设置页那个输入框），不再拿学号前 4 位自动推断：
     * 推断错的时候会悄悄藏掉学期，用户完全找不到原因。
     * 没设置就不过滤。
     */
    private suspend fun applyYearFilter(all: List<Semester>): Pair<List<Semester>, String?> {
        val year = uiPrefs.flow.first().enrollmentYearOverride ?: return all to null
        // 手动填了入学年就彻底按它过滤：解析不出年份的学期也一起藏掉，
        // 否则永远会有漏网的老学期让人以为设置没生效。
        val filtered = Enrollment.filterSince(all, year, hideUnknown = true)
        return if (filtered.isEmpty()) {
            all to "按 $year 年及以后过滤后没有学期，已显示全部"
        } else {
            filtered to null
        }
    }

    /** 入学年改动后立刻重算，不用退出重进课表 */
    private suspend fun observeEnrollmentYear() {
        uiPrefs.flow
            .map { it.enrollmentYearOverride }
            .distinctUntilChanged()
            .drop(1) // 首帧由 bootstrap 负责，避免和它抢着改状态
            .collect { reapplyYearFilter() }
    }

    private suspend fun reapplyYearFilter() {
        val all = _state.value.allSemesters
        if (all.isEmpty()) return
        val (visible, notice) = applyYearFilter(all)
        val currentId = _state.value.selectedSemester?.id
        val selected = visible.firstOrNull { it.id == currentId }
            ?: visible.firstOrNull { it.isCurrent }
            ?: visible.firstOrNull()
        _state.update { it.copy(semesters = visible, notice = notice, selectedSemester = selected) }
        if (selected != null && selected.id != currentId) {
            _state.update { it.copy(loading = true) }
            loadSemester(selected, auth.isLoggedIn(), forceSync = false)
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** 载入某学期的数据；没有缓存且已登录时顺手拉一次 */
    private suspend fun loadSemester(semester: Semester, loggedIn: Boolean, forceSync: Boolean) {
        try {
            if (loggedIn && (forceSync || !repo.hasCourseData(semester.id))) {
                repo.refreshCourseTable(semester.id)
            }
        } catch (e: Throwable) {
            // 拉取失败不清空已有缓存，只提示
            _state.update { it.copy(error = e.toUserMessage()) }
        }
        var periods = repo.periods()
        if (periods.isEmpty() && loggedIn) {
            runCatching { repo.refreshPeriods() }
            periods = repo.periods()
        }
        val today = LocalDate.now()
        // 周次参数：按学期存；教务给了学期起始日就直接当权威值填进去（不需要用户确认）
        val settings = resolveWeekSettings(
            semesterId = semester.id,
            calendarStart = semester.startDate,
            dataWeeks = semester.weekIndices,
            today = today,
        )
        val week = if (settings.weekOneStart != null) {
            WeekMath
                .weekIndexAt(settings.weekOneStart, today)
                .coerceIn(semester.firstWeek ?: 1, semester.weekIndices.maxOrNull() ?: Int.MAX_VALUE)
        } else {
            WeekAlignment.currentWeek(
                currentWeek = repo.resolveCurrentWeek(semester, today),
                firstWeek = semester.firstWeek,
            )
        }
        val sessions = repo.sessionsOfSemester(semester.id)
        val courses = repo.courses(semester.id)
        val plans = repo.customPlans(semester.id)
        _state.update {
            it.copy(
                loading = false,
                selectedSemester = semester,
                periods = periods,
                weekIndex = week,
                // 记下"这个周次是按今天算的"，resume 时才知道要不要重算
                weekResolvedOn = today,
                allSessions = sessions,
                courses = courses,
                plans = plans,
                weekStart = settings.weekOneStart,
                weekStartDay = settings.startDay,
                weekTotal = settings.totalWeeks,
                weekConfirmed = settings.confirmed,
                weekSource = settings.source,
            )
        }
    }

    /**
     * 取某学期的周次参数，必要时自动填一次。
     *
     * 填充顺序（用户拍定的三档）：**教务校历 → 已有设置 → 推断**。自动值只填空白项；
     * 教务给的值视为权威（`confirmed = true`），推断出来的值必须标"待确认"。
     */
    private suspend fun resolveWeekSettings(
        semesterId: Int,
        calendarStart: LocalDate?,
        dataWeeks: List<Int>,
        today: LocalDate,
    ): com.zzupocket.app.core.WeekSettings {
        val saved = runCatching { uiPrefs.weekSettings(semesterId) }
            .getOrDefault(com.zzupocket.app.core.WeekSettings())
        if (saved.weekOneStart != null) return saved
        if (calendarStart == null) return saved
        val start = WeekMath.startOfWeek(calendarStart, saved.startDay)
        val filled = saved.copy(
            weekOneStart = start,
            totalWeeks = saved.totalWeeks ?: dataWeeks.maxOrNull(),
            confirmed = true,
            source = com.zzupocket.app.core.WeekSource.CALENDAR,
        )
        runCatching { uiPrefs.saveWeekSettings(semesterId, filled) }
        return filled
    }

    // ── 自定义日程 ──────────────────────────────────────────────────────

    /** 打开编辑框。[plan] 为空 = 新建 */
    fun openPlanEditor(plan: Plan? = null) {
        if (_state.value.selectedSemester == null) {
            _state.update { it.copy(notice = "还没有选中学期，先同步一次课表") }
            return
        }
        _state.update { it.copy(planEditorOpen = true, editingPlan = plan) }
    }

    fun closePlanEditor() = _state.update {
        it.copy(planEditorOpen = false, editingPlan = null, pendingPlan = null, pendingConflicts = emptyList())
    }

    /** 保存前先看有没有和上课时间撞车；撞了就等用户确认，不撞直接存 */
    fun savePlan(spec: PlanSpec) {
        val semesterId = _state.value.selectedSemester?.id ?: return
        viewModelScope.launch {
            val conflicts = runCatching { repo.planConflicts(semesterId, spec) }.getOrDefault(emptyList())
            if (conflicts.isNotEmpty()) {
                _state.update {
                    it.copy(
                        pendingPlan = spec,
                        pendingConflicts = conflicts.map { s ->
                            "${ScheduleText.weekdayName(s.weekday)}第 ${s.startUnit}-${s.endUnit} 节《${s.courseName}》"
                        },
                    )
                }
                return@launch
            }
            writePlan(spec)
        }
    }

    /** 用户确认"仍然保存" */
    fun confirmPendingPlan() {
        val spec = _state.value.pendingPlan ?: return
        viewModelScope.launch { writePlan(spec) }
    }

    fun dismissPendingPlan() = _state.update { it.copy(pendingPlan = null, pendingConflicts = emptyList()) }

    private suspend fun writePlan(spec: PlanSpec) {
        val semester = _state.value.selectedSemester ?: return
        runCatching { repo.savePlan(spec) }
            .onFailure { e -> _state.update { it.copy(error = e.toUserMessage()) } }
        closePlanEditor()
        loadSemester(semester, auth.isLoggedIn(), forceSync = false)
    }

    fun deletePlan(code: String) {
        val semester = _state.value.selectedSemester ?: return
        viewModelScope.launch {
            runCatching { repo.deletePlan(semester.id, code) }
                .onFailure { e -> _state.update { it.copy(error = e.toUserMessage()) } }
            closePlanEditor()
            loadSemester(semester, auth.isLoggedIn(), forceSync = false)
        }
    }

    /** 点课程块：自定义日程开编辑框，教务课程开详情 */
    fun onSessionClick(session: Session) {
        if (session.source == ScheduleRepository.SOURCE_CUSTOM) {
            openPlanEditor(_state.value.plans.firstOrNull { it.code == session.courseCode })
        } else {
            val pool = if (session.semesterId == GmsScheduleMapper.GRAD_SEMESTER_ID) {
                _state.value.gmsCourses
            } else {
                _state.value.courses
            }
            pool.firstOrNull { it.code == session.courseCode }?.let(::openDetail)
        }
    }

    fun selectSemester(id: Int) {
        val semester = _state.value.semesters.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            // 换学期时把"周次是按哪天算的"一并清掉：新课表还没解析出周次之前，
            // 课表页会先不挂周次翻页器，避免拿旧学期的周次当初始页
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    selectedSemester = semester,
                    weekResolvedOn = null,
                )
            }
            loadSemester(semester, auth.isLoggedIn(), forceSync = false)
        }
    }

    /** 切换教学周：纯本地，不发请求 */
    fun goToWeek(week: Int) {
        if (week !in _state.value.weeks) return
        _state.update { it.copy(weekIndex = week) }
    }

    /** 研究生视图切换教学周（同样纯本地） */
    fun goToGmsWeek(week: Int) {
        if (week !in _state.value.gmsWeeks) return
        _state.update { it.copy(gmsWeekIndex = week) }
    }

    /**
     * 设置「当前周次」—— 等价于反推开学日期，并把这次修改记为**用户设置**（已确认）。
     *
     * 研究生接口不返回当前第几周，反推在课程跨周重复时一定有歧义，所以这是最终裁决点。
     * 本科同样可用（比如周五那周其实是第 8 周，直接改这里最直观）。
     */
    fun setCurrentWeek(week: Int) {
        val s = _state.value
        val upper = s.weekTotal ?: s.weeks.lastOrNull() ?: s.gmsWeeks.lastOrNull() ?: week
        if (week < 1 || week > maxOf(upper, week)) return
        val today = LocalDate.now()
        applyWeekSettings(
            s.weekSettingsSnapshot().copy(
                weekOneStart = WeekMath.weekOneStart(today, week, s.weekStartDay),
                confirmed = true,
                source = com.zzupocket.app.core.WeekSource.USER,
            ),
            week = week,
            today = today,
        )
    }

    /** 设置「开学日期（第 1 周起始日）」；选到哪天都吸附到那一周的第一天 */
    fun setWeekStartDate(date: LocalDate) {
        val s = _state.value
        val start = WeekMath.startOfWeek(date, s.weekStartDay)
        val today = LocalDate.now()
        applyWeekSettings(
            s.weekSettingsSnapshot().copy(
                weekOneStart = start,
                confirmed = true,
                source = com.zzupocket.app.core.WeekSource.USER,
            ),
            week = WeekMath.weekIndexAt(start, today).coerceAtLeast(1),
            today = today,
        )
    }

    /** 设置「本学期总周次」；只影响显示范围，不删数据 */
    fun setWeekTotal(total: Int?) {
        val s = _state.value
        applyWeekSettings(
            s.weekSettingsSnapshot().copy(totalWeeks = total?.coerceIn(1, 30)),
            week = if (s.gradMode) s.gmsWeekIndex else s.weekIndex,
            today = LocalDate.now(),
        )
    }

    /** 设置「每周起始日」（周一/周日） */
    fun setWeekStartDay(day: com.zzupocket.app.core.WeekStartDay) {
        val s = _state.value
        applyWeekSettings(
            s.weekSettingsSnapshot().copy(
                startDay = day,
                weekOneStart = s.weekStart?.let { WeekMath.startOfWeek(it, day) },
            ),
            week = if (s.gradMode) s.gmsWeekIndex else s.weekIndex,
            today = LocalDate.now(),
        )
    }

    /** 重置为自动值：清掉本学期设置，下次进入按「教务校历 → 推断」重新填 */
    fun resetWeekSettings() {
        val id = weekSemesterId() ?: return
        _state.update {
            it.copy(
                weekStart = null,
                weekTotal = null,
                weekConfirmed = false,
                weekSource = com.zzupocket.app.core.WeekSource.INFERRED,
                gmsAnchored = false,
            )
        }
        viewModelScope.launch {
            runCatching { uiPrefs.clearWeekSettings(id) }
            val s = _state.value
            if (s.gradMode) {
                loadGmsWeek(force = true)
            } else {
                s.selectedSemester?.let { loadSemester(it, auth.isLoggedIn(), forceSync = false) }
            }
        }
    }

    /** 当前正在看的学期在设置里的 id（研究生用合成 id） */
    private fun weekSemesterId(): Int? = if (_state.value.gradMode) {
        GmsScheduleMapper.GRAD_SEMESTER_ID
    } else {
        _state.value.selectedSemester?.id
    }

    private fun ScheduleUiState.weekSettingsSnapshot() = com.zzupocket.app.core.WeekSettings(
        weekOneStart = weekStart,
        startDay = weekStartDay,
        totalWeeks = weekTotal,
        confirmed = weekConfirmed,
        source = weekSource,
    )

    /** 写设置 + 立刻按新基准重算当前周次（不重新联网） */
    private fun applyWeekSettings(
        settings: com.zzupocket.app.core.WeekSettings,
        week: Int,
        today: LocalDate,
    ) {
        val s = _state.value
        val bounded = week.coerceIn(1, settings.totalWeeks ?: maxOf(week, 1))
        _state.update {
            it.copy(
                weekStart = settings.weekOneStart,
                weekStartDay = settings.startDay,
                weekTotal = settings.totalWeeks,
                weekConfirmed = settings.confirmed,
                weekSource = settings.source,
                weekIndex = bounded,
                gmsWeekIndex = bounded,
                weekResolvedOn = today,
                gmsWeekResolvedOn = today,
                gmsAnchored = settings.confirmed,
                gmsSemester = if (s.gradMode && settings.weekOneStart != null) {
                    it.gmsSemester?.copy(startDate = settings.weekOneStart)
                } else {
                    it.gmsSemester
                },
                notice = null,
            )
        }
        val id = weekSemesterId() ?: return
        viewModelScope.launch { runCatching { uiPrefs.saveWeekSettings(id, settings) } }
    }

    /**
     * 从设置页回来时重新读周次参数（纯本地，不联网）。
     *
     * 只更新"当前这一侧"（本科生更新 weekIndex、研究生更新 gmsWeekIndex），
     * 免得把本科的周次写到研究生视图上。
     */
    private suspend fun reloadWeekSettings() {
        val id = weekSemesterId() ?: return
        val st = runCatching { uiPrefs.weekSettings(id) }.getOrNull() ?: return
        val start = st.weekOneStart ?: return
        val today = LocalDate.now()
        val week = WeekMath.weekIndexAt(start, today).coerceIn(1, st.totalWeeks ?: Int.MAX_VALUE)
        val grad = _state.value.gradMode
        _state.update {
            it.copy(
                weekStart = start,
                weekStartDay = st.startDay,
                weekTotal = st.totalWeeks,
                weekConfirmed = st.confirmed,
                weekSource = st.source,
                weekIndex = if (grad) it.weekIndex else week,
                gmsWeekIndex = if (grad) week else it.gmsWeekIndex,
                weekResolvedOn = if (grad) it.weekResolvedOn else today,
                gmsWeekResolvedOn = if (grad) today else it.gmsWeekResolvedOn,
                gmsAnchored = st.confirmed,
                gmsSemester = if (grad) it.gmsSemester?.copy(startDate = start) else it.gmsSemester,
                notice = if (st.confirmed) null else "周次基准待确认：去「课程表设置」确认一下开学日期",
            )
        }
    }

    fun refresh() {
        val semester = _state.value.selectedSemester
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            if (auth.isLoggedIn()) {
                runCatching { repo.refreshSemesters() }
                val all = repo.semesters()
                val (visible, notice) = applyYearFilter(all)
                val cur = _state.value.selectedSemester
                val sel = visible.firstOrNull { it.id == cur?.id } ?: visible.firstOrNull()
                _state.update {
                    it.copy(
                        allSemesters = all,
                        semesters = visible,
                        notice = notice,
                        selectedSemester = sel,
                    )
                }
                if (sel != null) loadSemester(sel, true, forceSync = true) else _state.update { it.copy(loading = false) }
            } else {
                _state.update { it.copy(loading = false, error = "未登录，无法刷新课表") }
            }
        }
    }

    fun openDetail(course: Course) = _state.update { it.copy(detailCourse = course) }
    fun closeDetail() = _state.update { it.copy(detailCourse = null) }

    /** 课程总览用的排序（按课程名） */
    fun overviewCourses(): List<Course> = _state.value.courses.sortedBy { it.nameZh }
}
