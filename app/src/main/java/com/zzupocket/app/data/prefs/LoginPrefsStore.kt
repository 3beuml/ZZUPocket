package com.zzupocket.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.loginPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "login_prefs")

/**
 * 登录流程里需要**跨页面、跨进程**活着的一点点状态。
 *
 * 目前只有一个值：上一次发出短信验证码的时刻。
 *
 * 为什么必须落盘（而不是留在 ViewModel 里）：学校的重发限流是**按账号**算的，
 * 不会因为用户退出登录页、重进一次就重置。倒计时如果只活在 ViewModel 里，
 * 重进页面后按钮立刻恢复可点，用户点下去只会再吃一次失败 ——
 * 表现就跟"这个 App 的验证码按钮是坏的"一样。
 *
 * 刻意用独立的 DataStore 文件：它跟界面偏好（ui_prefs）不是一回事，
 * 将来清偏好/导出偏好时不该把它一起带走。
 */
class LoginPrefsStore(context: Context) {

    private val store = context.applicationContext.loginPrefsStore

    /** 上一次发出验证码的时刻（毫秒）；没发过就是 null */
    suspend fun lastSmsSentAt(): Long? = store.data.map { it[KEY_SMS_SENT_AT] }.first()

    suspend fun setSmsSentAt(millis: Long) = store.edit { it[KEY_SMS_SENT_AT] = millis }

    private companion object {
        val KEY_SMS_SENT_AT = longPreferencesKey("last_sms_sent_at")
    }
}
