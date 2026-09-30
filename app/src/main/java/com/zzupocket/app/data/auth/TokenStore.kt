package com.zzupocket.app.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 令牌存储。
 *
 * ⚠️ **只存令牌，不存密码。** 代价是 userToken（约 1 个月）过期后需要重新登录一次；
 * 收益是设备上不存在可直接复用的账号密码。
 *
 * 文件名 `secure_prefs` 与 `res/xml/backup_rules.xml` / `data_extraction_rules.xml`
 * 里的排除项必须一致 —— 令牌不进任何备份。
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var casUserToken: String?
        get() = prefs.getString(KEY_CAS_USER, null)
        set(value) = prefs.edit().putString(KEY_CAS_USER, value).apply()

    var casRefreshToken: String?
        get() = prefs.getString(KEY_CAS_REFRESH, null)
        set(value) = prefs.edit().putString(KEY_CAS_REFRESH, value).apply()

    var ecardAccessToken: String?
        get() = prefs.getString(KEY_ECARD_ACCESS, null)
        set(value) = prefs.edit().putString(KEY_ECARD_ACCESS, value).apply()

    var ecardRefreshToken: String?
        get() = prefs.getString(KEY_ECARD_REFRESH, null)
        set(value) = prefs.edit().putString(KEY_ECARD_REFRESH, value).apply()

    var ecardTokenAt: Long
        get() = prefs.getLong(KEY_ECARD_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_ECARD_AT, value).apply()

    /** 上次登录成功的时间，用于诊断页展示 */
    var lastLoginAt: Long
        get() = prefs.getLong(KEY_LAST_LOGIN, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_LOGIN, value).apply()

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val FILE_NAME = "secure_prefs"
        private const val KEY_CAS_USER = "cas_user_token"
        private const val KEY_CAS_REFRESH = "cas_refresh_token"
        private const val KEY_ECARD_ACCESS = "ecard_access_token"
        private const val KEY_ECARD_REFRESH = "ecard_refresh_token"
        private const val KEY_ECARD_AT = "ecard_token_at"
        private const val KEY_LAST_LOGIN = "last_login_at"
    }
}
