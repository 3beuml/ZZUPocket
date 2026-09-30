package com.zzupocket.app.ui.schedule

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    vm: DisplaySettingsViewModel = viewModel(),
) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
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
                title = { Text("课表显示") },
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
