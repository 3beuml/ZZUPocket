package com.zzupocket.app.domain

import java.time.LocalDate

/** 学期 */
data class Semester(
    val id: Int,
    val nameZh: String,
    val schoolYear: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val weekStartOnSunday: Boolean,
    /** 该学期包含哪些教学周（升序），如 1..18 */
    val weekIndices: List<Int>,
    val isCurrent: Boolean,
) {
    val weekCount: Int get() = weekIndices.size
    val firstWeek: Int? get() = weekIndices.minOrNull()
    val lastWeek: Int? get() = weekIndices.maxOrNull()

    /** "2026-2027 第一学期" 这种展示串 */
    val displayName: String
        get() = prettifySemesterName(nameZh, schoolYear)
}

/**
 * 教务下发的 `nameZh` 形如 "2026-2027-1"，直接连上学年会变成
 * "2026-2027 2026-2027-1"（学年重复且学期号不好读）。
 * 这里把它整理成 "2026-2027 第一学期"；不匹配就退回原始拼接。
 */
private fun prettifySemesterName(nameZh: String, schoolYear: String): String {
    val m = Regex("^(\\d{4}-\\d{4})-(\\d+)$").find(nameZh.trim())
    if (m != null) {
        val term = when (m.groupValues[2]) {
            "1" -> "第一学期"
            "2" -> "第二学期"
            "3" -> "第三学期"
            else -> "第${m.groupValues[2]}学期"
        }
        return "${m.groupValues[1]} $term"
    }
    return listOf(schoolYear, nameZh).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * 上课记录。
 *
 * [source] 为后续"自定义日程"预留：教务课程是 `"eas"`，自己加的日程是 `"custom"`。
 * 两者在课表网格里混着显示，但用颜色区分，并且**课程总览只列教务课程**。
 */
data class Session(
    val id: String,
    val semesterId: Int,
    val source: String,
    val courseCode: String,
    val courseName: String,
    val weekIndex: Int,
    val weekday: Int,       // 1..7
    val startUnit: Int,     // 1..10
    val endUnit: Int,
    val date: String?,      // ISO，教务给的真实日期（处理调休靠它）
    val startTime: String?, // "08:00"
    val endTime: String?,
    val teacherName: String?,
    val roomText: String?,
    val campusText: String?,
    val color: String?,
)

/** 一门课（把同一门课的多个上课时段聚在一起） */
data class Course(
    val key: String,
    val semesterId: Int,
    val code: String,
    val nameZh: String,
    val credits: Double,
    val openDepartment: String?,
    val teachers: List<String>,
    val sessions: List<Session>,
) {
    /** 有课的教学周（去重升序） */
    val weeks: List<Int> get() = sessions.map { it.weekIndex }.distinct().sorted()

    /** 去重后的时段：(星期, 起始节, 结束节) */
    val slots: List<Triple<Int, Int, Int>>
        get() = sessions.map { Triple(it.weekday, it.startUnit, it.endUnit) }.distinct()

    val roomTexts: List<String>
        get() = sessions.mapNotNull { it.roomText ?: it.campusText }.distinct()
}

/**
 * 课程详情里的一行"分时段明细"。
 *
 * 同一时段（星期 + 起止节次）如果整学期地点和教师都一样，就只产出一行并把周次合并；
 * 只有真的不同（例如第 9 周换了教室）才会拆成多行、各自带自己的周次。
 * 这样 16 周同一间教室的课不会再被列成 16 行废话。
 */
data class SlotLine(
    val weekday: Int,
    val startUnit: Int,
    val endUnit: Int,
    val room: String?,
    val teacher: String?,
    val weeks: List<Int>,
)

/** 把上课记录按「时段 → （地点, 教师）」两级归并 */
fun List<Session>.toSlotLines(): List<SlotLine> {
    if (isEmpty()) return emptyList()
    return groupBy { Triple(it.weekday, it.startUnit, it.endUnit) }
        .flatMap { (slot, sameSlot) ->
            sameSlot.groupBy { (it.roomText ?: "") to (it.teacherName ?: "") }
                .map { (_, sameWho) ->
                    val first = sameWho.first()
                    SlotLine(
                        weekday = slot.first,
                        startUnit = slot.second,
                        endUnit = slot.third,
                        room = first.roomText ?: first.campusText,
                        teacher = first.teacherName,
                        weeks = sameWho.map { it.weekIndex }.distinct().sorted(),
                    )
                }
        }
        .sortedWith(compareBy({ it.weekday }, { it.startUnit }))
}

/** 节次（含起止时间，来自 /room/system/course-units） */
data class Period(
    val indexNo: Int,
    val nameZh: String,
    val startTime: Int,   // 800 表示 08:00
    val endTime: Int,     // 845 表示 08:45
    val dayPart: String,  // MORNING / AFTERNOON / EVENING
    val segmentIndex: Int,
) {
    /** "08:00" */
    val startText: String get() = formatHhmm(startTime)
    val endText: String get() = formatHhmm(endTime)

    private fun formatHhmm(v: Int): String {
        val s = v.toString().padStart(4, '0')
        return "${s.substring(0, 2)}:${s.substring(2)}"
    }
}
