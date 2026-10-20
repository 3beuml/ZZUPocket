package com.zzupocket.app.data.gms

import com.zzupocket.app.domain.Course
import com.zzupocket.app.domain.Period
import com.zzupocket.app.domain.Semester
import com.zzupocket.app.domain.Session
import java.time.LocalDate

/**
 * 把研究生教务的整学期条目映射成本科课表在用的 [Session] / [Course]。
 *
 * 这样研究生课表可以直接复用 `ScheduleGrid / WeekPager`，不再单独维护一套列表 UI；
 * 周次、跨节次合并、课程详情、配色都与本科保持一致。
 */
object GmsScheduleMapper {

    /** 合成学期 ID：负值，避免与数据库里的本科教务学期冲突 */
    const val GRAD_SEMESTER_ID = -10_001

    /**
     * 研究生系统页面只给「第N节」，不提供时刻；这里用学校官方《作息时间表》兜底
     * （2026-10-18 用户提供：上午 1-4、下午 5-8、晚间 9-10）。
     * 只收录 10 节课，起床/晨读/就餐/课间操/晚自习/熄灯等辅助项不进入课表。
     */
    fun defaultPeriods(): List<Period> = listOf(
        Period(1, "第1节", 800, 845, "MORNING", 1),
        Period(2, "第2节", 855, 940, "MORNING", 1),
        Period(3, "第3节", 1010, 1055, "MORNING", 2),
        Period(4, "第4节", 1105, 1150, "MORNING", 2),
        Period(5, "第5节", 1400, 1445, "AFTERNOON", 3),
        Period(6, "第6节", 1455, 1540, "AFTERNOON", 3),
        Period(7, "第7节", 1600, 1645, "AFTERNOON", 4),
        Period(8, "第8节", 1655, 1740, "AFTERNOON", 4),
        Period(9, "第9节", 1900, 1945, "EVENING", 5),
        Period(10, "第10节", 1955, 2040, "EVENING", 5),
    )

    /**
     * 一格一课 → 可进网格的 [Session]。
     *
     * 研究生接口按「一天一格」下发；同一门课连上两节时会变成两条相邻记录。
     * 这里把同一天、同一门课、同一教师/教室的连续节次合并成一个块，
     * 与本科「跨节次只画一个块」的表现一致。
     */
    fun toSessions(
        entries: List<GmsClient.WeekEntry>,
        periods: List<Period> = emptyList(),
    ): List<Session> {
        if (entries.isEmpty()) return emptyList()
        val groups = entries.groupBy {
            listOf(
                it.weekIndex,
                it.weekday,
                it.courseCode?.takeIf(String::isNotBlank) ?: it.courseName.trim(),
                it.teacher.orEmpty(),
                it.room.orEmpty(),
            )
        }
        val out = ArrayList<Session>(entries.size)
        for (group in groups.values) {
            val first = group.first()
            val key = first.courseCode?.takeIf(String::isNotBlank) ?: first.courseName.trim()
            val units = group.map { it.period }.distinct().sorted()
            for (run in consecutiveRuns(units)) {
                val start = periods.firstOrNull { it.indexNo == run.first }
                val end = periods.firstOrNull { it.indexNo == run.last }
                out += Session(
                    id = "gms:${first.weekIndex}:${first.weekday}:${run.first}-${run.last}:$key",
                    semesterId = GRAD_SEMESTER_ID,
                    source = GmsScheduleSource.SOURCE,
                    courseCode = key,
                    courseName = first.courseName,
                    weekIndex = first.weekIndex,
                    weekday = first.weekday,
                    startUnit = run.first,
                    endUnit = run.last,
                    date = null,
                    startTime = start?.startText,
                    endTime = end?.endText,
                    teacherName = first.teacher,
                    roomText = first.room,
                    campusText = null,
                    color = null,
                )
            }
        }
        return out.sortedWith(compareBy({ it.weekIndex }, { it.weekday }, { it.startUnit }))
    }

    /** 把同一门课的多个时段聚成 [Course]（课程详情、配色、总览都用它）。 */
    fun toCourses(sessions: List<Session>): List<Course> =
        sessions.groupBy { it.courseCode }
            .map { (key, list) ->
                val first = list.first()
                Course(
                    key = "gms:$key",
                    semesterId = GRAD_SEMESTER_ID,
                    code = key,
                    nameZh = first.courseName,
                    credits = 0.0,
                    openDepartment = null,
                    teachers = list.mapNotNull { it.teacherName }.distinct(),
                    sessions = list,
                )
            }
            .sortedBy { it.nameZh }

    /**
     * 研究生学期：周次来自整学期课表。
     *
     * 第 1 周周一的来源按可信度排序：
     * 1. [anchoredMonday] —— 已落库/用户手动校正过的锚点（最可信，直接采用）；
     * 2. 由「本周课表」反推的当前周（[anchoredWeek] 或现场反推），再用
     *    [GmsWeekAlign.weekOneMonday] 倒推。
     *
     * 两者都没有时 [Semester.startDate] 为 null —— 网格仍能显示，只是表头不显示日期。
     */
    fun toSemester(
        term: GmsClient.TermDto,
        entries: List<GmsClient.WeekEntry>,
        current: List<GradLesson>,
        today: LocalDate,
        anchoredMonday: LocalDate? = null,
        anchoredWeek: Int? = null,
    ): Semester {
        val weeks = entries.map { it.weekIndex }.distinct().sorted()
        val first = weeks.firstOrNull() ?: 1
        val last = weeks.lastOrNull() ?: first
        val currentWeek = anchoredWeek ?: GmsWeekAlign.currentWeekIndex(entries, current)
        return Semester(
            id = GRAD_SEMESTER_ID,
            nameZh = term.text.ifBlank { "研究生课表" },
            schoolYear = "",
            startDate = anchoredMonday ?: currentWeek?.let { GmsWeekAlign.weekOneMonday(today, it) },
            endDate = null,
            weekStartOnSunday = false,
            weekIndices = (first..last).toList(),
            isCurrent = true,
        )
    }

    /** 连续节次合并：`[1,2,3,5,6]` → `[1..3, 5..6]` */
    private fun consecutiveRuns(units: List<Int>): List<IntRange> {
        if (units.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var start = units.first()
        var prev = start
        for (unit in units.drop(1)) {
            if (unit == prev + 1) {
                prev = unit
            } else {
                out += start..prev
                start = unit
                prev = unit
            }
        }
        out += start..prev
        return out
    }
}
