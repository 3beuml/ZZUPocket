package com.zzupocket.app.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppError
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.Enrollment
import com.zzupocket.app.core.ScheduleText
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
    /** 研究生视图当前教学周（由「本周课表」反推） */
    val gmsWeekIndex: Int = 1,
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
                val periods = _state.value.periods.ifEmpty { GmsScheduleMapper.defaultPeriods() }
                val sessions = GmsScheduleMapper.toSessions(entries, periods)
                val courses = GmsScheduleMapper.toCourses(sessions)
                val semester = GmsScheduleMapper.toSemester(term, entries, current, LocalDate.now())
                val week = GmsWeekAlign.currentWeekIndex(entries, current)
                    ?: semester.firstWeek
                    ?: 1
                _state.update {
                    it.copy(
                        gmsBusy = false,
                        gmsError = null,
                        gmsLessons = current,
                        gmsSemester = semester,
                        gmsSessions = sessions,
                        gmsCourses = courses,
                        gmsWeekIndex = week,
                        gmsPeriods = periods,
                        gmsWeekResolvedOn = LocalDate.now(),
                        periods = periods,
                        notice = if (semester.startDate == null) {
                            "未能自动对齐当前教学周，已从第 $week 周开始显示"
                        } else null,
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
            }
        }
    }

    fun onResume() {
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
        val week = WeekAlignment.currentWeek(
            currentWeek = repo.resolveCurrentWeek(semester, today),
            firstWeek = semester.firstWeek,
        )
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
            )
        }
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
