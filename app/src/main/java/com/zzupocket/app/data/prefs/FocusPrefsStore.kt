package com.zzupocket.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zzupocket.app.domain.Phase
import com.zzupocket.app.domain.TimerSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 番茄钟预设。
 *
 * 经典档是番茄工作法原版；深度档给需要长时间不被打断的活（写代码、写报告）；
 * 冲刺档给刷题/模拟考这类本来就要坐很久的场景。
 */
enum class TimerPreset(
    val label: String,
    val focusMinutes: Int,
    val shortBreakMinutes: Int,
    val longBreakMinutes: Int,
    val longBreakEvery: Int,
) {
    CLASSIC("经典 25/5", 25, 5, 15, 4),
    DEEP("深度 50/10", 50, 10, 20, 3),
    ENDURANCE("长跑 90/15", 90, 15, 30, 2),
    CUSTOM("自定义", 25, 5, 15, 4),
    ;

    companion object {
        fun of(name: String?): TimerPreset? = entries.firstOrNull { it.name == name }
    }
}

data class FocusPrefs(
    val preset: TimerPreset = TimerPreset.CLASSIC,
    val focusMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    /** 几个番茄之后进长休息 */
    val longBreakEvery: Int = 4,
    /** 专注结束后自动开始休息 */
    val autoStartBreak: Boolean = true,
    /** 休息结束后自动开始下一个专注 */
    val autoStartFocus: Boolean = false,
    /** 上一次用的标签，下次默认选中 —— 减少每次都要点一下的摩擦 */
    val lastTagId: Long? = null,
    val lastTagName: String = "",
) {
    val focusSeconds: Int get() = focusMinutes * 60
    val shortBreakSeconds: Int get() = shortBreakMinutes * 60
    val longBreakSeconds: Int get() = longBreakMinutes * 60

    fun plannedSeconds(phase: Phase): Int = when (phase) {
        Phase.FOCUS -> focusSeconds
        Phase.SHORT_BREAK -> shortBreakSeconds
        Phase.LONG_BREAK -> longBreakSeconds
    }
}

private val Context.focusPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "focus_prefs")

/**
 * 番茄钟设置 + **正在跑的那一段**。
 *
 * 后者必须落盘：进程被系统杀掉之后要能接着走，不能白丢一个番茄。
 */
class FocusPrefsStore(context: Context, private val json: Json) {

    private val store = context.applicationContext.focusPrefsStore

    val flow: Flow<FocusPrefs> = store.data.map { p ->
        FocusPrefs(
            preset = TimerPreset.of(p[KEY_PRESET]) ?: presetOfMinutes(p[KEY_FOCUS]),
            focusMinutes = p[KEY_FOCUS] ?: 25,
            shortBreakMinutes = p[KEY_SHORT] ?: 5,
            longBreakMinutes = p[KEY_LONG] ?: 15,
            longBreakEvery = (p[KEY_LONG_EVERY] ?: 4).coerceIn(1, 12),
            autoStartBreak = p[KEY_AUTO_BREAK] ?: true,
            autoStartFocus = p[KEY_AUTO_FOCUS] ?: false,
            lastTagId = p[KEY_LAST_TAG_ID],
            lastTagName = p[KEY_LAST_TAG_NAME].orEmpty(),
        )
    }

    val snapshotFlow: Flow<TimerSnapshot> = store.data.map { p ->
        p[KEY_SNAPSHOT]?.let { raw ->
            runCatching { json.decodeFromString<TimerSnapshot>(raw) }.getOrNull()
        } ?: TimerSnapshot()
    }

    suspend fun current(): FocusPrefs = flow.first()

    suspend fun setPreset(preset: TimerPreset) = store.edit { p ->
        p[KEY_PRESET] = preset.name
        if (preset != TimerPreset.CUSTOM) {
            p[KEY_FOCUS] = preset.focusMinutes
            p[KEY_SHORT] = preset.shortBreakMinutes
            p[KEY_LONG] = preset.longBreakMinutes
            p[KEY_LONG_EVERY] = preset.longBreakEvery
        }
    }

    /** 手动改任意一项 → 预设自动变成"自定义" */
    suspend fun setDurations(focus: Int, short: Int, long: Int, every: Int) = store.edit { p ->
        p[KEY_PRESET] = TimerPreset.CUSTOM.name
        p[KEY_FOCUS] = focus.coerceIn(1, 240)
        p[KEY_SHORT] = short.coerceIn(1, 120)
        p[KEY_LONG] = long.coerceIn(1, 180)
        p[KEY_LONG_EVERY] = every.coerceIn(1, 12)
    }

    suspend fun setAutoStart(breakAfterFocus: Boolean, focusAfterBreak: Boolean) = store.edit { p ->
        p[KEY_AUTO_BREAK] = breakAfterFocus
        p[KEY_AUTO_FOCUS] = focusAfterBreak
    }

    suspend fun rememberTag(id: Long?, name: String) = store.edit { p ->
        if (id == null || name.isBlank()) {
            p.remove(KEY_LAST_TAG_ID)
            p.remove(KEY_LAST_TAG_NAME)
        } else {
            p[KEY_LAST_TAG_ID] = id
            p[KEY_LAST_TAG_NAME] = name
        }
    }

    suspend fun saveSnapshot(s: TimerSnapshot) = store.edit { p ->
        p[KEY_SNAPSHOT] = json.encodeToString(s)
    }

    suspend fun clearSnapshot() = store.edit { it.remove(KEY_SNAPSHOT) }

    suspend fun snapshot(): TimerSnapshot = snapshotFlow.first()

    /** 老的（或格式坏掉的）快照：focusMinutes 落在预设值上就认为是那个预设 */
    private fun presetOfMinutes(focus: Int?): TimerPreset =
        TimerPreset.entries.firstOrNull { it != TimerPreset.CUSTOM && it.focusMinutes == focus }
            ?: TimerPreset.CUSTOM

    private companion object {
        val KEY_PRESET = stringPreferencesKey("preset")
        val KEY_FOCUS = intPreferencesKey("focus_minutes")
        val KEY_SHORT = intPreferencesKey("short_minutes")
        val KEY_LONG = intPreferencesKey("long_minutes")
        val KEY_LONG_EVERY = intPreferencesKey("long_break_every")
        val KEY_AUTO_BREAK = booleanPreferencesKey("auto_start_break")
        val KEY_AUTO_FOCUS = booleanPreferencesKey("auto_start_focus")
        val KEY_LAST_TAG_ID = longPreferencesKey("last_tag_id")
        val KEY_LAST_TAG_NAME = stringPreferencesKey("last_tag_name")
        val KEY_SNAPSHOT = stringPreferencesKey("timer_snapshot")
    }
}
