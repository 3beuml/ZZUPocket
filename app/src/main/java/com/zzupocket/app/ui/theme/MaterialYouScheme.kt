package com.zzupocket.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.zzupocket.app.data.prefs.DarkContrast
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant

/**
 * 配色风格变体（对标 Android 14+ 的"主题色"风格）。
 * 默认的 TONAL_SPOT 就是系统动态取色用的那套算法，所以它跟原生观感一致。
 */
enum class SchemeVariant(val label: String) {
    TONAL_SPOT("标准"),
    VIBRANT("鲜艳"),
    EXPRESSIVE("表现力"),
    CONTENT("内容色"),
}

/**
 * 由**一个种子色**算出整套 Material 3 配色（浅色 / 深色两套）。
 *
 * 用的是 Google `material-color-utilities`（MIT，Kotlin 移植）：先用 HCT 把种子色摊成
 * 若干色调调色板，再按 M3 的规范取各角色色值 —— 和 Android 系统的动态取色同一套算法，
 * 所以自己给种子色也能得到"原生感"的配色，而不是随手挑的色。
 */
object MaterialYouScheme {

    /** 默认对比度（系统默认档） */
    const val DEFAULT_CONTRAST = 0.0

    /** 外观页「深色对比度」档位 → MCU contrastLevel（对标系统对比度三档的映射） */
    fun darkContrastLevel(darkContrast: DarkContrast): Double = when (darkContrast) {
        DarkContrast.STANDARD -> 0.0
        DarkContrast.MEDIUM -> 0.5
        DarkContrast.HIGH -> 1.0
    }

    /**
     * 「深色保留颜色」的表达：深色列表的表面角色统一染色（往主色靠 20%，
     * 保住壁纸/种子色的色相）并轻微上抬（加白 7%），底不再是深灰近黑。
     *
     * 只动背景/表面系角色，文字角色一概不碰——
     * 文字与表面的明度差还被加白拉大了一档，对比反而更宽松。
     */
    internal fun tintedSurfaces(scheme: ColorScheme): ColorScheme {
        val accent = scheme.primary
        fun lift(c: Color): Color = lerp(lerp(c, accent, 0.20f), Color.White, 0.07f)
        return scheme.copy(
            background = lift(scheme.background),
            surface = lift(scheme.surface),
            surfaceDim = lift(scheme.surfaceDim),
            surfaceBright = lift(scheme.surfaceBright),
            surfaceContainerLowest = lift(scheme.surfaceContainerLowest),
            surfaceContainerLow = lift(scheme.surfaceContainerLow),
            surfaceContainer = lift(scheme.surfaceContainer),
            surfaceContainerHigh = lift(scheme.surfaceContainerHigh),
            surfaceContainerHighest = lift(scheme.surfaceContainerHighest),
            surfaceVariant = lift(scheme.surfaceVariant),
            surfaceTint = scheme.primary,
        )
    }

    fun fromSeed(
        seedArgb: Int,
        dark: Boolean,
        variant: SchemeVariant = SchemeVariant.TONAL_SPOT,
        contrastLevel: Double = DEFAULT_CONTRAST,
    ): ColorScheme {
        val scheme = dynamicScheme(seedArgb, dark, variant, contrastLevel)
        val colors = MaterialDynamicColors()
        fun role(color: DynamicColor): Color = Color(color.getArgb(scheme))
        return ColorScheme(
            primary = role(colors.primary()),
            onPrimary = role(colors.onPrimary()),
            primaryContainer = role(colors.primaryContainer()),
            onPrimaryContainer = role(colors.onPrimaryContainer()),
            inversePrimary = role(colors.inversePrimary()),

            secondary = role(colors.secondary()),
            onSecondary = role(colors.onSecondary()),
            secondaryContainer = role(colors.secondaryContainer()),
            onSecondaryContainer = role(colors.onSecondaryContainer()),

            tertiary = role(colors.tertiary()),
            onTertiary = role(colors.onTertiary()),
            tertiaryContainer = role(colors.tertiaryContainer()),
            onTertiaryContainer = role(colors.onTertiaryContainer()),

            background = role(colors.background()),
            onBackground = role(colors.onBackground()),

            surface = role(colors.surface()),
            onSurface = role(colors.onSurface()),
            surfaceVariant = role(colors.surfaceVariant()),
            onSurfaceVariant = role(colors.onSurfaceVariant()),
            surfaceTint = role(colors.surfaceTint()),
            inverseSurface = role(colors.inverseSurface()),
            inverseOnSurface = role(colors.inverseOnSurface()),
            surfaceBright = role(colors.surfaceBright()),
            surfaceDim = role(colors.surfaceDim()),
            surfaceContainer = role(colors.surfaceContainer()),
            surfaceContainerHigh = role(colors.surfaceContainerHigh()),
            surfaceContainerHighest = role(colors.surfaceContainerHighest()),
            surfaceContainerLow = role(colors.surfaceContainerLow()),
            surfaceContainerLowest = role(colors.surfaceContainerLowest()),

            error = role(colors.error()),
            onError = role(colors.onError()),
            errorContainer = role(colors.errorContainer()),
            onErrorContainer = role(colors.onErrorContainer()),

            outline = role(colors.outline()),
            outlineVariant = role(colors.outlineVariant()),
            scrim = role(colors.scrim()),
            // 注：primaryFixed / secondaryFixed / tertiaryFixed 那一组角色
            // 在当前的 Compose Material3 版本里还不是构造参数，本 App 也没用到，故不映射。
        )
    }

    internal fun dynamicScheme(
        seedArgb: Int,
        dark: Boolean,
        variant: SchemeVariant,
        contrastLevel: Double,
    ): DynamicScheme {
        val hct = Hct.fromInt(seedArgb)
        return when (variant) {
            SchemeVariant.TONAL_SPOT -> SchemeTonalSpot(hct, dark, contrastLevel)
            SchemeVariant.VIBRANT -> SchemeVibrant(hct, dark, contrastLevel)
            SchemeVariant.EXPRESSIVE -> SchemeExpressive(hct, dark, contrastLevel)
            SchemeVariant.CONTENT -> SchemeContent(hct, dark, contrastLevel)
        }
    }
}
