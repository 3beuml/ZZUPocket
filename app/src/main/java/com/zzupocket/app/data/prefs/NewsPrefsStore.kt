package com.zzupocket.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zzupocket.app.domain.news.NewsFeedFilter
import com.zzupocket.app.domain.news.NewsSortMode
import com.zzupocket.app.domain.news.NewsTopic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.newsDataStore by preferencesDataStore(name = "news_prefs")

/** 仅存用户主动选择的主题与排序方式，不读取、不上传任何校园账号信息。 */
class NewsPrefsStore(private val context: Context) {
    private val interestsKey = stringPreferencesKey("interests")
    private val topicsKey = stringPreferencesKey("followed_topics")
    private val onlyFollowedKey = booleanPreferencesKey("only_followed")
    private val sortModeKey = stringPreferencesKey("sort_mode")

    val filter: Flow<NewsFeedFilter> = context.newsDataStore.data.map {
        readFilter(it, interestsKey, topicsKey, onlyFollowedKey, sortModeKey)
    }

    suspend fun setFilter(filter: NewsFeedFilter) {
        context.newsDataStore.edit { prefs -> prefs.writeFilter(filter.normalized()) }
    }

    /** 在一次 edit 内读取最新配置，快速连点不会互相覆盖。 */
    suspend fun updateFilter(transform: (NewsFeedFilter) -> NewsFeedFilter) {
        context.newsDataStore.edit { prefs ->
            val current = readFilter(prefs, interestsKey, topicsKey, onlyFollowedKey, sortModeKey)
            prefs.writeFilter(transform(current).normalized())
        }
    }

    private fun MutablePreferences.writeFilter(filter: NewsFeedFilter) {
        this[topicsKey] = filter.topics.sortedBy(NewsTopic::ordinal).joinToString("|", transform = NewsTopic::name)
        this[onlyFollowedKey] = filter.onlyFollowed
        this[sortModeKey] = filter.sortMode.name
    }

    private companion object {
        fun readFilter(
            prefs: Preferences,
            interestsKey: Preferences.Key<String>,
            topicsKey: Preferences.Key<String>,
            onlyFollowedKey: Preferences.Key<Boolean>,
            sortModeKey: Preferences.Key<String>,
        ): NewsFeedFilter {
            val savedTopics = prefs[topicsKey]
            val topics = if (savedTopics == null) {
                val legacy = prefs[interestsKey]?.split('|').orEmpty()
                NewsTopic.fromLegacyInterests(legacy)
            } else {
                savedTopics.split('|').mapNotNull { token ->
                    NewsTopic.entries.firstOrNull { it.name == token }
                }.toSet()
            }
            val sortMode = NewsSortMode.entries.firstOrNull { it.name == prefs[sortModeKey] } ?: NewsSortMode.DATE
            return NewsFeedFilter(topics, prefs[onlyFollowedKey] == true, sortMode).normalized()
        }
    }
}
