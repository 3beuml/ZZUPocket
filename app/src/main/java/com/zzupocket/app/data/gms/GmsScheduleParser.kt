package com.zzupocket.app.data.gms

/**
 * gmsstu（研究生教务）课表单元格（z1..z7）文本的解析。
 *
 * 服务端把一门课压成一段平文本，实测有两种形态：
 *
 *  ① 带课程号：`课程名{课程号}_{班次}[周次]教师[教室]`
 *     例：`新型传感技术及应用135505702_001[10-17周]教师甲[北1301]`
 *  ② 不带课程号：`课程名(班级名)[周次]教师[教室]`
 *     例：`数值分析主校区数值分析4班[2-17周]教师乙[北1204]`
 *
 * 两者共通的骨架：
 *  - `[周次]`：第一个内容里带「周」字的方括号段（里面形如 `10-17周`、`7-10周`）；
 *  - `[教室]`：其中最后一个方括号段；
 *  - 两段之间的平文本是教师名；周次之前的平文本是「课程名（+ 课程号班次 / 班级名）」；
 *  - 单元格出现 `##` 前缀表示选课式课程（网页会渲染成 C_kc_xuanke 样式），解析时剥掉即可；
 *  - 教师姓名没有独立字段可用：响应顶层 `jsxm`（教师姓名）实测恒为 null。
 *
 * ⚠️ 一格多条（2026-10-25 实测，issue #5 的根因）：**同一时段、不同周次的多条排课会被
 * 服务端压进同一个单元格，用 `<br/>` 分隔**。必须先拆开逐条解析——否则只有第一条生效，
 * 其余条目的周次整段丢失（后期的课直接消失）；`<br/>` 与后续课名会被灌进教师字段
 * （跨节次块在网格里显示教师，用户看到「字母片段」）；教室取最后一段，还会串段。
 * 实测样例见 GmsScheduleParserTest。
 *
 * 课程号可缺、班级名不可拆分是常态，所以这里**不做强约束**：
 * 任何字段缺失都允许为 null；某一段连「周次段」都找不到时只丢那一段，
 * 宁可丢一条脏数据，也不能让整张课表解析失败。
 */
object GmsScheduleParser {

    /** 单个单元格解析结果（尚未展开成多个教学周） */
    data class CellEntry(
        val courseName: String,

        /** 课程号_班次（如 `135505702_001`），无课程号的课程为 null */
        val courseCode: String? = null,

        /** 班级名（如 `主校区数值分析4班` / `主校区政治20班`）。 */
        val className: String? = null,

        /** 周次原文（如 `10-17周` / `7-10周`）。用 [parseWeeks] 展开成周号区间。 */
        val weekText: String? = null,

        val teacher: String? = null,
        val room: String? = null,

        /** `##` 前缀：选课式课程标记 */
        val selective: Boolean = false,
    )

    private val BRACKET = Regex("\\[([^\\[\\]]+)\\]")

    /** 一格多条的分隔：`<br/>`/`<br>`/`<br />`（大小写不敏感）与裸换行 */
    private val SEGMENT_SPLIT = Regex("(?i)<br\\s*/?>|\\n")

    /** 字段里残留的 HTML 片段（标签、`&nbsp;`）——显示前剥掉 */
    private val HTML_RESIDUE = Regex("<[^>]*>|&nbsp;?")

    /**
     * 把一格文本拆成若干条 [CellEntry]。
     *
     * 一格一条时结果只有一个元素；某一段格式不符（无 [] 段）就丢那一段。
     */
    fun parseCellEntries(raw: String?): List<CellEntry> {
        var text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val selective = text.contains("##")
        text = text.replace("##", "").trim()
        if (text.isEmpty()) return emptyList()
        return SEGMENT_SPLIT.split(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parseSegment(it, selective) }
    }

    /** 兼容旧调用方：等价于 [parseCellEntries] 的首条。 */
    fun parseCell(raw: String?): CellEntry? = parseCellEntries(raw).firstOrNull()

    private fun parseSegment(text: String, selective: Boolean): CellEntry? {
        val groups = BRACKET.findAll(text).toList()
        if (groups.isEmpty()) return null

        // 周次段 = 第一个带「周」的 []；没有这样的段就退回第一个 []（兼容将来改版）
        val weekGroup = groups.firstOrNull { it.groupValues[1].contains("周") } ?: groups.first()
        val weekText = weekGroup.groupValues[1]

        // 周次之后的所有文本：`教师[教室]`
        val tail = text.substring(weekGroup.range.last + 1).trim()
        val room = tail.substringAfterLast('[').substringBefore(']').trim()
            .takeIf { tail.contains('[') && it.isNotEmpty() }
        val teacher = (if (tail.contains('[')) tail.substringBeforeLast('[') else tail).trim()
            .takeIf { it.isNotEmpty() }
        // ⚠️ 教师与教室中间没有分隔符，上面的规则只对「教师紧挨周次段、教室在最后」成立；
        // 一格多课导致的串段已由 [parseCellEntries] 按 <br/> 拆分解决，剩下的脏数据宁缺毋滥。

        // 周次之前的文本：课程名 + 代码_班次 / 班级名
        val head = text.substring(0, weekGroup.range.first).trim()
        val codeMatch = Regex("(\\d{6,10})_(\\d{1,3})\\s*$").find(head)
        val courseCode = codeMatch?.let { "${it.groupValues[1]}_${it.groupValues[2]}" }
        val nameClass = if (codeMatch != null) head.substring(0, codeMatch.range.first) else head

        // 无课程号的课程：以校区关键词把课程名与班级名切开
        val campusMark = Regex("(主校区|南校区|北校区|东校区)")
        var courseName = clean(nameClass.trim())
        var className: String? = null
        if (courseCode == null) {
            val m = campusMark.find(nameClass)
            if (m != null) {
                courseName = clean(nameClass.substring(0, m.range.first).trim())
                className = clean(nameClass.substring(m.range.first).trim())
                    .takeIf { it.isNotEmpty() }
            }
        }

        if (courseName.isEmpty()) return null
        return CellEntry(
            courseName = courseName,
            courseCode = courseCode,
            className = className,
            weekText = weekText,
            teacher = teacher?.let(::clean),
            room = room?.let(::clean),
            selective = selective,
        )
    }

    /** HTML 残留 → 空格，再压缩多余空白；不动中英文正文 */
    private fun clean(text: String): String =
        HTML_RESIDUE.replace(text, " ").replace(Regex("\\s+"), " ").trim()

    /**
     * 周次文本 → 周号区间列表。
     *
     * 实测出现过的写法：`10-17周` / `7-10周` / `2-5周[连续]`。`[连续]` 只是网页样式
     * 标记，剥掉即可；`X 周` 单值；多段逗号列表（如 `1,2,3周`）目前未在数据里出现，
     * 暂只按整段解析，宁可保守只返回能确定的部分。
     */
    fun parseWeeks(raw: String?): List<IntRange>? {
        if (raw == null) return null
        val s = raw.replace(" ", "")
        if (s.isEmpty()) return null
        val out = mutableListOf<IntRange>()
        Regex("(\\d+)(?:-(\\d+))?周").findAll(s).forEach { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2]
            out.add(if (b.isEmpty()) a..a else a..b.toInt())
        }
        return out.ifEmpty { null }
    }
}
