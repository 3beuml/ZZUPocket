package com.zzupocket.app.data.gms

import com.zzupocket.app.data.gms.GmsClient.RowDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * gmsstu（研究生教务）课表解析的单元测试。
 *
 * 夹具是 2026-10 用真实研究生账号实抓的响应原文（11 行整学期课表 + 7 组本周课表），
 * 唯一改动是把教师姓名替换成了占位名（某甲/乙/丙/丁/戊）——
 * 结构、课号、教室、周次、班级名全部与线上原样一致。
 */
class GmsScheduleParserTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── parseCell：两种形态 ─────────────────────────────────────────────

    @Test
    fun `形态一 带课程号的一节大课`() {
        val e = GmsScheduleParser.parseCell("新型传感技术及应用135505702_001[10-17周]某甲[北1301]")!!
        assertEquals("新型传感技术及应用", e.courseName)
        assertEquals("135505702_001", e.courseCode)
        assertNull(e.className)
        assertEquals("10-17周", e.weekText)
        assertEquals("某甲", e.teacher)
        assertEquals("北1301", e.room)
        assertTrue(!e.selective)
    }

    @Test
    fun `形态二 不带课程号 按校区关键词切开班名`() {
        val e = GmsScheduleParser.parseCell("数值分析主校区数值分析4班[2-17周]某戊[北1204]")!!
        assertEquals("数值分析", e.courseName)
        assertNull(e.courseCode)
        assertEquals("主校区数值分析4班", e.className)
        assertEquals("2-17周", e.weekText)
        assertEquals("某戊", e.teacher)
        assertEquals("北1204", e.room)
    }

    @Test
    fun `形态二 政治课同样切开`() {
        val e = GmsScheduleParser.parseCell("自然辩证法概论主校区政治20班[7-10周]某丙[南4201]")!!
        assertEquals("自然辩证法概论", e.courseName)
        assertNull(e.courseCode)
        assertEquals("主校区政治20班", e.className)
        assertEquals("7-10周", e.weekText)
        assertEquals("南4201", e.room)
    }

    @Test
    fun `选课式课程的井号标记得剥掉`() {
        val e = GmsScheduleParser.parseCell("新型传感技术及应用135505702_001[10-17周]某甲[北1301]##")!!
        assertTrue(e.selective)
        assertEquals("新型传感技术及应用", e.courseName)
    }

    @Test
    fun `空文本和没有方括号段的脏数据返回null`() {
        assertNull(GmsScheduleParser.parseCell(null))
        assertNull(GmsScheduleParser.parseCell(""))
        assertNull(GmsScheduleParser.parseCell("一点方括号都没有的文本"))
    }

    // ── parseWeeks ─────────────────────────────────────────────────────

    @Test
    fun `周次区间与单周`() {
        assertEquals(listOf(10..17), GmsScheduleParser.parseWeeks("10-17周"))
        assertEquals(listOf(7..7), GmsScheduleParser.parseWeeks("7周"))
    }

    @Test
    fun `周次带连续标记与节次尾巴也能取到区间`() {
        // 本周视图的 ZS 文本：“2-17周[连续],第7节,第8节”
        assertEquals(listOf(2..17), GmsScheduleParser.parseWeeks("2-17周[连续],第7节,第8节"))
        assertNull(GmsScheduleParser.parseWeeks(null))
        assertNull(GmsScheduleParser.parseWeeks("没有周字的文本"))
    }

    // ── 整学期课表：原始 11 行 fixture 的骨架断言 ─────────────────────

    /** 与线上一字不差的 11 行（教师姓名占位：甲乙丙丁戊），字段顺序/空白与线上等价 */
    private val fixture11 = """
        {"rows":[
          {"id":null,"transientData":{},"attachments":null,"jcid":"1","mc":"第1节","sjbz":null,
           "z1":"新型传感技术及应用135505702_001[10-17周]某甲[北1301]","z2":null,
           "z3":"计量科学与技术135505602_002[10-17周]某乙[北1303]","z4":null,"z5":null,"z6":null,"z7":null,
           "xq":null,"jsxm":null,"xkfs":"2"},
          {"id":null,"transientData":{},"attachments":null,"jcid":"2","mc":"第2节","sjbz":null,
           "z1":"新型传感技术及应用135505702_001[10-17周]某甲[北1301]","z2":null,
           "z3":"计量科学与技术135505602_002[10-17周]某乙[北1303]","z4":null,"z5":null,"z6":null,"z7":null,
           "xq":null,"jsxm":null,"xkfs":"2"},
          {"id":null,"transientData":{},"attachments":null,"jcid":"3","mc":"第3节","sjbz":null,
           "z1":"新型传感技术及应用135505702_001[10-17周]某甲[北1301]","z2":null,
           "z3":"计量科学与技术135505602_002[10-17周]某乙[北1303]","z4":null,"z5":null,"z6":null,"z7":null,
           "xq":null,"jsxm":null,"xkfs":"2"},
          {"id":null,"transientData":{},"attachments":null,"jcid":"4","mc":"第4节","sjbz":null,
           "z1":"新型传感技术及应用135505702_001[10-17周]某甲[北1301]","z2":null,
           "z3":"计量科学与技术135505602_002[10-17周]某乙[北1303]","z4":null,"z5":null,"z6":null,"z7":null,
           "xq":null,"jsxm":null,"xkfs":"2"},
          {"id":null,"transientData":{},"attachments":null,"jcid":"5","mc":"第5节","sjbz":null,
           "z1":null,"z2":"自然辩证法概论主校区政治20班[7-10周]某丙[南4201]","z3":null,"z4":null,"z5":null,"z6":null,
           "z7":"研究生学术论文写作指导135606501_002[2-5周]某丁[北4101]","xq":null,"jsxm":null,"xkfs":"2"},
          {"id":null,"transientData":{},"attachments":null,"jcid":"6","mc":"第6节","sjbz":null,
           "z1":null,"z2":"自然辩证法概论主校区政治20班[7-10周]某丙[南4201]","z3":null,"z4":null,"z5":null,"z6":null,
           "z7":"研究生学术论文写作指导135606501_002[2-5周]某丁[北4101]","xq":null,"jsxm":null,"xkfs":"2"},
          {"id":null,"transientData":{},"attachments":null,"jcid":"7","mc":"第7节","sjbz":null,
           "z1":"数值分析主校区数值分析4班[2-17周]某戊[北1204]","z2":"自然辩证法概论主校区政治20班[7-10周]某丙[南4201]","z3":null,"z4":null,"z5":null,"z6":null,
           "z7":"研究生学术论文写作指导135606501_002[2-5周]某丁[北4101]","xq":null,"jsxm":null,"xkfs":"2"},
          {"id":null,"transientData":{},"attachments":null,"jcid":"8","mc":"第8节","sjbz":null,
           "z1":"数值分析主校区数值分析4班[2-17周]某戊[北1204]","z2":"自然辩证法概论主校区政治20班[7-10周]某丙[南4201]","z3":null,"z4":null,"z5":null,"z6":null,
           "z7":"研究生学术论文写作指导135606501_002[2-5周]某丁[北4101]","xq":null,"jsxm":null,"xkfs":"2"},
          {"id":null,"transientData":{},"attachments":null,"jcid":"9","mc":"第9节","sjbz":null,
           "z1":null,"z2":null,"z3":null,"z4":null,"z5":null,"z6":null,"z7":null,"xq":null,"jsxm":null,"xkfs":null},
          {"id":null,"transientData":{},"attachments":null,"jcid":"10","mc":"第10节","sjbz":null,
           "z1":null,"z2":null,"z3":null,"z4":null,"z5":null,"z6":null,"z7":null,"xq":null,"jsxm":null,"xkfs":null},
          {"id":null,"transientData":{},"attachments":null,"jcid":"99","mc":"无节次","sjbz":null,
           "z1":null,"z2":null,"z3":null,"z4":null,"z5":null,"z6":null,"z7":null,"xq":null,"jsxm":null,"xkfs":null}
        ]}
    """.trimIndent()

    @Test
    fun `11行真实模板解析出18条课`() {
        val client = GmsClient(OkHttpStub.CLIENT, json)
        val table = json.decodeFromString(GmsClient.TermTableDto.serializer(), fixture11)
        val lessons = client.rowsToSession(table.rows)
        assertEquals(18, lessons.size)
        // 五门课
        assertEquals(
            setOf("新型传感技术及应用", "计量科学与技术", "自然辩证法概论", "研究生学术论文写作指导", "数值分析"),
            lessons.map { it.courseName }.toSet(),
        )
    }

    @Test
    fun `新型传感 周一到第4节 周次10到17`() {
        val client = GmsClient(OkHttpStub.CLIENT, json)
        val table = json.decodeFromString(GmsClient.TermTableDto.serializer(), fixture11)
        val s = client.rowsToSession(table.rows).filter { it.courseName == "新型传感技术及应用" }
        assertEquals(4, s.size)
        s.forEach {
            assertEquals(1, it.weekday)
            assertEquals(10..17, it.weeks)
            assertEquals("135505702_001", it.courseCode)
            assertEquals("北1301", it.room)
        }
        assertEquals(setOf(1, 2, 3, 4), s.map { it.period }.toSet())
    }

    @Test
    fun `数值分析摊在学校班名上 无课程号但班级与周次齐全`() {
        val client = GmsClient(OkHttpStub.CLIENT, json)
        val table = json.decodeFromString(GmsClient.TermTableDto.serializer(), fixture11)
        val s = client.rowsToSession(table.rows).filter { it.courseName == "数值分析" }
        assertEquals(2, s.size)
        s.forEach {
            assertEquals(1, it.weekday)
            assertEquals(2..17, it.weeks)
            assertNull(it.courseCode)
            assertEquals("主校区数值分析4班", it.className)
        }
    }

    @Test
    fun `无节次占位行 jcid99 不产课`() {
        val client = GmsClient(OkHttpStub.CLIENT, json)
        val rows = listOf(
            RowDto(jcid = "99", mc = "无节次", z1 = "数值分析主校区数值分析4班[2-17周]某戊[北1204]"),
        )
        assertTrue(client.rowsToSession(rows).isEmpty())
    }

    @Test
    fun `周区间展开成每周一条的网格条目`() {
        val client = GmsClient(OkHttpStub.CLIENT, json)
        val table = json.decodeFromString(GmsClient.TermTableDto.serializer(), fixture11)
        val lessons = client.rowsToSession(table.rows)
        val entries = client.expandWeeks(lessons)
        // 新型传感 4条×8周 + 计量 4×8 + 政治课 4×4 + 学术论文 4×4 + 数值分析 2×16
        assertEquals(128, entries.size)
        // 第10周周一：4 条 新型传感(第1..4节) + 2 条 数值分析(第7/8节)
        val wk10 = entries.filter { it.weekIndex == 10 && it.weekday == 1 }
        assertEquals(6, wk10.size)
        assertTrue(wk10.count { it.courseName == "新型传感技术及应用" } == 4)
        assertTrue(wk10.count { it.courseName == "数值分析" && it.className == "主校区数值分析4班" } == 2)
        // 第3周周一只有 数值分析(第7/8节)
        val wk3mon = entries.filter { it.weekIndex == 3 && it.weekday == 1 }
        assertTrue(wk3mon.all { it.courseName == "数值分析" })
        // 第16周研究生学术写作课已结课（区间 2-5）
        assertTrue(entries.none { it.weekIndex == 16 && it.courseName == "研究生学术论文写作指导" })
    }

    @Test
    fun `学期下拉结构解析并识别当前学期`() {
        val terms = json.decodeFromString(
            GmsClient.TermListDto.serializer(),
            """{"termcode":[
                {"value":"20261","text":"2026-2027第一学期","selected":true},
                {"value":"20262","text":"2026-2027第二学期","selected":false}]}""",
        ).termcode
        assertEquals(2, terms.size)
        assertTrue(terms[0].selected)
        assertEquals("20261", terms[0].value)
        assertEquals("2026-2027第一学期", terms[0].text)
    }

    // ── 路由编码：与实测串比对过的规则 ────────────────────────────────

    @Test
    fun `路由编码与实测字节完全一致`() {
        val guid = "deadbeef-1234-4567-89ab-cdef01234567"
        assertEquals(
            "TXlIZWFydFdpbGxHb09ucHlnbC94c2tiY3gvbG9hZEtiI2RlYWRiZWVmLTEyMzQtNDU2Ny04OWFiLWNkZWYwMTIzNDU2Nw__",
            GmsClient.encodeRoute("pygl/xskbcx/loadKb", guid),
        )
        assertEquals(
            "TXlIZWFydFdpbGxHb09ucHlnbC94c2tiY3gva2JjeF9kcm9wI2RlYWRiZWVmLTEyMzQtNDU2Ny04OWFiLWNkZWYwMTIzNDU2Nw__",
            GmsClient.encodeRoute("pygl/xskbcx/kbcx_drop", guid),
        )
    }

    /** 仅占位：单元测试不联网，本测试类的客户端永远不会真正发请求 */
    private object OkHttpStub {
        val CLIENT = okhttp3.OkHttpClient()
    }
}
