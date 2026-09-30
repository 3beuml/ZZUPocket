package com.zzupocket.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.prefs.DarkMode
import com.zzupocket.app.data.prefs.ThemePreset
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.ui.theme.MaterialYouScheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme

/** 预设的中文名（放 UI 层，偏好存储里只存枚举） */
private fun ThemePreset.label(): String = when (this) {
    ThemePreset.DYNAMIC -> "跟随壁纸（动态取色）"
    ThemePreset.PURPLE -> "紫"
    ThemePreset.BLUE -> "蓝"
    ThemePreset.TEAL -> "青"
    ThemePreset.GREEN -> "绿"
    ThemePreset.AMBER -> "琥珀"
    ThemePreset.PINK -> "玫红"
    ThemePreset.GRAPHITE -> "石墨"
}

private fun DarkMode.label(): String = when (this) {
    DarkMode.SYSTEM -> "跟随系统"
    DarkMode.LIGHT -> "始终浅色"
    DarkMode.DARK -> "始终深色"
}

class AppearanceViewModel : ViewModel() {

    private val store = AppGraph.uiPrefsStore

    private val _state = MutableStateFlow(UiPrefs())
    val state: StateFlow<UiPrefs> = _state.asStateFlow()

    init {
        viewModelScope.launch { store.flow.collect { _state.value = it } }
    }

    fun setPreset(preset: ThemePreset) {
        viewModelScope.launch { store.setThemePreset(preset) }
    }

    fun setDarkMode(mode: DarkMode) {
        viewModelScope.launch { store.setDarkMode(mode) }
    }
}

/**
 * 「外观」页：切换整个 App 的配色与深浅色。
 *
 * 点一下**立刻全局生效**（主题在 MainActivity 上，改完整个 App 立刻重绘），
 * 所以每行左边那组色块本身就是预览色，不用再单独做一块预览区。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    vm: AppearanceViewModel = viewModel(),
) {
    val prefs by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配色与深色模式") },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "深浅色",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DarkMode.entries.forEach { mode ->
                    FilterChip(
                        selected = prefs.darkMode == mode,
                        onClick = { vm.setDarkMode(mode) },
                        label = { Text(mode.label()) },
                    )
                }
            }

            Text("配色", style = MaterialTheme.typography.titleSmall)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    ThemePreset.entries.forEach { preset ->
                        PresetRow(
                            preset = preset,
                            selected = prefs.themePreset == preset,
                            dark = prefs.darkMode == DarkMode.DARK,
                            onClick = { vm.setPreset(preset) },
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("说明", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "· 「跟随壁纸」用的是系统的动态取色（Material You），取色结果跟你的手机壁纸一致，" +
                            "这也是安卓原生的默认观感。\n" +
                            "· 其余每个配色由一个种子色出发，用 Material 3 同一套算法推出浅色与深色两份色板，" +
                            "所以颜色之间的关系跟原生一致，而不是随手挑的色。\n" +
                            "· 深浅色可以单独设，不影响配色选择。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.width(1.dp))
        }
    }
}

/** 一行配色：左边三色块（主色 / 主色容器 / 第三色），右边名字，选中打勾 */
@Composable
private fun PresetRow(
    preset: ThemePreset,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val swatches = rememberPresetSwatches(preset, dark)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(width = 56.dp, height = 28.dp)) {
            swatches.forEachIndexed { index, color ->
                Swatch(color = color, offsetIndex = index)
            }
        }
        Text(
            text = preset.label(),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选中",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 三个重叠的圆点，用来看这套配色长什么样 */
@Composable
private fun Swatch(color: Color, offsetIndex: Int) {
    Box(
        Modifier
            .padding(start = (offsetIndex * 14).dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

/** 某个预设（按当前深浅档）的三个代表色 */
@Composable
private fun rememberPresetSwatches(preset: ThemePreset, dark: Boolean): List<Color> {
    val context = LocalContext.current
    return remember(preset, dark) {
        val scheme = preset.seedArgb?.let { seed ->
            MaterialYouScheme.fromSeed(seedArgb = seed, dark = dark)
        } ?: if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        listOf(scheme.primary, scheme.primaryContainer, scheme.tertiary)
    }
}
