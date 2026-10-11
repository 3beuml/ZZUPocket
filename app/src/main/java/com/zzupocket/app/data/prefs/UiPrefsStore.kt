package com.zzupocket.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 课程格里显示哪些内容（老师不在这里显示：点开课程详情能看到，格子太小放不下） */
enum class CourseTextMode {
    NAME,
    NAME_ROOM,

    /** 已废弃：早期版本允许在格子里显示老师，现在统一按「课名+地点」处理（读偏好时会自动降级） */
    @Deprecated("课表格子不再显示老师")
    NAME_TEACHER_ROOM,
}

/** 格子密度：影响行高与字号（9sp 太小，标准档提到 11sp） */
enum class GridDensity { COMPACT, STANDARD, COMFORTABLE }

/** 课程配色：多彩（按课程代码取色）/ 单色（统一用主题色） */
enum class CourseColorMode { COLORFUL, SINGLE }

/**
 * 课程块描边。
 *
 * 虚线用于把相邻、颜色接近的课程块分开，避免"连成一片"看不出边界。
 * 这里刻意把原来的「显示网格线」开关合并进来 —— 它本质就是同一种东西的实线形式。
 */
enum class BlockBorder { NONE, SOLID, DASHED }

/** 深浅色：跟随系统 / 始终浅色 / 始终深色 */
enum class DarkMode { SYSTEM, LIGHT, DARK }

/**
 * 全应用配色预设。
 *
 * [DYNAMIC] = 跟随系统壁纸的动态取色（最接近原生 Android 的默认观感）；
 * 其余每一项都是**一个种子色**，由 Material 3 算法（HCT）算出整套浅/深配色，
 * 所以颜色之间的关系是"原生那套"，而不是随手挑的色。
 */
enum class ThemePreset(val seedArgb: Int?) {
    DYNAMIC(null),
    PURPLE(0xFF6750A4.toInt()),
    BLUE(0xFF1A73E8.toInt()),
    TEAL(0xFF00838F.toInt()),
    GREEN(0xFF2E7D32.toInt()),
    AMBER(0xFFE8710A.toInt()),
    PINK(0xFFD81B60.toInt()),
    GRAPHITE(0xFF546E7A.toInt()),
}

data class UiPrefs(
    /** 是否显示周六周日（关掉就是只显示工作日 5 列，列宽更宽、字更大） */
    val showWeekend: Boolean = true,
    val blockBorder: BlockBorder = BlockBorder.DASHED,
    val courseTextMode: CourseTextMode = CourseTextMode.NAME_ROOM,
    val density: GridDensity = GridDensity.STANDARD,
    /** "正在上的课"描边高亮 —— 借鉴 Sked 的 liveCourseOutline */
    val liveOutlineEnabled: Boolean = true,
    val liveOutlineWidth: Int = 2,
    val colorMode: CourseColorMode = CourseColorMode.COLORFUL,
    /** 手动指定入学年；为空则不过滤（早期版本会从学号推断，已经改掉了） */
    val enrollmentYearOverride: Int? = null,
    /** 自定义课表背景图的文件路径；null = 用默认背景 */
    val backgroundPath: String? = null,
    /** 壁纸浓淡：图片自身的不透明度 */
    val backgroundOpacity: Float = 0.7f,
    /** 壁纸模糊半径（dp），0 = 完全清晰 */
    val backgroundBlur: Float = 6f,
    /**
     * 底色浓度：图片上那层主题底色的强度，越大字越清楚。
     * 和 [backgroundOpacity] 是乘法关系 —— 70% × 40% 大约让照片保留四成强度，
     * 壁纸看得出来、正文也读得清。
     */
    val backgroundScrim: Float = 0.4f,
    /** 课表页的底部导航栏也一起透明（只有课表 tab 生效） */
    val wallpaperNavBar: Boolean = true,
    /**
     * 电价（元/度）。服务端的配置里虽然有个 "Electricity rate" 字段，
     * 但实测值是空的（show=false, value=null），所以只能手填。
     * 默认 0.568 是河南居民合表电价，和学校宿舍电费口径一致。
     */
    val energyPricePerKwh: Float = 0.568f,
    /** 每次换图 +1，用来让内存里的位图缓存失效 */
    val backgroundVersion: Int = 0,
    /** 全应用配色预设（默认跟随系统壁纸动态取色） */
    val themePreset: ThemePreset = ThemePreset.DYNAMIC,
    /** 深浅色（默认跟随系统） */
    val darkMode: DarkMode = DarkMode.SYSTEM,
    /**
     * 两个槽位**各自**的房间可读名（如"柳园20号楼 101"）。
     *
     * ⚠️ 为什么要分开存：照明与空调是两套独立表计，**可以是两个不同的房间** ——
     * 换校区试查、或者学校只回了"绑定房间"那一路时就会这样。
     * 以前只有一个 `roomLabel` 给两张卡共用，谁最后保存就覆盖谁：
     * 点一次「从学校账户读取」（只填照明那一路）就会把空调卡下面的房间名也写成照明那间，
     * 看起来像"怎么查都显示我自己寝室"。
     *
     * 留空表示没有可读名，界面会退回用内置房间表按房间 ID 反查，再退回直接显示 ID。
     */
    val lightRoomLabel: String? = null,
    val acRoomLabel: String? = null,
) {
    val dayCount: Int get() = if (showWeekend) 7 else 5

    val cellHeightDp: Int
        get() = when (density) {
            GridDensity.COMPACT -> 48
            GridDensity.STANDARD -> 54
            GridDensity.COMFORTABLE -> 64
        }

    val courseFontSp: Float
        get() = when (density) {
            GridDensity.COMPACT -> 9.5f
            GridDensity.STANDARD -> 11f
            GridDensity.COMFORTABLE -> 12.5f
        }

    val roomFontSp: Float
        get() = when (density) {
            GridDensity.COMPACT -> 8f
            GridDensity.STANDARD -> 9f
            GridDensity.COMFORTABLE -> 10f
        }
}

private val Context.uiPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "ui_prefs")

class UiPrefsStore(context: Context) {

    private val store = context.applicationContext.uiPrefsStore

    val flow: Flow<UiPrefs> = store.data.map { p ->
        UiPrefs(
            showWeekend = p[KEY_SHOW_WEEKEND] ?: true,
            blockBorder = p[KEY_BLOCK_BORDER]?.let { runCatching { BlockBorder.valueOf(it) }.getOrNull() }
                ?: BlockBorder.DASHED,
            courseTextMode = p[KEY_TEXT_MODE]
                ?.let { runCatching { CourseTextMode.valueOf(it) }.getOrNull() }
                ?.takeIf { it != CourseTextMode.NAME_TEACHER_ROOM }   // 老设置自动降级
                ?: CourseTextMode.NAME_ROOM,
            density = p[KEY_DENSITY]?.let { runCatching { GridDensity.valueOf(it) }.getOrNull() }
                ?: GridDensity.STANDARD,
            liveOutlineEnabled = p[KEY_LIVE_OUTLINE] ?: true,
            liveOutlineWidth = p[KEY_LIVE_OUTLINE_WIDTH] ?: 2,
            colorMode = p[KEY_COLOR_MODE]?.let { runCatching { CourseColorMode.valueOf(it) }.getOrNull() }
                ?: CourseColorMode.COLORFUL,
            enrollmentYearOverride = p[KEY_ENROLL_YEAR]?.takeIf { it > 0 },
            backgroundPath = p[KEY_BG_PATH],
            backgroundOpacity = p[KEY_BG_OPACITY] ?: 0.7f,
            backgroundBlur = p[KEY_BG_BLUR] ?: 6f,
            backgroundScrim = p[KEY_BG_SCRIM] ?: 0.4f,
            wallpaperNavBar = p[KEY_BG_NAVBAR] ?: true,
            energyPricePerKwh = p[KEY_ENERGY_PRICE] ?: 0.568f,
            backgroundVersion = p[KEY_BG_VERSION] ?: 0,
            themePreset = p[KEY_THEME_PRESET]?.let { runCatching { ThemePreset.valueOf(it) }.getOrNull() }
                ?: ThemePreset.DYNAMIC,
            darkMode = p[KEY_DARK_MODE]?.let { runCatching { DarkMode.valueOf(it) }.getOrNull() }
                ?: DarkMode.SYSTEM,
            lightRoomLabel = p[KEY_LIGHT_ROOM_LABEL]?.takeIf { it.isNotBlank() },
            acRoomLabel = p[KEY_AC_ROOM_LABEL]?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun setShowWeekend(v: Boolean) = store.edit { it[KEY_SHOW_WEEKEND] = v }
    suspend fun setBlockBorder(v: BlockBorder) = store.edit { it[KEY_BLOCK_BORDER] = v.name }
    suspend fun setCourseTextMode(v: CourseTextMode) = store.edit { it[KEY_TEXT_MODE] = v.name }
    suspend fun setDensity(v: GridDensity) = store.edit { it[KEY_DENSITY] = v.name }
    suspend fun setLiveOutline(v: Boolean) = store.edit { it[KEY_LIVE_OUTLINE] = v }
    suspend fun setLiveOutlineWidth(v: Int) = store.edit { it[KEY_LIVE_OUTLINE_WIDTH] = v }
    suspend fun setColorMode(v: CourseColorMode) = store.edit { it[KEY_COLOR_MODE] = v.name }
    suspend fun setEnrollmentYear(v: Int?) = store.edit {
        if (v == null) it.remove(KEY_ENROLL_YEAR) else it[KEY_ENROLL_YEAR] = v
    }

    /** 换背景图：路径 + 版本号一起写 */
    suspend fun setBackground(path: String) = store.edit { p ->
        p[KEY_BG_PATH] = path
        p[KEY_BG_VERSION] = (p[KEY_BG_VERSION] ?: 0) + 1
    }

    suspend fun setBackgroundOpacity(v: Float) = store.edit { p ->
        p[KEY_BG_OPACITY] = v.coerceIn(0.15f, 0.95f)
    }

    suspend fun setBackgroundBlur(v: Float) = store.edit { p ->
        p[KEY_BG_BLUR] = v.coerceIn(0f, 20f)
    }

    suspend fun setBackgroundScrim(v: Float) = store.edit { p ->
        p[KEY_BG_SCRIM] = v.coerceIn(0f, 0.9f)
    }

    suspend fun setWallpaperNavBar(v: Boolean) = store.edit { p -> p[KEY_BG_NAVBAR] = v }

    suspend fun setThemePreset(v: ThemePreset) = store.edit { p -> p[KEY_THEME_PRESET] = v.name }

    suspend fun setDarkMode(v: DarkMode) = store.edit { p -> p[KEY_DARK_MODE] = v.name }

    /**
     * 写**其中一路**的房间可读名。
     *
     * 只传要改的那一路 —— 另一路的房间名必须原样留着：
     * 两路可以是不同房间（见 [UiPrefs.lightRoomLabel] 的说明），
     * 一起覆盖就是"查了别的房间却显示自己的寝室名"那个 bug。
     * [light]/[ac] 传空串表示清掉（界面会退回按房间 ID 反查）。
     */
    suspend fun setRoomLabels(light: String? = null, ac: String? = null) = store.edit { p ->
        if (light != null) {
            if (light.isBlank()) p.remove(KEY_LIGHT_ROOM_LABEL) else p[KEY_LIGHT_ROOM_LABEL] = light
        }
        if (ac != null) {
            if (ac.isBlank()) p.remove(KEY_AC_ROOM_LABEL) else p[KEY_AC_ROOM_LABEL] = ac
        }
    }

    suspend fun setEnergyPricePerKwh(v: Float) = store.edit { p ->
        p[KEY_ENERGY_PRICE] = v.coerceIn(0.1f, 5f)
    }

    /** 恢复默认壁纸：清掉图片和所有调节项，版本号照旧 +1（缓存一样要失效） */
    suspend fun clearBackground() = store.edit { p ->
        p.remove(KEY_BG_PATH)
        p.remove(KEY_BG_OPACITY)
        p.remove(KEY_BG_BLUR)
        p.remove(KEY_BG_SCRIM)
        p[KEY_BG_VERSION] = (p[KEY_BG_VERSION] ?: 0) + 1
    }

    private companion object {
        val KEY_SHOW_WEEKEND = booleanPreferencesKey("show_weekend")
        val KEY_BLOCK_BORDER = stringPreferencesKey("block_border")
        val KEY_TEXT_MODE = stringPreferencesKey("course_text_mode")
        val KEY_DENSITY = stringPreferencesKey("grid_density")
        val KEY_LIVE_OUTLINE = booleanPreferencesKey("live_outline")
        val KEY_LIVE_OUTLINE_WIDTH = intPreferencesKey("live_outline_width")
        val KEY_COLOR_MODE = stringPreferencesKey("color_mode")
        val KEY_ENROLL_YEAR = intPreferencesKey("enrollment_year")
        val KEY_BG_PATH = stringPreferencesKey("grid_bg_path")
        val KEY_BG_OPACITY = floatPreferencesKey("grid_bg_opacity")
        val KEY_BG_BLUR = floatPreferencesKey("grid_bg_blur")
        val KEY_BG_SCRIM = floatPreferencesKey("grid_bg_scrim")
        val KEY_BG_NAVBAR = booleanPreferencesKey("grid_bg_navbar")
        val KEY_ENERGY_PRICE = floatPreferencesKey("energy_price_per_kwh")
        val KEY_BG_VERSION = intPreferencesKey("grid_bg_version")
        val KEY_THEME_PRESET = stringPreferencesKey("theme_preset")
        val KEY_DARK_MODE = stringPreferencesKey("dark_mode")

        /** 旧的单槽位房间名（历史遗留）：已不再读写，留着只是没去动老用户的存储 */
        val KEY_LIGHT_ROOM_LABEL = stringPreferencesKey("light_room_label")
        val KEY_AC_ROOM_LABEL = stringPreferencesKey("ac_room_label")
    }
}
