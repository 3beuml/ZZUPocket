package com.zzupocket.app.ui.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.db.FocusTagEntity
import com.zzupocket.app.data.prefs.FocusPrefs
import com.zzupocket.app.data.prefs.FocusPrefsStore
import com.zzupocket.app.data.prefs.TimerPreset
import com.zzupocket.app.data.repo.FocusRepository
import com.zzupocket.app.domain.FocusRecord
import com.zzupocket.app.domain.FocusStats
import com.zzupocket.app.domain.FocusSummary
import com.zzupocket.app.domain.Phase
import com.zzupocket.app.focus.PomodoroEngine
import com.zzupocket.app.focus.TimerUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FocusUiState(
    val timer: TimerUi = TimerUi(),
    val prefs: FocusPrefs = FocusPrefs(),
    val tags: List<FocusTagEntity> = emptyList(),
    /** 本次要用的标签名；空串 = 未分类 */
    val tagName: String = "",
    val summary: FocusSummary = FocusSummary(),
    val todayRecords: List<FocusRecord> = emptyList(),
    val courseCandidates: List<String> = emptyList(),
) {
    /** 空闲时按设置里的时长显示，这样改完设置立刻能看到 */
    val displayTotalSeconds: Int
        get() = if (timer.idle) prefs.plannedSeconds(timer.phase) else timer.snapshot.plannedSeconds
}

class FocusViewModel : ViewModel() {

    private val engine: PomodoroEngine = AppGraph.pomodoroEngine
    private val prefsStore: FocusPrefsStore = AppGraph.focusPrefsStore
    private val repo: FocusRepository = AppGraph.focusRepository
    private val scheduleRepo = AppGraph.scheduleRepository

    private val tagName = MutableStateFlow("")
    private val selectedTag = tagName.asStateFlow()

    /** 本学期的课名，排在候选列表最前面（其余学期的放在后面） */
    private val semesterCourseNames = MutableStateFlow<List<String>>(emptyList())

    /** 统计要往前看多久：热力图最久 53 周，多留一点余量 */
    private val recordsSince = FocusStats.dayStart(
        FocusStats.dayKey(System.currentTimeMillis()).minusDays(400),
    )

    private val records = repo.observeRecords(recordsSince)

    val state: StateFlow<FocusUiState> = combine(
        engine.state,
        prefsStore.flow,
        repo.observeTags(),
        records,
        scheduleRepo.observeCourseNames(),
    ) { timer, prefs, tags, allRecords, names ->
        val now = System.currentTimeMillis()
        FocusUiState(
            timer = timer,
            prefs = prefs,
            tags = tags.filter { !it.archived },
            summary = FocusStats.summarize(allRecords, now),
            todayRecords = FocusStats.inRange(
                allRecords,
                FocusStats.dayStart(FocusStats.dayKey(now)),
                FocusStats.dayEnd(FocusStats.dayKey(now)),
            ),
            courseCandidates = semesterCourseNames.value +
                names.filterNot { it in semesterCourseNames.value },
        )
    }.combine(selectedTag) { ui, selected -> ui.copy(tagName = selected) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FocusUiState())

    init {
        viewModelScope.launch {
            // 第一次进来把四个默认标签种好（听课/自习/背单词/写代码）
            repo.ensureDefaultTags()
            // 默认沿用上次用的标签，省一次点击
            val p = prefsStore.current()
            if (p.lastTagName.isNotBlank()) tagName.value = p.lastTagName
        }
        viewModelScope.launch {
            val names = runCatching {
                scheduleRepo.currentSemester()?.let { sem ->
                    scheduleRepo.courses(sem.id).map { it.nameZh }.distinct()
                }
            }.getOrNull()
            semesterCourseNames.value = names.orEmpty()
        }
    }

    // ── 计时操作 ────────────────────────────────────────────────────────

    fun start() {
        val name = tagName.value.trim()
        // 空闲时按钮上写的就是"开始休息"还是"开始专注"，直接跟着 timer.phase 走
        val phase = state.value.timer.phase
        viewModelScope.launch {
            val tag = if (name.isEmpty()) null else repo.ensureTag(name)
            engine.start(
                phase = phase,
                tagId = tag?.id,
                tagName = tag?.name ?: name,
            )
        }
    }

    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun skip() = engine.skip()
    fun stop() = engine.stop()
    fun extend(minutes: Int) = engine.extend(minutes)
    fun startNext() = engine.startNext()

    // ── 标签 ────────────────────────────────────────────────────────────

    fun selectTag(name: String) {
        tagName.value = name
    }

    fun toggleTag(name: String) {
        tagName.value = if (tagName.value == name) "" else name
    }

    fun createTag(name: String, colorArgb: Int = 0) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val tag = repo.ensureTag(clean, colorArgb)
            if (tag != null) tagName.value = tag.name
        }
    }

    fun renameTag(id: Long, newName: String) = viewModelScope.launch { repo.renameTag(id, newName) }
    fun archiveTag(id: Long) = viewModelScope.launch { repo.archiveTag(id) }

    // ── 设置 ────────────────────────────────────────────────────────────

    fun applyPreset(preset: TimerPreset) = viewModelScope.launch { prefsStore.setPreset(preset) }

    fun saveDurations(focus: Int, short: Int, long: Int, every: Int) = viewModelScope.launch {
        prefsStore.setDurations(focus, short, long, every)
    }

    fun setAutoStart(breakAfterFocus: Boolean, focusAfterBreak: Boolean) = viewModelScope.launch {
        prefsStore.setAutoStart(breakAfterFocus, focusAfterBreak)
    }

    // ── 记录 ────────────────────────────────────────────────────────────

    fun deleteRecord(id: Long) = viewModelScope.launch { repo.deleteSession(id) }

    /** 手动补录：忘了开计时器时把这段时间补回去（分钟） */
    fun addManualRecord(minutes: Int, startedAt: Long, note: String = "") {
        if (minutes <= 0) return
        val name = tagName.value.trim()
        viewModelScope.launch {
            val tag = if (name.isEmpty()) null else repo.ensureTag(name)
            repo.addManualSession(
                startedAt = startedAt,
                actualSeconds = minutes * 60,
                tagId = tag?.id,
                tagName = tag?.name ?: name,
                note = note,
            )
        }
    }
}
