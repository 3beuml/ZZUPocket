package com.zzupocket.app.ui.schedule

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.Enrollment
import com.zzupocket.app.core.WeekMath
import com.zzupocket.app.data.prefs.BlockBorder
import com.zzupocket.app.data.prefs.CourseColorMode
import com.zzupocket.app.data.prefs.CourseTextMode
import com.zzupocket.app.data.prefs.GridDensity
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.data.prefs.UiPrefsStore
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「教学周」卡片要显示的值。
 *
 * 研究生系统不返回当前第几周，所以周次必须是**可设置、可确认**的参数；
 * 这里只负责读写与展示，真正的换算在 [com.zzupocket.app.core.WeekMath]。
 */
data class WeekUi(
    val semesterId: Int? = null,
    /** 是否研究生视图（研究生用合成学期 id） */
    val grad: Boolean = false,
    val weekStart: java.time.LocalDate? = null,
    val startDay: com.zzupocket.app.core.WeekStartDay = com.zzupocket.app.core.WeekStartDay.MONDAY,
    val total: Int? = null,
    val confirmed: Boolean = false,
    val source: com.zzupocket.app.core.WeekSource = com.zzupocket.app.core.WeekSource.INFERRED,
    /** 按 [weekStart] 与今天算出的当前周次 */
    val currentWeek: Int? = null,
)

class DisplaySettingsViewModel : ViewModel() {

    private val store: UiPrefsStore = AppGraph.uiPrefsStore
    private val scheduleRepo = AppGraph.scheduleRepository

    val prefs: StateFlow<UiPrefs> = store.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiPrefs())

    /** 从学号前 4 位推断的入学年，用于在界面上展示"当前生效的入学年" */
    var detectedEnrollmentYear: Int? = null
        private set

    init {
        viewModelScope.launch {
            detectedEnrollmentYear = scheduleRepo.settingsAccount()?.let { parseEnrollmentYear(it) }
        }
    }

    fun setShowWeekend(v: Boolean) = viewModelScope.launch { store.setShowWeekend(v) }

    /** 「课程总览」要打开的学期：优先当前学期，退而求其次第一个 */
    suspend fun currentSemesterId(): Int? =
        scheduleRepo.currentSemester()?.id ?: scheduleRepo.semesters().firstOrNull()?.id
    fun setBlockBorder(v: BlockBorder) = viewModelScope.launch { store.setBlockBorder(v) }
    fun setTextMode(v: CourseTextMode) = viewModelScope.launch { store.setCourseTextMode(v) }
    fun setDensity(v: GridDensity) = viewModelScope.launch { store.setDensity(v) }
    fun setLiveOutline(v: Boolean) = viewModelScope.launch { store.setLiveOutline(v) }
    fun setLiveOutlineWidth(v: Int) = viewModelScope.launch { store.setLiveOutlineWidth(v) }
    fun setColorMode(v: CourseColorMode) = viewModelScope.launch { store.setColorMode(v) }
    fun setEnrollmentYear(v: Int?) = viewModelScope.launch { store.setEnrollmentYear(v) }
    fun setBackgroundOpacity(v: Float) = viewModelScope.launch { store.setBackgroundOpacity(v) }
    fun setBackgroundBlur(v: Float) = viewModelScope.launch { store.setBackgroundBlur(v) }
    fun setBackgroundScrim(v: Float) = viewModelScope.launch { store.setBackgroundScrim(v) }
    fun setWallpaperNavBar(v: Boolean) = viewModelScope.launch { store.setWallpaperNavBar(v) }

    fun clearBackground() {
        viewModelScope.launch {
            runCatching { ScheduleBackground.clear(AppGraph.appContextOrNull() ?: return@launch) }
            store.clearBackground()
        }
    }

    // ── 教学周参数 ────────────────────────────────────────────────────────

    private val _week = kotlinx.coroutines.flow.MutableStateFlow(WeekUi())
    val week: StateFlow<WeekUi> = _week

    init {
        viewModelScope.launch { refreshWeek() }
    }

    /** 当前该编辑哪个学期：有本科学期就用它，否则当成研究生（合成学期 id） */
    private suspend fun targetSemester(): Pair<Int, java.time.LocalDate?> {
        val s = runCatching { scheduleRepo.currentSemester() }.getOrNull()
        return if (s != null) {
            s.id to s.startDate
        } else {
            com.zzupocket.app.data.gms.GmsScheduleMapper.GRAD_SEMESTER_ID to null
        }
    }

    /** 读参数；若为空且教务给了学期起始日，就自动填一次（教务校历视为权威，直接 confirmed） */
    suspend fun refreshWeek() {
        val (id, calendarStart) = targetSemester()
        var st = store.weekSettings(id)
        if (st.weekOneStart == null && calendarStart != null) {
            st = st.copy(
                weekOneStart = com.zzupocket.app.core.WeekMath.startOfWeek(calendarStart, st.startDay),
                confirmed = true,
                source = com.zzupocket.app.core.WeekSource.CALENDAR,
            )
            runCatching { store.saveWeekSettings(id, st) }
        }
        val today = java.time.LocalDate.now()
        _week.value = WeekUi(
            semesterId = id,
            grad = id == com.zzupocket.app.data.gms.GmsScheduleMapper.GRAD_SEMESTER_ID,
            weekStart = st.weekOneStart,
            startDay = st.startDay,
            total = st.totalWeeks,
            confirmed = st.confirmed,
            source = st.source,
            currentWeek = st.weekOneStart?.let { com.zzupocket.app.core.WeekMath.weekIndexAt(it, today) },
        )
    }

    fun setWeekStart(date: java.time.LocalDate) = viewModelScope.launch {
        val w = _week.value
        val id = w.semesterId ?: return@launch
        val start = com.zzupocket.app.core.WeekMath.startOfWeek(date, w.startDay)
        store.saveWeekSettings(
            id,
            com.zzupocket.app.core.WeekSettings(
                weekOneStart = start,
                startDay = w.startDay,
                totalWeeks = w.total,
                confirmed = true,
                source = com.zzupocket.app.core.WeekSource.USER,
            ),
        )
        refreshWeek()
    }

    fun setCurrentWeek(week: Int) = viewModelScope.launch {
        val w = _week.value
        val id = w.semesterId ?: return@launch
        val start = com.zzupocket.app.core.WeekMath.weekOneStart(java.time.LocalDate.now(), week, w.startDay)
        store.saveWeekSettings(
            id,
            com.zzupocket.app.core.WeekSettings(
                weekOneStart = start,
                startDay = w.startDay,
                totalWeeks = w.total,
                confirmed = true,
                source = com.zzupocket.app.core.WeekSource.USER,
            ),
        )
        refreshWeek()
    }

    fun setWeekTotal(total: Int?) = viewModelScope.launch {
        val w = _week.value
        val id = w.semesterId ?: return@launch
        store.saveWeekSettings(
            id,
            com.zzupocket.app.core.WeekSettings(
                weekOneStart = w.weekStart,
                startDay = w.startDay,
                totalWeeks = total,
                confirmed = w.confirmed,
                source = w.source,
            ),
        )
        refreshWeek()
    }

    fun setWeekStartDay(day: com.zzupocket.app.core.WeekStartDay) = viewModelScope.launch {
        val w = _week.value
        val id = w.semesterId ?: return@launch
        store.saveWeekSettings(
            id,
            com.zzupocket.app.core.WeekSettings(
                weekOneStart = w.weekStart?.let { com.zzupocket.app.core.WeekMath.startOfWeek(it, day) },
                startDay = day,
                totalWeeks = w.total,
                confirmed = w.confirmed,
                source = w.source,
            ),
        )
        refreshWeek()
    }

    fun resetWeek() = viewModelScope.launch {
        val id = _week.value.semesterId ?: return@launch
        store.clearWeekSettings(id)
        refreshWeek()
    }

    companion object {
        /**
         * 学号前 4 位即入学年（如 202400000001 → 2024）。
         * 解析不出就返回 null —— 此时**不做过滤**，宁可多显示也不要少显示。
         */
        fun parseEnrollmentYear(account: String?): Int? = Enrollment.parseFromStudentId(account)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsScreen(
    onBack: () -> Unit,
    onOpenCrop: (Uri) -> Unit = {},
    onRecrop: () -> Unit = {},
    /** 「课程总览」入口（原顶栏图标移到设置页里） */
    onOpenOverview: (Int) -> Unit = {},
    vm: DisplaySettingsViewModel = viewModel(),
) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val week by vm.week.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val background = rememberScheduleBackground(
        context = context,
        path = prefs.backgroundPath,
        version = prefs.backgroundVersion,
    )
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onOpenCrop(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课程表设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // 「课程总览」入口（2026-10-25 补：之前重构时按钮丢了，页面一度不可达）
            val scope = rememberCoroutineScope()
            OutlinedButton(
                onClick = {
                    scope.launch {
                        vm.currentSemesterId()?.let { id -> onOpenOverview(id) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("课程总览（本学期全部课程）") }

            // 教学周放在最上面：周次对不对，比"好不好看"重要得多
            WeekSettingsCard(
                week = week,
                onSetWeekStart = vm::setWeekStart,
                onSetCurrentWeek = vm::setCurrentWeek,
                onSetTotal = vm::setWeekTotal,
                onSetStartDay = vm::setWeekStartDay,
                onReset = vm::resetWeek,
            )

            SectionCard("显示") {
                SwitchRow(
                    title = "显示周六周日",
                    hint = "关掉后只显示周一到周五，每列更宽、课名更完整",
                    checked = prefs.showWeekend,
                    onCheckedChange = vm::setShowWeekend,
                )
                ChoiceRow(
                    title = "课程块描边",
                    hint = "虚线便于分辨相邻且颜色接近的课程块",
                    options = listOf(
                        BlockBorder.NONE to "无",
                        BlockBorder.SOLID to "实线",
                        BlockBorder.DASHED to "虚线",
                    ),
                    selected = prefs.blockBorder,
                    onSelect = vm::setBlockBorder,
                )
                ChoiceRow(
                    title = "课程格内容",
                    hint = "老师不放在格子里（格子里放不下，点开课程详情就能看到）",
                    options = listOf(
                        CourseTextMode.NAME to "课名",
                        CourseTextMode.NAME_ROOM to "课名+地点",
                    ),
                    selected = prefs.courseTextMode,
                    onSelect = vm::setTextMode,
                )
                ChoiceRow(
                    title = "格子密度",
                    hint = "标准档把课名字号提到 11sp（原来 9sp 偏小）",
                    options = listOf(
                        GridDensity.COMPACT to "紧凑",
                        GridDensity.STANDARD to "标准",
                        GridDensity.COMFORTABLE to "舒适",
                    ),
                    selected = prefs.density,
                    onSelect = vm::setDensity,
                )
            }

            SectionCard("配色") {
                ChoiceRow(
                    title = "课程配色",
                    options = listOf(
                        CourseColorMode.COLORFUL to "多彩",
                        CourseColorMode.SINGLE to "单色",
                    ),
                    selected = prefs.colorMode,
                    onSelect = vm::setColorMode,
                )
            }

            SectionCard("正在上的课") {
                SwitchRow(
                    title = "描边高亮",
                    hint = "给当前正在上的那节课加一圈描边，扫一眼就知道现在上什么",
                    checked = prefs.liveOutlineEnabled,
                    onCheckedChange = vm::setLiveOutline,
                )
                if (prefs.liveOutlineEnabled) {
                    ChoiceRow(
                        title = "描边宽度",
                        options = listOf(1 to "细", 2 to "标准", 3 to "粗"),
                        selected = prefs.liveOutlineWidth,
                        onSelect = vm::setLiveOutlineWidth,
                    )
                }
            }

            SectionCard("课表背景") {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (background != null) {
                        Image(
                            bitmap = background,
                            contentDescription = "当前的课表背景",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                    } else {
                        Text(
                            text = "当前用的是默认背景。选一张图，导入时会按课表比例让你裁剪。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                pickImage.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                        ) { Text(if (background == null) "选择图片" else "换一张") }

                        if (background != null) {
                            OutlinedButton(onClick = onRecrop) { Text("重新裁剪") }
                        }
                    }

                    if (background != null) {
                        // 原图还在就能重新裁；被系统清理过就退回"换一张"
                        val hasSource = remember(prefs.backgroundVersion) {
                            ScheduleBackground.hasSource(context)
                        }
                        if (!hasSource) {
                            Text(
                                text = "原图副本已经不在了，想换构图请点「换一张」重新选。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        WallpaperSlider(
                            label = "壁纸浓淡",
                            value = prefs.backgroundOpacity,
                            range = 0.15f..0.95f,
                            display = "${(prefs.backgroundOpacity * 100).roundToInt()}%",
                            hint = "图片自身的不透明度。",
                            onChange = vm::setBackgroundOpacity,
                        )
                        WallpaperSlider(
                            label = "模糊",
                            value = prefs.backgroundBlur,
                            range = 0f..20f,
                            display = if (prefs.backgroundBlur < 0.5f) {
                                "清晰"
                            } else {
                                "${prefs.backgroundBlur.roundToInt()}"
                            },
                            hint = "模糊一点，照片和文字更容易共存。",
                            onChange = vm::setBackgroundBlur,
                        )
                        WallpaperSlider(
                            label = "底色浓度",
                            value = prefs.backgroundScrim,
                            range = 0f..0.9f,
                            display = "${(prefs.backgroundScrim * 100).roundToInt()}%",
                            hint = "图片上那层主题底色。课名看不清就往右调。",
                            onChange = vm::setBackgroundScrim,
                        )
                        SwitchRow(
                            title = "底部导航栏一起透明",
                            hint = "只在课表这一页生效，其他页照旧",
                            checked = prefs.wallpaperNavBar,
                            onCheckedChange = vm::setWallpaperNavBar,
                        )
                        OutlinedButton(
                            onClick = vm::clearBackground,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("恢复默认壁纸") }
                    }
                }
            }

            SectionCard("学期范围") {
                val detected = vm.detectedEnrollmentYear
                val overrideYear = prefs.enrollmentYearOverride
                var yearText by rememberSaveable { mutableStateOf(overrideYear?.toString() ?: "") }
                val parsedYear = yearText.toIntOrNull()
                val invalid = yearText.isNotEmpty() &&
                    (yearText.length != 4 || parsedYear == null || parsedYear !in 2000..2100)

                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    OutlinedTextField(
                        value = yearText,
                        onValueChange = { raw ->
                            val digits = raw.filter(Char::isDigit).take(4)
                            yearText = digits
                            val p = digits.toIntOrNull()
                            when {
                                digits.isEmpty() -> vm.setEnrollmentYear(null)
                                digits.length == 4 && p != null && p in 2000..2100 ->
                                    vm.setEnrollmentYear(p)
                                // 输入不完整或超出范围：不生效，保持原状态
                                else -> Unit
                            }
                        },
                        label = { Text("入学年") },
                        placeholder = { Text("如 2024") },
                        singleLine = true,
                        isError = invalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when {
                            invalid -> "请输入 4 位年份（2000–2100）"
                            overrideYear != null -> "已生效：只显示 $overrideYear 年及以后的学期"
                            else -> "留空 = 显示全部学期（当前）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (invalid) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (detected != null) {
                        TextButton(
                            onClick = {
                                yearText = detected.toString()
                                vm.setEnrollmentYear(detected)
                            },
                        ) { Text("按学号推断：$detected") }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WallpaperSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    hint: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                text = display,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    hint: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    hint: String? = null,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(label) }
            }
        }
    }
}

// ── 教学周卡片 ──────────────────────────────────────────────────────────

/**
 * 「教学周」设置卡片。
 *
 * 为什么这几项必须能改：研究生系统不返回"当前第几周"，早期靠课表内容反推，
 * 实测把第 7 周算成了第 8 周（并把错误固化）。现在这四项是唯一事实来源：
 * 开学日期（第 1 周起始日）、当前周次、本学期总周次、每周起始日。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekSettingsCard(
    week: WeekUi,
    onSetWeekStart: (java.time.LocalDate) -> Unit,
    onSetCurrentWeek: (Int) -> Unit,
    onSetTotal: (Int?) -> Unit,
    onSetStartDay: (com.zzupocket.app.core.WeekStartDay) -> Unit,
    onReset: () -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showWeekPicker by remember { mutableStateOf(false) }
    var showTotalPicker by remember { mutableStateOf(false) }

    val sourceText = when (week.source) {
        com.zzupocket.app.core.WeekSource.CALENDAR -> "来自教务校历"
        com.zzupocket.app.core.WeekSource.USER -> "你手动设置的"
        com.zzupocket.app.core.WeekSource.INFERRED -> "自动推断"
    }
    val dateText = week.weekStart?.let { "%d/%d".format(it.monthValue, it.dayOfMonth) } ?: "未设置"

    SectionCard("教学周") {
        ValueRow(
            title = "开学日期（第 1 周${week.startDay.label}）",
            value = dateText,
            hint = if (week.weekStart == null) "点这里设置，否则周次和日期都会偏" else sourceText,
            warn = week.weekStart == null || !week.confirmed,
            onClick = { showDatePicker = true },
        )
        ValueRow(
            title = "当前周次",
            value = week.currentWeek?.takeIf { it >= 1 }?.let { "第 $it 周" } ?: "—",
            hint = "改它等于反推开学日期（以今天为准）",
            onClick = { showWeekPicker = true },
        )
        ValueRow(
            title = "本学期总周次",
            value = week.total?.toString() ?: "自动",
            hint = "只影响显示范围，不会删数据",
            onClick = { showTotalPicker = true },
        )
        ChoiceRow(
            title = "每周起始日",
            hint = "我校校历按周一起算；个别学校按周日",
            options = listOf(
                com.zzupocket.app.core.WeekStartDay.MONDAY to "周一",
                com.zzupocket.app.core.WeekStartDay.SUNDAY to "周日",
            ),
            selected = week.startDay,
            onSelect = onSetStartDay,
        )
        TextButton(
            onClick = onReset,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text("重置为自动值") }
    }

    if (showDatePicker) {
        val initial = week.weekStart
            ?.atStartOfDay(java.time.ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            val picked = java.time.Instant.ofEpochMilli(millis)
                                .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                            onSetWeekStart(picked)
                        }
                        showDatePicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showWeekPicker) {
        NumberGridDialog(
            title = "现在实际是第几周？",
            hint = "选完会按今天反推开学日期，并记住这个设置",
            numbers = (1..(week.total ?: 25)).toList(),
            selected = week.currentWeek,
            onPick = { onSetCurrentWeek(it); showWeekPicker = false },
            onDismiss = { showWeekPicker = false },
        )
    }

    if (showTotalPicker) {
        NumberGridDialog(
            title = "本学期共几周？",
            hint = "默认取课表里的最大周；调小只是不再显示后面的周",
            numbers = (1..30).toList(),
            selected = week.total,
            onPick = { onSetTotal(it); showTotalPicker = false },
            onDismiss = { showTotalPicker = false },
        )
    }
}

/** 设置页里的一行「标题 + 当前值」，点了进二级对话框 */
@Composable
private fun ValueRow(
    title: String,
    value: String,
    hint: String? = null,
    warn: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warn) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 数字网格选择对话框（周次/总周次共用） */
@Composable
private fun NumberGridDialog(
    title: String,
    hint: String,
    numbers: List<Int>,
    selected: Int?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hint, style = MaterialTheme.typography.bodySmall)
                numbers.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { n ->
                            val isSelected = n == selected
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    .clickable { onPick(n) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "$n",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
