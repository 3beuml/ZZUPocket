package com.zzupocket.app.data.repo

import android.util.Log
import androidx.room.withTransaction
import com.zzupocket.app.data.auth.AuthRepository
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.db.CourseEntity
import com.zzupocket.app.data.db.CourseSessionEntity
import com.zzupocket.app.data.db.PeriodEntity
import com.zzupocket.app.data.db.SemesterEntity
import com.zzupocket.app.data.eas.EasClient
import com.zzupocket.app.domain.Course
import com.zzupocket.app.domain.Period
import com.zzupocket.app.domain.Plan
import com.zzupocket.app.domain.PlanSpec
import com.zzupocket.app.domain.Semester
import com.zzupocket.app.domain.Session
import com.zzupocket.app.domain.WeekAlignment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * 课表仓库。
 *
 * 设计要点：**拉一次整学期，之后全部本地算**。切周、课程详情、课程总览都不再发请求，
 * 所以课表页很快而且**离线可用**。
 *
 * 教学周号由服务端下发（每条上课记录自带 `weekIndex`），"今天的课"优先按**真实日期**匹配
 * ——这样学校调休/调课时不需要任何特殊处理。
 */
class ScheduleRepository(
    private val db: AppDatabase,
    private val eas: EasClient,
    private val auth: AuthRepository,
) {

    // ── 同步 ─────────────────────────────────────────────────────────────

    /** 拉学期列表；如果服务端给了当前学期就标记它，否则按日期推断 */
    suspend fun refreshSemesters() {
        val token = auth.requireCasToken()
        val list = eas.fetchSemesters(token)
        if (list.isEmpty()) return

        val currentId = runCatching { eas.fetchCurrentSemester(token)?.id }.getOrNull()
            ?: inferCurrentSemesterId(list)

        val now = System.currentTimeMillis()
        db.withTransaction {
            db.semesterDao().upsertAll(
                list.map { it.toEntity(isCurrent = it.id == currentId, now = now) },
            )
            db.semesterDao().clearCurrent()
            if (currentId != null) db.semesterDao().markCurrent(currentId)
        }
    }

    /** 服务端没给当前学期时，用日期推断：今天的日期落在哪个学期区间里 */
    private fun inferCurrentSemesterId(list: List<EasClient.SemesterDto>): Int? {
        val today = LocalDate.now().toString()
        return list.firstOrNull { s ->
            val a = s.startDate?.take(10)
            val b = s.endDate?.take(10)
            a != null && b != null && today >= a && today <= b
        }?.id ?: list.firstOrNull()?.id
    }

    /**
     * 拉整学期课表并落库。
     * 只清理 `source = "eas"` 的记录 —— **自定义日程不能被误删**（P3 起会有）。
     */
    suspend fun refreshCourseTable(semesterId: Int) {
        val token = auth.requireCasToken()
        val sessions = eas.fetchCourseTable(token, semesterId)
        val semester = semester(semesterId)
        val now = System.currentTimeMillis()

        // 一门课 = 按课程代码聚合，sessions 全部挂在它下面
        val grouped = sessions.groupBy { it.courseCode }
        val courseEntities = grouped.map { (code, list) ->
            val first = list.first()
            CourseEntity(
                key = courseKeyOf(semesterId, code),
                semesterId = semesterId,
                code = code,
                nameZh = first.courseName,
                credits = first.credits,
                openDepartment = first.openDepartment,
                teachersCsv = first.teachers.joinToString(","),
                updatedAt = now,
            )
        }
        val sessionEntities = sessions.map { s ->
            // ⚠️ 学校的**临时补课**经常只给日期、不给教学周/星期（`weekIndex`/`weekday` 是 0）。
            // 按日期把这两样补出来：这样它既能在"今天"按日期匹配到，
            // 也能落进对应教学周的网格里（否则网格永远看不到它）。
            val derived = weekdayAndWeekOf(semester, s.date)
            val weekday = if (s.weekday in 1..7) s.weekday else derived?.first ?: 0
            val weekIndex = if (s.weekIndex > 0) s.weekIndex else derived?.second ?: 0
            CourseSessionEntity(
                // 主键带上日期：同一门课在同一周同一天同一节的**补课**不能覆盖正常那节课，
                // 两条都得留（以前 id 不含日期，后写的一条会把前一条顶掉）
                id = sessionIdOf(semesterId, s.courseCode, weekIndex, weekday, s.startUnit, s.date),
                courseKey = courseKeyOf(semesterId, s.courseCode),
                semesterId = semesterId,
                source = SOURCE_EAS,
                courseName = s.courseName,
                weekIndex = weekIndex,
                weekday = weekday,
                startUnit = s.startUnit,
                endUnit = s.endUnit,
                date = s.date,
                startTime = com.zzupocket.app.core.ScheduleText.timeOfDay(s.startTime),
                endTime = com.zzupocket.app.core.ScheduleText.timeOfDay(s.endTime),
                teacherName = s.teacherName,
                roomText = s.roomText,
                campusText = s.campusText,
                color = null,
                updatedAt = now,
            )
        }

        db.withTransaction {
            db.courseSessionDao().deleteEasBySemester(semesterId)
            db.courseDao().deleteBySemester(semesterId)
            db.courseDao().upsertAll(courseEntities)
            db.courseSessionDao().upsertAll(sessionEntities)
        }
        logGridCollisions(sessionEntities)
    }

    /** 节次时刻表（作息）。内容基本不变，拉一次长期有效。 */
    suspend fun refreshPeriods() {
        val token = auth.requireCasToken()
        val list = eas.fetchPeriods(token)
        if (list.isEmpty()) return
        val now = System.currentTimeMillis()
        db.periodDao().upsertAll(
            list.mapNotNull { p ->
                if (p.indexNo <= 0) null else PeriodEntity(
                    indexNo = p.indexNo,
                    nameZh = p.nameZh,
                    startTime = p.startTime,
                    endTime = p.endTime,
                    dayPart = p.dayPart,
                    segmentIndex = p.segmentIndex,
                    updatedAt = now,
                )
            },
        )
    }

    // ── 读取 ─────────────────────────────────────────────────────────────

    fun observeSemesters(): Flow<List<Semester>> =
        db.semesterDao().observeAll().map { list -> list.map { it.toDomain() } }

    fun observeCourses(semesterId: Int): Flow<List<Course>> =
        combine(
            db.courseDao().observeBySemester(semesterId),
            db.courseSessionDao().observeBySemester(semesterId),
        ) { courses, sessions -> buildCourses(courses, sessions.map { it.toDomain() }) }

    fun observePeriods(): Flow<List<Period>> =
        db.periodDao().observeAll().map { list -> list.map { it.toDomain() } }

    /** 番茄钟标签候选：课名 */
    fun observeCourseNames(): Flow<List<String>> = db.courseDao().observeAllNames()

    suspend fun periods(): List<Period> = db.periodDao().all().map { it.toDomain() }

    suspend fun semesters(): List<Semester> = db.semesterDao().all().map { it.toDomain() }

    suspend fun semester(id: Int): Semester? =
        db.semesterDao().all().firstOrNull { it.id == id }?.toDomain()

    suspend fun currentSemester(): Semester? =
        db.semesterDao().current()?.toDomain() ?: db.semesterDao().all().firstOrNull()?.toDomain()

    suspend fun courses(semesterId: Int): List<Course> = loadCourses(semesterId)

    private suspend fun loadCourses(semesterId: Int): List<Course> =
        buildCourses(
            db.courseDao().bySemester(semesterId),
            db.courseSessionDao().bySemester(semesterId).map { it.toDomain() },
        )

    private fun buildCourses(
        courses: List<CourseEntity>,
        sessions: List<Session>,
    ): List<Course> {
        val byCode = sessions.groupBy { it.courseCode }
        return courses.map { c ->
            Course(
                key = c.key,
                semesterId = c.semesterId,
                code = c.code,
                nameZh = c.nameZh,
                credits = c.credits,
                openDepartment = c.openDepartment,
                teachers = c.teachersCsv.split(",").filter { it.isNotBlank() },
                sessions = byCode[c.code].orEmpty(),
            )
        }
    }

    // ── 本地计算 ─────────────────────────────────────────────────────────

    /**
     * 由学期起始日推算某一天属于第几教学周；落在学期外（假期）返回 null。
     *
     * 算法在 [WeekAlignment.indexOf]（纯函数、有单测）：对齐到周首再算，
     * 这样即使 startDate 是周中某天也不会错一周。
     */
    fun resolveCurrentWeek(semester: Semester, today: LocalDate = LocalDate.now()): Int? =
        WeekAlignment.indexOf(
            startDate = semester.startDate,
            weekStartOnSunday = semester.weekStartOnSunday,
            weekIndices = semester.weekIndices,
            date = today,
        )

    /**
     * 只有日期、没有周网格信息的记录（临时补课）：按日期算出「星期 + 教学周」。
     *
     * 星期直接用 ISO（周一=1…周日=7，与教务一致）；教学周用同一套对齐算法，
     * 落到学期周次之外（比如假期补课）就返回 0 —— 那种记录只能按日期匹配显示，不硬塞进某一周。
     */
    private fun weekdayAndWeekOf(semester: Semester?, date: String?): Pair<Int, Int>? {
        val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val week = semester?.let { resolveCurrentWeek(it, d) } ?: 0
        return d.dayOfWeek.value to week
    }

    /**
     * 某一天的课。
     *
     * **优先按真实日期匹配**（教务每条记录都带 date），这样学校调休/调课自动正确；
     * 只有少数记录没有日期时才回退到「星期 + 教学周」。
     */
    suspend fun sessionsOn(
        semesterId: Int,
        date: LocalDate,
        currentWeek: Int? = null,
    ): List<Session> {
        val all = db.courseSessionDao().bySemester(semesterId).map { it.toDomain() }
        val iso = date.toString()
        val byDate = all.filter { it.date == iso }
        val wd = date.dayOfWeek.value // ISO: 周一=1 … 周日=7，与教务的 weekday 一致
        val withoutDate = if (currentWeek == null) {
            emptyList()
        } else {
            all.filter { it.date == null && it.weekday == wd && it.weekIndex == currentWeek }
        }
        return (byDate + withoutDate).distinctBy { it.id }.sortedBy { it.startUnit }
    }

    /** 某一教学周的全部课（用于周课表网格） */
    suspend fun sessionsOfWeek(semesterId: Int, weekIndex: Int): List<Session> =
        db.courseSessionDao().bySemester(semesterId)
            .map { it.toDomain() }
            .filter { it.weekIndex == weekIndex }

    /**
     * 整学期全部课。
     * 界面一次性拿全，翻周时在内存里过滤 —— 这样滑周不会闪空白，也不发请求。
     */
    suspend fun sessionsOfSemester(semesterId: Int): List<Session> =
        db.courseSessionDao().bySemester(semesterId).map { it.toDomain() }

    fun observeSessionsOfWeek(semesterId: Int, weekIndex: Int): Flow<List<Session>> =
        db.courseSessionDao().observeBySemester(semesterId).map { list ->
            list.map { it.toDomain() }.filter { it.weekIndex == weekIndex }
        }

    suspend fun hasCourseData(semesterId: Int): Boolean =
        db.courseSessionDao().countBySemester(semesterId) > 0

    /**
     * 诊断：**同一个 (周, 星期, 起始节次) 上有多门课**的格子有多少个（`ScheduleGrid` 标签）。
     *
     * 为什么要记：课表页一个格子只会画**第一门**匹配的课（`ScheduleScreen` 里是 `firstOrNull`），
     * 而且被跨节大块覆盖的格子会被整段跳过 —— 学校新加的**补课**如果正好落在已有课的格子，
     * 在网格里就是看不见的。只记格子坐标与条数，**不含课程名/教室**。
     */
    private fun logGridCollisions(rows: List<CourseSessionEntity>) {
        runCatching {
            val dated = rows.filter { it.weekIndex > 0 && it.weekday in 1..7 }
            val collisions = dated
                .groupBy { Triple(it.weekIndex, it.weekday, it.startUnit) }
                .filter { (_, list) -> list.map { it.courseKey }.distinct().size > 1 }
            val dates = rows.mapNotNull { it.date }.distinct().sorted()
            // 星期分布：**周末有没有课**是判断"这个接口给不给补课"的关键 ——
            // 学校自己的课表能显示周六/周日的补课，如果这里一周七天里只有周一到周五，
            // 基本可以断定这个接口只返回常规周课表，补课来自别处。
            val weekdayCounts = (1..7).joinToString(" ") { d ->
                val n = dated.count { it.weekday == d }
                "周${"一二三四五六日"[d - 1]}$n"
            }
            Log.i(
                TAG_GRID,
                "课表入库：${rows.size} 条，日期 ${dates.size} 天（${dates.joinToString(",")}）；" +
                    "星期分布($weekdayCounts)；" +
                    "同格多门课（网格只会画第一门）的格子 ${collisions.size} 个：" +
                    collisions.entries.take(12).joinToString(" ") { (k, v) ->
                        "第${k.first}周${k.second}/${k.third}节×${v.map { it.courseKey }.distinct().size}"
                    },
            )
        }
    }

    // ── 自定义日程 ──────────────────────────────────────────────────────

    /**
     * 一个自定义日程 = 若干周的同一条记录（每周一行），和教务课程一个存法。
     * 这样网格、今天、翻周全都不用改；教务刷新时也只删 source = 'eas' 的行。
     */
    suspend fun customPlans(semesterId: Int): List<Plan> =
        db.courseSessionDao().bySemesterAndSource(semesterId, SOURCE_CUSTOM)
            .groupBy { it.courseKey }
            .map { (courseKey, rows) ->
                val head = rows.minByOrNull { it.weekIndex } ?: rows.first()
                Plan(
                    // 表格里没有单列 courseCode，日程标识就存在 courseKey 里
                    code = courseKey.removePrefix("$semesterId:"),
                    semesterId = semesterId,
                    name = head.courseName,
                    weekday = head.weekday,
                    startUnit = head.startUnit,
                    endUnit = head.endUnit,
                    weeks = rows.map { it.weekIndex }.distinct().sorted(),
                    roomText = head.roomText,
                    colorKey = head.color,
                )
            }
            .sortedWith(compareBy({ it.weekday }, { it.startUnit }))

    /** 新建或改写一个日程；返回它的 code（新建时是刚生成的） */
    suspend fun savePlan(spec: PlanSpec): String {
        val code = spec.code?.takeIf { it.isNotBlank() } ?: newPlanCode()
        val dao = db.courseSessionDao()
        // 先删旧行：改了周次/时段之后不能留下孤儿记录
        dao.deleteByCourseKey(courseKeyOf(spec.semesterId, code))

        val periods = periods().associateBy { it.indexNo }
        val startTime = periods[spec.startUnit]?.startText
        val endTime = periods[spec.endUnit]?.endText
        val now = System.currentTimeMillis()
        val rows = spec.weeks.distinct().sorted().map { week ->
            CourseSessionEntity(
                id = sessionIdOf(spec.semesterId, code, week, spec.weekday, spec.startUnit),
                courseKey = courseKeyOf(spec.semesterId, code),
                semesterId = spec.semesterId,
                source = SOURCE_CUSTOM,
                courseName = spec.name.trim(),
                weekIndex = week,
                weekday = spec.weekday,
                startUnit = spec.startUnit,
                endUnit = spec.endUnit,
                // 自定义日程不绑定真实日期：它按"教学周 + 星期"重复，
                // 学校调休时不会跟着跑（那本来也不是这门"课"的事）。
                date = null,
                startTime = startTime,
                endTime = endTime,
                teacherName = null,
                roomText = spec.roomText?.trim()?.takeIf { it.isNotEmpty() },
                campusText = null,
                color = spec.colorKey,
                updatedAt = now,
            )
        }
        if (rows.isNotEmpty()) dao.upsertAll(rows)
        return code
    }

    suspend fun deletePlan(semesterId: Int, code: String) =
        db.courseSessionDao().deleteByCourseKey(courseKeyOf(semesterId, code))

    /**
     * 这个日程和哪些教务课程撞了（同一天、周次有交集、节次有重叠）。
     * 只提醒不阻止 —— 有些"撞课"其实是自己想要的重叠安排。
     */
    suspend fun planConflicts(semesterId: Int, spec: PlanSpec): List<Session> {
        val weeks = spec.weeks.toSet()
        if (weeks.isEmpty()) return emptyList()
        return db.courseSessionDao().bySemester(semesterId)
            .filter { it.source == SOURCE_EAS }
            .map { it.toDomain() }
            .filter {
                it.weekday == spec.weekday &&
                    it.weekIndex in weeks &&
                    it.startUnit <= spec.endUnit &&
                    spec.startUnit <= it.endUnit
            }
            .distinctBy { it.courseName to it.startUnit }
            .sortedBy { it.startUnit }
    }

    /** 已保存的学号 —— 用来推断入学年（学号前 4 位） */
    suspend fun settingsAccount(): String? = db.settingsDao().get()?.account

    companion object {
        const val SOURCE_EAS = "eas"
        const val SOURCE_CUSTOM = "custom"

        private const val TAG_GRID = "ScheduleGrid"

        fun courseKeyOf(semesterId: Int, courseCode: String) = "$semesterId:$courseCode"

        /**
         * 稳定主键：同一学期 + 同一门课 + 同一周 + 同一天 + 同一节 + **同一日期** = 同一节课。
         *
         * [date] 必须进主键：学校的**补课**可能和正常课落在同一周同一天同一节
         * （比如把周二的课补在某周周二），不含日期就会互相覆盖，只剩一条。
         * 自定义日程没有日期（按周重复），传 null 即可。
         */
        fun sessionIdOf(
            semesterId: Int,
            courseCode: String,
            weekIndex: Int,
            weekday: Int,
            startUnit: Int,
            date: String? = null,
        ) = "$semesterId:$courseCode:$weekIndex:$weekday:$startUnit:${date.orEmpty()}"

        fun newPlanCode(): String = "P" + System.currentTimeMillis().toString(36)
    }
}

// ── 实体 ↔ 领域模型 ─────────────────────────────────────────────────────

private fun EasClient.SemesterDto.toEntity(isCurrent: Boolean, now: Long) = SemesterEntity(
    id = id,
    code = code,
    nameZh = nameZh,
    schoolYear = schoolYear,
    startDate = startDate?.take(10),
    endDate = endDate?.take(10),
    weekStartOnSunday = weekStartOnSunday,
    weekIndicesCsv = weekIndices.joinToString(","),
    isCurrent = isCurrent,
    updatedAt = now,
)

private fun SemesterEntity.toDomain() = Semester(
    id = id,
    nameZh = nameZh,
    schoolYear = schoolYear,
    startDate = startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    endDate = endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    weekStartOnSunday = weekStartOnSunday,
    weekIndices = weekIndicesCsv.split(",").mapNotNull { it.trim().toIntOrNull() }.sorted(),
    isCurrent = isCurrent,
)

private fun CourseSessionEntity.toDomain() = Session(
    id = id,
    semesterId = semesterId,
    source = source,
    courseCode = courseKey.substringAfterLast(':'),
    courseName = courseName,
    weekIndex = weekIndex,
    weekday = weekday,
    startUnit = startUnit,
    endUnit = endUnit,
    date = date,
    startTime = startTime,
    endTime = endTime,
    teacherName = teacherName,
    roomText = roomText,
    campusText = campusText,
    color = color,
)

private fun PeriodEntity.toDomain() = Period(
    indexNo = indexNo,
    nameZh = nameZh,
    startTime = startTime,
    endTime = endTime,
    dayPart = dayPart,
    segmentIndex = segmentIndex,
)
