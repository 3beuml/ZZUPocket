package com.zzupocket.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zzupocket.app.domain.news.NewsProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.newsDataStore by preferencesDataStore(name = "news_prefs")

/** 仅存用户主动选择的兴趣，不读取、不上传任何校园账号信息。 */
class NewsPrefsStore(private val context: Context) {
    private val interestsKey = stringPreferencesKey("interests")

    val profile: Flow<NewsProfile> = context.newsDataStore.data.map { prefs ->
        val saved = prefs[interestsKey]
            ?.split('|')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.distinct()
            ?.take(8)
        NewsProfile(saved ?: NewsProfile.DEFAULT_INTERESTS)
    }

    suspend fun setInterests(interests: List<String>) {
        val cleaned = interests.map(String::trim).filter(String::isNotBlank).distinct().take(8)
        context.newsDataStore.edit { it[interestsKey] = cleaned.joinToString("|") }
    }
}
