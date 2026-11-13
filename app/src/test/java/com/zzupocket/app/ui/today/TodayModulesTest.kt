package com.zzupocket.app.ui.today

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首页模块自定义的解析规则（issue #19） */
class TodayModulesTest {

    @Test
    fun `无自定义时按默认顺序全可见`() {
        val layout = TodayModules.resolved(emptyList(), emptySet())
        assertEquals(TodayModules.DEFAULT_ORDER, layout.map { it.first })
        assertTrue(layout.all { it.second })
    }

    @Test
    fun `时间轴恒在最前且不可隐藏`() {
        // 用户把时间轴调到最后、并尝试隐藏它
        val layout = TodayModules.resolved(
            listOf(
                TodayModules.TODAY_MODULE_SCHEDULE,
                TodayModules.TODAY_MODULE_CARD,
                TodayModules.TODAY_MODULE_TIMELINE,
            ),
            setOf(TodayModules.TODAY_MODULE_TIMELINE),
        )
        assertEquals(TodayModules.TODAY_MODULE_TIMELINE, layout.first().first)
        assertTrue(layout.first().second) // 依旧可见
    }

    @Test
    fun `隐藏的模块不再渲染`() {
        val layout = TodayModules.resolved(
            emptyList(),
            setOf(TodayModules.TODAY_MODULE_CARD, TodayModules.TODAY_MODULE_LIBRARY),
        )
        val ids = layout.map { it.first }
        assertFalse(TodayModules.TODAY_MODULE_CARD in ids.filter { id ->
            layout.first { it.first == id }.second
        })
        assertFalse(TodayModules.TODAY_MODULE_LIBRARY in ids.filter { id ->
            layout.first { it.first == id }.second
        })
        // 其余模块不受影响
        assertTrue(layout.first { it.first == TodayModules.TODAY_MODULE_ELECTRICITY }.second)
    }

    @Test
    fun `未知 id 被丢弃、缺省模块按默认顺序补到末尾`() {
        // 用户表里混进一个不存在的 id；且"空教室/时间轴/日程/图书馆"不在用户表里
        //（模拟用户表是旧版本存下的、新版本加了新模块的场景）：
        // 规则 = 用户顺序原样保留 + 缺省模块按默认顺序**追加到末尾** + 时间轴钉最前。
        val layout = TodayModules.resolved(
            listOf("ghost", TodayModules.TODAY_MODULE_CARD, TodayModules.TODAY_MODULE_ELECTRICITY),
            emptySet(),
        )
        assertEquals(
            listOf(
                TodayModules.TODAY_MODULE_TIMELINE,
                TodayModules.TODAY_MODULE_CARD,
                TodayModules.TODAY_MODULE_ELECTRICITY,
                TodayModules.TODAY_MODULE_SCHEDULE,
                TodayModules.TODAY_MODULE_LIBRARY,
                TodayModules.TODAY_MODULE_ROOMS,
            ),
            layout.map { it.first },
        )
    }

    @Test
    fun `重复 id 去重`() {
        val layout = TodayModules.resolved(
            listOf(TodayModules.TODAY_MODULE_CARD, TodayModules.TODAY_MODULE_CARD),
            emptySet(),
        )
        assertEquals(
            layout.map { it.first }.count { it == TodayModules.TODAY_MODULE_CARD },
            1,
        )
    }
}
