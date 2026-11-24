package com.zzupocket.app.ui.news

import com.zzupocket.app.domain.news.NewsFeedFilter
import com.zzupocket.app.domain.news.NewsSortMode
import com.zzupocket.app.domain.news.NewsTopic
import org.junit.Assert.assertEquals
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
    fun `严格模式单独启用筛选状态`() {
        assertTrue(NewsFeedFilter(onlyFollowed = true).isActive)
    }

    @Test
    fun `无主题时关注优先置灰并归一为按时间`() {
        val filter = NewsFeedFilter(sortMode = NewsSortMode.RELEVANCE)
        assertFalse(filter.canPrioritizeFollowed)
        assertFalse(filter.isActive)
        assertEquals(NewsSortMode.DATE, filter.effectiveSortMode)
        assertEquals(NewsSortMode.DATE, filter.normalized().sortMode)
        assertEquals(filter, filter.withSortMode(NewsSortMode.RELEVANCE))
    }

    @Test
    fun `有主题时关注优先可启用`() {
        val filter = NewsFeedFilter(setOf(NewsTopic.AI), sortMode = NewsSortMode.RELEVANCE)
        assertTrue(filter.canPrioritizeFollowed)
        assertTrue(filter.isActive)
        assertEquals(NewsSortMode.RELEVANCE, filter.effectiveSortMode)
    }

    @Test
    fun `取消最后一个主题时同时回退按时间`() {
        val filter = NewsFeedFilter(setOf(NewsTopic.AI), sortMode = NewsSortMode.RELEVANCE).toggleTopic(NewsTopic.AI)
        assertTrue(filter.topics.isEmpty())
        assertEquals(NewsSortMode.DATE, filter.sortMode)
        assertFalse(filter.isActive)
    }

    @Test
    fun `重置为空主题按时间且关闭严格模式`() {
        assertFalse(NewsFeedFilter().isActive)
    }
}
