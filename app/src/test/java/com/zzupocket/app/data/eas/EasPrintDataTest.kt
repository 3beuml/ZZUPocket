package com.zzupocket.app.data.eas

import com.zzupocket.app.data.db.CourseSessionEntity
import com.zzupocket.app.data.repo.ScheduleRepository
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * print-data（学生课表打印接口，issue #7 的实验排课来源）的解析与合并测试。
 *
 * 夹具取自 2026-10-25 用户真实抓包（jwxt.zzu.edu.cn 的 print-data 响应），
 * 仅把教师姓名换成占位——字段结构与线上一字不差。
 */
class EasPrintDataTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 真实响应切片：两条理论课 + 一门独立实验课 + 一条缺课程名的脏数据 */
    private val fixture = """
        {"studentTableVms":[{
          "id":1,"name":"某同学","activities":[
            {"lessonId":458331,"lessonCode":"154014-007","courseCode":"154014","courseName":"软件工程概论",
             "weekday":2,"startUnit":1,"endUnit":2,"weekIndexes":[1,2,3,4,6,7,8,9,10],
             "room":"中2414","building":"中","campus":"主校区","startTime":"8:00","endTime":"9:40",
             "teachers":["某师"],"experiment":false},
            {"lessonId":458692,"lessonCode":"154013-007","courseCode":"154013","courseName":"计算机网络",
             "weekday":2,"startUnit":3,"endUnit":4,"weekIndexes":[16,1,3,5,7,9,11,13,15],
             "room":"中3302","building":"中","campus":"主校区","startTime":"10:10","endTime":"11:50",
             "teachers":["某师"],"experiment":false},
            {"lessonId":515008,"lessonCode":"515008-012","courseCode":"515008","courseName":"机能学实验",
             "weekday":3,"startUnit":6,"endUnit":8,"weekIndexes":[7],
             "room":"北2_304","building":"北2","campus":"主校区","startTime":"14:55","endTime":"17:40",
             "teachers":["某师"],"experiment":true},
            {"lessonId":0,"lessonCode":null,"courseName":null,
             "weekday":3,"startUnit":6,"endUnit":8,"weekIndexes":[7],
             "experiment":true}
          ],
          "credits":20.5}]
        }
    """.trimIndent()

    @Test
    fun `print-data 响应解析出结构化排课`() {
        val env = json.decodeFromString(EasClient.PrintEnvelope.serializer(), fixture)
        val acts = env.studentTableVms.single().activities
        assertEquals(4, acts.size)
        val exp = acts.first { it.experiment }
        assertEquals("机能学实验", exp.courseName)
        assertEquals(listOf(7), exp.weekIndexes)
        assertEquals(false, acts[0].experiment)
    }

    @Test
    fun `合并把主数据缺的实验段补进来`() {
        val env = json.decodeFromString(EasClient.PrintEnvelope.serializer(), fixture)
        val acts = env.studentTableVms.single().activities

        // 主数据：只有软件工程概论（micro-server 形态，带日期、无实验标志）
        val base = listOf(
            CourseSessionEntity(
                id = "194:154014:1:2:1:",
                courseKey = "194:154014",
                semesterId = 194,
                source = "eas",
                courseName = "软件工程概论",
                weekIndex = 1,
                weekday = 2,
                startUnit = 1,
                endUnit = 2,
                date = "2026-09-08",
                startTime = "08:00",
                endTime = "09:40",
                teacherName = "某师",
                roomText = "中 2414",
                campusText = "主校区",
                color = null,
                experiment = false,
                updatedAt = 0L,
            ),
        )
        val merged = ScheduleRepository.mergePrintActivities(base, acts, semesterId = 194, fetchedAt = 42L)

        // 实验段（第7周 周三6-8节）主数据没有 → 必须被补上，且带 experiment 标志
        val exp = merged.filter { it.courseKey == "194:515008" }
        assertEquals(1, exp.size)
        assertTrue(exp[0].experiment)
        assertEquals(7, exp[0].weekIndex)
        assertEquals(6, exp[0].startUnit)
        assertEquals(8, exp[0].endUnit)
        // 时刻小时补零：print-data 给的是 "14:55" 已合规；此处再验证脏格式归一
        assertEquals("14:55", exp[0].startTime)

        // 理论课：主数据只有第 1 周（带日期），print 数据把 2,3,4,6..10 周补全 → 共 9 周
        val theory = merged.filter { it.courseKey == "194:154014" }.sortedBy { it.weekIndex }
        assertEquals(9, theory.size)
        // 第 1 周键冲突 → 主数据保留（带日期、无实验标志），print 数据不覆盖
        assertEquals("2026-09-08", theory.first { it.weekIndex == 1 }.date)
        assertFalse(theory.first { it.weekIndex == 1 }.experiment)
        // print 补的周没有日期
        assertTrue(theory.first { it.weekIndex == 2 }.date == null)

        // 计算机网络（单周 16,1,3..15）是主数据没有的课 → 按周展开补全
        val net = merged.filter { it.courseKey == "194:154013" }
        assertEquals(9, net.size)
        assertTrue(net.all { !it.experiment })
    }

    @Test
    fun `同键冲突时主数据优先_print数据不覆盖`() {
        val base = listOf(
            CourseSessionEntity(
                id = "194:154014:1:2:1:",
                courseKey = "194:154014",
                semesterId = 194,
                source = "eas",
                courseName = "软件工程概论",
                weekIndex = 1,
                weekday = 2,
                startUnit = 1,
                endUnit = 2,
                date = null,
                startTime = null,
                endTime = null,
                teacherName = null,
                roomText = null,
                campusText = null,
                color = null,
                experiment = false,
                updatedAt = 0L,
            ),
        )
        // print 数据同一键（即使 endUnit 更宽）也不覆盖主数据——主数据带日期，是调休匹配的锚
        val extras = listOf(
            EasClient.PrintActivityDto(
                courseCode = "154014", courseName = "软件工程概论",
                weekday = 2, startUnit = 1, endUnit = 4,
                weekIndexes = listOf(1),
                experiment = false,
            ),
        )
        val merged = ScheduleRepository.mergePrintActivities(base, extras, semesterId = 194, fetchedAt = 0L)
        assertEquals(1, merged.size)
        assertEquals(2, merged[0].endUnit)
        assertFalse(merged[0].experiment)
    }

    @Test
    fun `时刻小时补零`() {
        // print-data 实测给 "8:00"（不补零），micro-server 给 "08:00"——统一成后者
        val extras = listOf(
            EasClient.PrintActivityDto(
                courseCode = "154014", courseName = "软件工程概论",
                weekday = 2, startUnit = 1, endUnit = 2,
                weekIndexes = listOf(2),
                startTime = "8:00", endTime = "9:40",
            ),
        )
        val merged = ScheduleRepository.mergePrintActivities(emptyList(), extras, semesterId = 194, fetchedAt = 0L)
        assertEquals("08:00", merged[0].startTime)
        assertEquals("09:40", merged[0].endTime)
    }
}
