package com.zzupocket.app.ui.news

import com.zzupocket.app.domain.news.NewsCategory
import com.zzupocket.app.domain.news.NewsProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class NewsFilterCountTest {

    @Test
    fun `默认条件不显示数字`() {
        assertEquals(0, newsFilterCount(NewsProfile.DEFAULT_INTERESTS, NewsCategory.ALL))
    }

    @Test
    fun `兴趣顺序变化不算修改`() {
        assertEquals(
            0,
            newsFilterCount(NewsProfile.DEFAULT_INTERESTS.reversed(), NewsCategory.ALL),
        )
    }

    @Test
    fun `选中分类算一个条件`() {
        assertEquals(1, newsFilterCount(NewsProfile.DEFAULT_INTERESTS, NewsCategory.CAREER))
    }

    @Test
    fun `改过兴趣算一个条件`() {
        assertEquals(
            1,
            newsFilterCount(listOf("学科竞赛", "人工智能"), NewsCategory.ALL),
        )
    }

    @Test
    fun `分类和兴趣同时改动算两个条件`() {
        assertEquals(
            2,
            newsFilterCount(listOf("保研", "学术讲座"), NewsCategory.POSTGRADUATE),
        )
    }
}
