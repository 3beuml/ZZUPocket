package com.zzupocket.app.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.widget.RemoteViews
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.zzupocket.app.R
import com.zzupocket.app.data.prefs.CourseColorMode
import com.zzupocket.app.data.prefs.DarkMode
import com.zzupocket.app.data.prefs.ThemePreset
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.ui.theme.MaterialYouScheme

/** 小组件实际用到的几个颜色（都由 App 的主题算出来，不是另挑一套） */
data class WidgetColors(
    val bg: Int,
    val text: Int,
    val dim: Int,
    /** 比 [dim] 更弱的一档（地点、状态小字），用透明度拉开层次 */
    val hint: Int,
    val accent: Int,
    /** 警示色（欠费 / 电量偏低 / 余额偏低） */
    val error: Int,
    /** 课程配色是否"多彩"（关掉就只有主色一种，和 App 课表页的「单色」口径一致） */
    val colorful: Boolean,
)

/**
 * 小组件的配色与 tint 工具。
 *
 * ## 为什么不用布局里写死的颜色
 * 布局里的 `@color/widget_*` 只是兜底（见 res/values/colors.xml 的注释）。
 * 组件真正显示的颜色是**从 App 当前主题算出来的**：
 *
 * ```
 * UiPrefs.themePreset + UiPrefs.darkMode  →  ColorScheme  →  bg / text / dim / accent
 * ```
 *
 * 主题预设那一套已经有现成的实现（`ui/theme/MaterialYouScheme`，和 App 内部完全同一个来源），
 * 所以桌面上的组件和 App 里的卡片是**同一套颜色**，用户在设置里换配色，桌面跟着变。
 *
 * ## 怎么把颜色刷到 RemoteViews 上（这一步是关键，别改成 setBackgroundColor）
 * 圆角是靠 `@drawable/widget_bg` 这个 shape 撑起来的；一旦用
 * `setInt(id, "setBackgroundColor", …)`，背景会被换成 ColorDrawable，**圆角直接没了**。
 * 正确做法是保住 shape、只改它的 tint：
 *
 * ```kotlin
 * remoteViews.setColorStateList(id, "setBackgroundTintList", ColorStateList.valueOf(color))
 * ```
 *
 * `setColorStateList(viewId, 方法名, 值)` 是 RemoteViews 的标准能力（在宿主进程里反射调用
 * `View.setBackgroundTintList`），所有启动器（包括厂商定制的）都支持。
 */
object WidgetStyle {

    /** 组件该用浅色还是深色 —— 跟 App 的「深浅色」设置走，不是跟系统走 */
    fun isDark(context: Context, prefs: UiPrefs): Boolean = when (prefs.darkMode) {
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
        // 「跟随系统」时才看系统；注意这里是**启动器所在进程**的配置，也就是系统的深浅色
        DarkMode.SYSTEM ->
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 当前主题的整套配色。
     *
     * 「跟随系统壁纸」用 Android 12+ 的动态取色；其余预设用种子色 + M3(HCT) 算法现算，
     * 两条路都走 Compose Material3，所以和 App 内的观感完全一致。
     * 动态取色在个别设备上不可用时 Material3 自己会退回默认配色，不需要我们兜。
     */
    fun scheme(context: Context, prefs: UiPrefs, dark: Boolean): ColorScheme {
        val seed = prefs.themePreset.seedArgb
        return if (seed == null) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            MaterialYouScheme.fromSeed(seed, dark)
        }
    }

    fun colors(context: Context, prefs: UiPrefs): WidgetColors {
        val dark = isDark(context, prefs)
        val s = scheme(context, prefs, dark)
        val dim = s.onSurfaceVariant.toArgb()
        return WidgetColors(
            // surfaceContainer：M3 里"卡片"那一层的容器色，比 surface 稍微实一点，
            // 放在花花绿绿的壁纸上不会糊成一片
            bg = s.surfaceContainer.toArgb(),
            text = s.onSurface.toArgb(),
            dim = dim,
            // 比 dim 再弱一档：地点、状态小字用透明度压下去，不必另挑一个灰色
            hint = withAlpha(dim, 0.62f),
            accent = s.primary.toArgb(),
            error = s.error.toArgb(),
            colorful = prefs.colorMode == CourseColorMode.COLORFUL,
        )
    }

    /** 给颜色换一个不透明度（0..1） */
    fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or (((alpha.coerceIn(0f, 1f)) * 255).toInt() shl 24)

    /**
     * 字号缩放系数：按**实测盒子高度**决定。
     *
     * 同一份布局会落在不同大小的盒子里（目标真机 4×2 = 342×195dp，
     * 而按官方老公式的启动器只给 ~250×110dp）。块和行的**高度**能靠
     * `layout_weight` / `setMinimumHeight` 自适应，但**字号不能** ——
     * 195dp 里合适的 28dp 数字，塞进 110dp 的盒子就会挤爆。
     * 所以渲染时按高度给字号乘一档，最坏情况也只是字小一点，不会挤出边界。
     */
    fun fontFactor(heightDp: Int): Float = when {
        heightDp <= 0 -> 1f        // 读不到尺寸时按目标机来
        heightDp >= 170 -> 1f      // 342×195 / 163×195 这一档
        heightDp >= 130 -> 0.85f
        else -> 0.7f
    }

    /**
     * 某个颜色上应该配什么颜色的字。
     *
     * 用在「下一节课」右上角那个圆形气泡上：它的底色是课程色（明度随课程不同），
     * 固定白字在浅色课程上会糊掉。按感知亮度挑黑或白 —— 门限取 0.6 是因为
     * 中间调下用深色字更清楚（与 M3 里 onPrimaryContainer 的做法同理）。
     */
    fun textOn(background: Int): Int {
        val r = android.graphics.Color.red(background) / 255f
        val g = android.graphics.Color.green(background) / 255f
        val b = android.graphics.Color.blue(background) / 255f
        // Rec. 709 亮度
        val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return if (luminance > 0.6f) 0xFF1C1B1F.toInt() else 0xFFFFFFFF.toInt()
    }

    /**
     * 课程色条的颜色。
     *
     * 和 App 课表格子**同一套算法**：色相 = 课程代码的哈希取模 360。
     * 区别只有明度：格子里需要浅底深字（浅色 0.88），而这条 3dp 的竖条要自己"发色"，
     * 太浅会看不见，所以取中间明度、深色模式下再提亮一档。
     *
     * 用户把课程配色设成「单色」时统一用主题主色（跟课表页的表现一致）。
     */
    fun courseColor(code: String, colors: WidgetColors, dark: Boolean): Int {
        if (!colors.colorful) return colors.accent
        val hue = (((code.hashCode() % 360) + 360) % 360).toFloat()
        return Color.hsl(hue, 0.55f, if (dark) 0.62f else 0.52f).toArgb()
    }

    /** 给某个圆角 shape 刷上颜色（保住圆角，见类注释） */
    fun tint(remoteViews: RemoteViews, viewId: Int, color: Int) {
        remoteViews.setColorStateList(
            viewId,
            "setBackgroundTintList",
            ColorStateList.valueOf(color),
        )
    }

    /** 组件根容器的背景：一张圆角卡 */
    fun paintBackground(remoteViews: RemoteViews, viewId: Int, colors: WidgetColors) =
        tint(remoteViews, viewId, colors.bg)
}
