package com.zzupocket.app.domain

/**
 * 自定义日程（自习、实验、社团、家教……）。
 *
 * 它和教务课程共用 `course_sessions` 表，靠 `source = "custom"` 区分：
 * 网格、今天、翻周这些现成的逻辑不用改就能混着显示，
 * 而且教务刷新时只删 `source = "eas"` 的行，**不会把自定义日程冲掉**。
 */
/**
 * 条目类型：「日程」是自习/社团这类安排；「课程」是**手动补录的课**
 * （学校接口漏给时用户自己补，issue #7 医学生缺课的兜底）。
 * 两者存储完全同构（source 都是 "custom"），类型只影响编辑器的字段
 * （课程多一个教师输入框）和列表里的标注；类型记在 code 前缀上（C/P），零迁移。
 */
enum class PlanKind(val label: String) {
    EVENT("日程"),
    COURSE("课程"),
    ;

    companion object {
        /** 新建时生成 code 的前缀；老数据全是 P，自然归为日程 */
        fun codePrefix(kind: PlanKind): String = if (kind == COURSE) "C" else "P"

        fun ofCode(code: String?): PlanKind = if (code?.startsWith("C") == true) COURSE else EVENT
    }
}

data class Plan(
    /** 日程自己的标识（存在 courseCode 里），改周次时用它定位旧记录 */
    val code: String,
    val semesterId: Int,
    val name: String,
    val weekday: Int,     // 1..7，周一 = 1
    val startUnit: Int,
    val endUnit: Int,
    /** 出现在哪些教学周（升序） */
    val weeks: List<Int>,
    val roomText: String?,
    /** [PlanColor] 的 name；为空用默认色 */
    val colorKey: String?,
    /** 手动补录课程的主讲老师（可空）；日程恒为 null */
    val teacherName: String? = null,
) {
    val weekCount: Int get() = weeks.size
    val kind: PlanKind get() = PlanKind.ofCode(code)
}

/** 新建或编辑时提交的内容（[code] 为空 = 新建） */
data class PlanSpec(
    val code: String? = null,
    val semesterId: Int,
    val name: String,
    val weekday: Int,
    val startUnit: Int,
    val endUnit: Int,
    val weeks: List<Int>,
    val roomText: String? = null,
    val colorKey: String? = null,
    val teacherName: String? = null,
    val kind: PlanKind = PlanKind.EVENT,
)

/**
 * 自定义日程的配色。
 *
 * 刻意用一组固定色而不是从课程代码算 HSL —— 用户是**手动挑**颜色的，
 * 挑完就得一直是他挑的那个色，不能因为算出来的色相接近就换掉。
 */
enum class PlanColor(val label: String, val argb: Long) {
    RED("红", 0xFFE57373),
    ORANGE("橙", 0xFFFFB74D),
    YELLOW("黄", 0xFFFFD54F),
    GREEN("绿", 0xFF81C784),
    TEAL("青", 0xFF4DB6AC),
    BLUE("蓝", 0xFF64B5F6),
    PURPLE("紫", 0xFFBA68C8),
    GRAY("灰", 0xFF90A4AE),
    ;

    companion object {
        val DEFAULT = BLUE
        fun of(name: String?): PlanColor? = entries.firstOrNull { it.name == name }
    }
}

/** 周次模式：全部周 / 单周 / 双周 —— 覆盖绝大多数真实需求 */
enum class WeekMode(val label: String) {
    ALL("每周"),
    ODD("单周"),
    EVEN("双周"),
    ;

    fun filter(weeks: List<Int>): List<Int> = when (this) {
        ALL -> weeks
        ODD -> weeks.filter { it % 2 == 1 }
        EVEN -> weeks.filter { it % 2 == 0 }
    }
}
