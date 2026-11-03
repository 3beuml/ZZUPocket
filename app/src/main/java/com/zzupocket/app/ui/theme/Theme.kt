package com.zzupocket.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.zzupocket.app.data.prefs.DarkMode
import com.zzupocket.app.data.prefs.ThemePreset

/**
 * 全 app 统一的「生效深浅」判定，与 [ZZUPocketTheme] 内部同一口径。
 *
 * Compose 屏幕里凡是"按深浅选颜色"的地方（课表块明度、玻璃卡层级这类）
 * 都必须用它，而不是各自 `isSystemInDarkTheme()`：用户在外观页选了
 * 「始终深色/浅色」以后，系统深浅和主题档位会分叉 —— 各自为政会让
 * 块底色与块上文字来自两套配色，互相不可读。
 */
@Composable
fun rememberEffectiveDark(darkMode: DarkMode): Boolean = when (darkMode) {
    DarkMode.SYSTEM -> isSystemInDarkTheme()
    DarkMode.LIGHT -> false
    DarkMode.DARK -> true
}

/**
 * 全应用主题。
 *
 * 两种配色来源：
 *  - [ThemePreset.DYNAMIC]（默认）：跟随系统壁纸的动态取色，最接近原生 Android 的观感；
 *  - 其余预设：一个种子色 + Material 3 算法（见 [MaterialYouScheme]）算出整套浅/深配色。
 *
 * 深浅色由 [darkMode] 决定（跟随系统 / 强制浅 / 强制深）。
 * minSdk 31 = Android 12，动态取色始终可用，所以不需要版本判断。
 */
@Composable
fun ZZUPocketTheme(
    darkMode: DarkMode = DarkMode.SYSTEM,
    preset: ThemePreset = ThemePreset.DYNAMIC,
    content: @Composable () -> Unit,
) {
    val dark = rememberEffectiveDark(darkMode)
    val colorScheme = rememberAppColorScheme(preset = preset, dark = dark)
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

/**
 * 按预设算出配色。
 *
 * 单独抽出来是给「外观」页做实时预览用的 —— 预览某套配色时，当前主题还不是它。
 */
@Composable
fun rememberAppColorScheme(
    preset: ThemePreset,
    dark: Boolean,
    variant: SchemeVariant = SchemeVariant.TONAL_SPOT,
): ColorScheme {
    val context = LocalContext.current
    return remember(preset, dark, variant) {
        val seed = preset.seedArgb
        if (seed == null) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            MaterialYouScheme.fromSeed(seedArgb = seed, dark = dark, variant = variant)
        }
    }
}
