package com.zzupocket.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.zzupocket.app.data.prefs.DarkContrast
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
    darkContrast: DarkContrast = DarkContrast.STANDARD,
    darkTinted: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = rememberEffectiveDark(darkMode)
    val colorScheme = rememberAppColorScheme(
        preset = preset,
        dark = dark,
        darkContrast = darkContrast,
        darkTinted = darkTinted,
    )
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

/**
 * 按预设算出配色。
 *
 * 单独抽出来是给「外观」页做实时预览用的 —— 预览某套配色时，当前主题还不是它。
 *
 * 深色对比度只在深色 scheme 上生效：
 *  - 静态种子档 → 直接把对比度喂给 MCU（0.0/0.5/1.0，对标系统对比度三档）；
 *  - DYNAMIC 档 → 标准档原样用系统动态方案（原生默认观感分毫不变）；
 *    选中/高档时以系统动态方案的主色改种、按所选对比度重算整套 —— 系统没有暴露
 *    对比度入口，这是保留壁纸上"色相感受"的最短通道。
 *
 * darkTinted（"深色保留颜色"：见 [MaterialYouScheme.tintedSurfaces]）开着时，
 * 深色 scheme 的背景/表面系角色统一染色（往主色靠 14%）并轻微上抬（加白 4.5%）；
 * DYNAMIC+标准档开着它时也会改种重算（按标准对比度），否则切不出带色底的方案。
 */
@Composable
fun rememberAppColorScheme(
    preset: ThemePreset,
    dark: Boolean,
    variant: SchemeVariant = SchemeVariant.TONAL_SPOT,
    darkContrast: DarkContrast = DarkContrast.STANDARD,
    darkTinted: Boolean = false,
): ColorScheme {
    val context = LocalContext.current
    return remember(preset, dark, variant, darkContrast, darkTinted) {
        val contrast = MaterialYouScheme.darkContrastLevel(darkContrast)
        fun build(seedArgb: Int, level: Double): ColorScheme {
            val scheme = MaterialYouScheme.fromSeed(
                seedArgb = seedArgb,
                dark = dark,
                variant = variant,
                contrastLevel = level,
            )
            return if (dark && darkTinted) MaterialYouScheme.tintedSurfaces(scheme) else scheme
        }
        val seed = preset.seedArgb
        when {
            seed != null -> build(
                seed,
                if (dark) contrast else MaterialYouScheme.DEFAULT_CONTRAST,
            )
            !dark -> dynamicLightColorScheme(context)
            dark && darkTinted -> build(dynamicDarkColorScheme(context).primary.toArgb(), contrast)
            contrast > 0.0 -> build(dynamicDarkColorScheme(context).primary.toArgb(), contrast)
            else -> dynamicDarkColorScheme(context)
        }
    }
}
