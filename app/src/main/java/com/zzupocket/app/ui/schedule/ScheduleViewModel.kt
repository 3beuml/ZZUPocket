package com.zzupocket.app.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.Enrollment
import com.zzupocket.app.core.ScheduleText
import com.zzupocket.app.core.toUserMessage
import com.zzupocket.app.data.auth.AuthRepository
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
    }

    fun onResume() {
        if (!_state.value.started) {
            viewModelScope.launch { bootstrap() }
        } else if (_state.value.loggedIn != auth.isLoggedIn()) {
            viewModelScope.launch { bootstrap() }
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
        _state.update { it.copy(started = true, loading = true, error = null) }
        try {
            val loggedIn = auth.isLoggedIn()
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
            _state.value.courses.firstOrNull { it.code == session.courseCode }?.let(::openDetail)
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
