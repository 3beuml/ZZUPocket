package com.zzupocket.app.data.repo

import com.zzupocket.app.data.db.DisplayOverrideEntity
import com.zzupocket.app.domain.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 显示覆盖（issue #6 保底）应用逻辑的单元测试。
 *
 * 覆盖存自己的表、按 key 定位，教务刷新永不触碰；这里钉的是「应用到 Session」
 * 的语义：名称整门课替换、教室按 (weekday, startUnit) + 周次替换、互不串扰——
 * 单双周换教室的课（单周 302、双周 405）分周各管各的。
 */
class DisplayOverrideTest {

    private fun session(
        code: String,
        name: String,
        weekday: Int,
        startUnit: Int,
        room: String?,
        week: Int = 7,
    ) = Session(
        id = "s-$code-$weekday-$startUnit-$week",
        semesterId = 194,
        source = "eas",
        courseCode = code,
        courseName = name,
        weekIndex = week,
        weekday = weekday,
        startUnit = startUnit,
        endUnit = startUnit + 1,
        date = null,
        startTime = null,
        endTime = null,
        teacherName = null,
        roomText = room,
        campusText = null,
        color = null,
    )

    @Test
    fun `名称覆盖替换整门课的课名`() {
        val out = ScheduleRepository.applyDisplayOverrides(
            listOf(session("154014", "软件工程概论", 2, 1, "中2414")),
            listOf(
                DisplayOverrideEntity(
                    id = "194|154014|name", semesterId = 194, courseCode = "154014",
                    kind = "name", weekday = null, startUnit = null,
                    value = "软工", updatedAt = 0,
                ),
            ),
        )
        assertEquals("软工", out[0].courseName)
        assertEquals("中2414", out[0].roomText) // 教室不受名称覆盖影响
    }

    @Test
    fun `教室覆盖只作用对应时段`() {
        val sessions = listOf(
            session("154014", "软件工程概论", 2, 1, "中2414"),
            session("154014", "软件工程概论", 4, 1, "南1_206"),
        )
        val out = ScheduleRepository.applyDisplayOverrides(
            sessions,
            listOf(
                DisplayOverrideEntity(
                    id = "194|154014|room|2|1|all", semesterId = 194, courseCode = "154014",
                    kind = "room", weekday = 2, startUnit = 1,
                    value = "信息楼302", updatedAt = 0,
                ),
            ),
        )
        assertEquals("信息楼302", out[0].roomText)   // 周二 1-2 生效
        assertEquals("南1_206", out[1].roomText)     // 周四不动
    }

    @Test
    fun `分周教室覆盖只作用列出的教学周`() {
        // 单双周换教室：单周在南操场1，双周在南操场2；用户只想改单周的显示
        val odd = session("154014", "网球", 3, 1, "南操场1", week = 7)
        val even = session("154014", "网球", 3, 1, "南操场2", week = 8)
        val out = ScheduleRepository.applyDisplayOverrides(
            listOf(odd, even),
            listOf(
                DisplayOverrideEntity(
                    id = "194|154014|room|3|1|7,9", semesterId = 194, courseCode = "154014",
                    kind = "room", weekday = 3, startUnit = 1, weeks = "7,9",
                    value = "中区网球场", updatedAt = 0,
                ),
            ),
        )
        assertEquals("中区网球场", out[0].roomText) // 第 7 周命中
        assertEquals("南操场2", out[1].roomText)    // 第 8 周不受影响
    }

    @Test
    fun `全部周覆盖作用该时段每一周`() {
        val odd = session("154014", "网球", 3, 1, "南操场1", week = 7)
        val even = session("154014", "网球", 3, 1, "南操场2", week = 8)
        val out = ScheduleRepository.applyDisplayOverrides(
            listOf(odd, even),
            listOf(
                DisplayOverrideEntity(
                    id = "194|154014|room|3|1|all", semesterId = 194, courseCode = "154014",
                    kind = "room", weekday = 3, startUnit = 1, weeks = null,
                    value = "中区网球场", updatedAt = 0,
                ),
            ),
        )
        assertEquals("中区网球场", out[0].roomText)
        assertEquals("中区网球场", out[1].roomText)
    }

    @Test
    fun `精确周覆盖优先于全部周覆盖`() {
        // 数据变化后可能同时残留两种粒度：第 7 周听精确的，其余周听全部周的
        val odd = session("154014", "网球", 3, 1, "南操场1", week = 7)
        val even = session("154014", "网球", 3, 1, "南操场2", week = 8)
        val out = ScheduleRepository.applyDisplayOverrides(
            listOf(odd, even),
            listOf(
                DisplayOverrideEntity(
                    id = "194|154014|room|3|1|7", semesterId = 194, courseCode = "154014",
                    kind = "room", weekday = 3, startUnit = 1, weeks = "7",
                    value = "单周教室", updatedAt = 0,
                ),
                DisplayOverrideEntity(
                    id = "194|154014|room|3|1|all", semesterId = 194, courseCode = "154014",
                    kind = "room", weekday = 3, startUnit = 1, weeks = null,
                    value = "兜底教室", updatedAt = 0,
                ),
            ),
        )
        assertEquals("单周教室", out[0].roomText)
        assertEquals("兜底教室", out[1].roomText)
    }

    @Test
    fun `覆盖不跨学期不跨课程`() {
        val out = ScheduleRepository.applyDisplayOverrides(
            listOf(
                session("154014", "软件工程概论", 2, 1, "中2414").copy(semesterId = 195),
                session("154015", "编译原理", 2, 1, "中2414"),
            ),
            listOf(
                DisplayOverrideEntity(
                    id = "194|154014|room|2|1|all", semesterId = 194, courseCode = "154014",
                    kind = "room", weekday = 2, startUnit = 1,
                    value = "信息楼302", updatedAt = 0,
                ),
            ),
        )
        assertEquals("中2414", out[0].roomText) // 学期不同
        assertEquals("中2414", out[1].roomText) // 课程不同
    }

    @Test
    fun `空覆盖原样返回`() {
        val sessions = listOf(session("154014", "软件工程概论", 2, 1, "中2414"))
        assertEquals(sessions, ScheduleRepository.applyDisplayOverrides(sessions, emptyList()))
    }

    @Test
    fun `parseWeeks 解析逗号周次`() {
        assertEquals(setOf(1, 3, 5), ScheduleRepository.parseWeeks("1, 3,5"))
        assertEquals(setOf(16), ScheduleRepository.parseWeeks("16"))
        assertNull(ScheduleRepository.parseWeeks(null))
        assertNull(ScheduleRepository.parseWeeks(""))
        assertNull(ScheduleRepository.parseWeeks("x,y")) // 全非法 → 全部周兜底
    }
}
