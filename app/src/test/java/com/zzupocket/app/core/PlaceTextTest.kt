package com.zzupocket.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 上课地点文案的规整。
 *
 * 学校给的教室名自带楼栋前缀（`南2_206`），教务的楼栋名又是 `南2`，
 * 拼起来会变成 `南2 南2_206` —— 这些用例就是把这个坑钉住。
 */
class PlaceTextTest {

    @Test
    fun `重复的楼栋前缀与下划线会被收拾干净`() {
        assertEquals("南2 206", ScheduleText.tidyPlace("南2 南2_206"))
        assertEquals("南2 206", ScheduleText.tidyPlace("南2_206"))
        assertEquals("北1 305", ScheduleText.tidyPlace("北1 北1_305"))
        assertEquals("南2 206", ScheduleText.tidyPlace("南2 南2 206"))
    }

    @Test
    fun `正常的楼栋加房号不受影响`() {
        assertEquals("南2 206", ScheduleText.tidyPlace("南2 206"))
        assertEquals("北1-305", ScheduleText.tidyPlace("北1-305"))
    }

    @Test
    fun `相邻但不同的片段不会被误删`() {
        // 两个片段不一样时都要保留
        assertEquals("南2 306", ScheduleText.tidyPlace("南2 306"))
        assertEquals("南2 306 南2", ScheduleText.tidyPlace("南2 306 南2"))
    }

    @Test
    fun `幂等_反复规整结果不变`() {
        val once = ScheduleText.tidyPlace("南2 南2_206")
        assertEquals(once, ScheduleText.tidyPlace(once))
    }

    @Test
    fun `空值返回 null`() {
        assertNull(ScheduleText.tidyPlace(null))
        assertNull(ScheduleText.tidyPlace(""))
        assertNull(ScheduleText.tidyPlace("   "))
        assertNull(ScheduleText.tidyPlace("___"))
    }

    @Test
    fun `自定义地点保留但压缩多余空格`() {
        assertEquals("线上 腾讯会议", ScheduleText.tidyPlace("线上   腾讯会议"))
        assertEquals("线上", ScheduleText.tidyPlace("  线上  "))
    }
}
