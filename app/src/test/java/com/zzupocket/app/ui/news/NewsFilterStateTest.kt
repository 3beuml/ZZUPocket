package com.zzupocket.app.ui.news

import com.zzupocket.app.domain.news.NewsFeedFilter
import com.zzupocket.app.domain.news.NewsSortMode
import com.zzupocket.app.domain.news.NewsTopic
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsFilterStateTest {
    @Test
    fun `默认空主题按时间为未启用`() {
        assertFalse(NewsFeedFilter().isActive)
        assertTrue(NewsFeedFilter().topics.isEmpty())
    }

    @Test
    fun `选择主题后只显示启用状态`() {
        assertTrue(NewsFeedFilter(setOf(NewsTopic.POSTGRADUATE, NewsTopic.LECTURE)).isActive)
    }

    @Test
    fun `严格模式和关注优先各自都会启用筛选状态`() {
        assertTrue(NewsFeedFilter(onlyFollowed = true).isActive)
        assertTrue(NewsFeedFilter(sortMode = NewsSortMode.RELEVANCE).isActive)
    }

    @Test
    fun `重置为空主题按时间且关闭严格模式`() {
        assertFalse(NewsFeedFilter().isActive)
    }
}
