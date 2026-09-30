package com.zzupocket.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.cardBalanceStore: DataStore<Preferences> by preferencesDataStore(name = "card_balance")

/** 上一次成功查到的校园卡余额 */
data class CachedCardBalance(
    /** 余额（元） */
    val yuan: Double,
    /** 查到它的时刻 */
    val at: Long,
)

/**
 * 校园卡余额的本地缓存。
 *
 * 为什么需要落盘：**余额只能联网查**（`CardRepository.balance()` 走一卡通接口），
 * 而桌面小组件有一条硬规矩 —— 组件里不打任何网络请求（桌面是系统在后台唤醒我们去画的，
 * 在那里发请求等于让用户莫名耗电、网络一慢组件就白屏）。
 *
 * 所以两边各退一步：App 每次成功查到余额就往这里存一份，组件只读这份缓存。
 * 代价是数字可能不新，因此组件会在右上角用小字如实标注「数据较旧」。
 *
 * 顺带解决另一个小问题：今天页的卡片本来只在内存里缓存 5 分钟，
 * 切走再切回来就重新查一次接口；现在冷启动也能先把上次的余额显示出来。
 */
class CardBalanceStore(context: Context) {

    private val store = context.applicationContext.cardBalanceStore

    suspend fun latest(): CachedCardBalance? {
        val prefs = store.data.first()
        val yuan = prefs[KEY_YUAN] ?: return null
        val at = prefs[KEY_AT] ?: return null
        return CachedCardBalance(yuan, at)
    }

    suspend fun save(yuan: Double, at: Long = System.currentTimeMillis()) = store.edit {
        it[KEY_YUAN] = yuan
        it[KEY_AT] = at
    }

    suspend fun clear() = store.edit { it.clear() }

    private companion object {
        val KEY_YUAN = doublePreferencesKey("card_balance_yuan")
        val KEY_AT = longPreferencesKey("card_balance_at")
    }
}
