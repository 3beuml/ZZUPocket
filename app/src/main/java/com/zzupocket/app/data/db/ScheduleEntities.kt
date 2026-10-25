package com.zzupocket.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// ── 学期 ────────────────────────────────────────────────────────────────

@Entity(tableName = "semesters")
data class SemesterEntity(
    @PrimaryKey val id: Int,
    val code: String,
    val nameZh: String,
    val schoolYear: String,
    val startDate: String?,
    val endDate: String?,
    val weekStartOnSunday: Boolean,
    /** 教学周号，逗号分隔，如 "1,2,3,...,18" */
    val weekIndicesCsv: String,
    val isCurrent: Boolean,
    val updatedAt: Long,
)

@Dao
interface SemesterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SemesterEntity>)

    @Query("SELECT * FROM semesters ORDER BY startDate DESC")
    suspend fun all(): List<SemesterEntity>

    @Query("SELECT * FROM semesters ORDER BY startDate DESC")
    fun observeAll(): Flow<List<SemesterEntity>>

    @Query("SELECT * FROM semesters WHERE isCurrent = 1 LIMIT 1")
    suspend fun current(): SemesterEntity?

    @Query("UPDATE semesters SET isCurrent = 0")
    suspend fun clearCurrent()

    @Query("UPDATE semesters SET isCurrent = 1 WHERE id = :id")
    suspend fun markCurrent(id: Int)
}

// ── 课程 ────────────────────────────────────────────────────────────────

@Entity(tableName = "courses")
data class CourseEntity(
    /** "semesterId:courseCode" */
    @PrimaryKey val key: String,
    val semesterId: Int,
    val code: String,
    val nameZh: String,
    val credits: Double,
    val openDepartment: String?,
    /** 逗号分隔的教师名 */
    val teachersCsv: String,
    val updatedAt: Long,
)

@Dao
interface CourseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CourseEntity>)

    @Query("SELECT * FROM courses WHERE semesterId = :semesterId ORDER BY nameZh")
    suspend fun bySemester(semesterId: Int): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE semesterId = :semesterId ORDER BY nameZh")
    fun observeBySemester(semesterId: Int): Flow<List<CourseEntity>>

    @Query("DELETE FROM courses WHERE semesterId = :semesterId")
    suspend fun deleteBySemester(semesterId: Int)

    @Query("SELECT COUNT(*) FROM courses WHERE semesterId = :semesterId")
    suspend fun countBySemester(semesterId: Int): Int

    /** 番茄钟的标签候选：所有学期出现过的课名（去重） */
    @Query("SELECT DISTINCT nameZh FROM courses WHERE semesterId >= 0 ORDER BY nameZh ASC")
    fun observeAllNames(): Flow<List<String>>
}

// ── 课表同步状态（本科 / 研究生共用）──────────────────────────────────

/**
 * 某个来源、某个学期最近一次成功抓取的时间与最近一次错误。
 *
 * 为什么要单独存：课表要"先显示缓存、再后台刷新"，就必须知道
 * **这份缓存是什么时候抓的**（界面上的「数据截至 HH:MM」、以及 12 小时 TTL 判断），
 * 以及**上次为什么失败**（区分"会话过期"与"教务不可用"，别让用户白重登）。
 *
 * 研究生用合成学期 id（-10001），本科用真实学期 id，靠 `source` 区分。
 */
@Entity(tableName = "timetable_sync", primaryKeys = ["source", "semesterId"])
data class TimetableSyncEntity(
    val source: String,
    val semesterId: Int,
    /** 最近一次**成功**抓取的时间（毫秒）；0 = 从没成功过 */
    val fetchedAt: Long,
    val lastError: String? = null,
    val lastErrorAt: Long? = null,
    /**
     * 课表内容指纹（每节课的 周次|星期|节次|课名|教室|教师 拼起来算）。
     * 刷新后指纹相同 ⇒ 内容没变 ⇒ **界面一个字都不该变**（用户明确要求），
     * 但仍要更新 [fetchedAt]，因为数据确实是刚取回来的。
     */
    val contentHash: String? = null,
    /** 缓存结构版本；与当前代码不符时直接丢弃重拉，避免旧缓存缺字段导致界面异常 */
    val cacheVersion: Int = 1,
)

@Dao
interface TimetableSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TimetableSyncEntity)

    @Query("SELECT * FROM timetable_sync WHERE source = :source AND semesterId = :semesterId")
    suspend fun get(source: String, semesterId: Int): TimetableSyncEntity?

    @Query("SELECT * FROM timetable_sync WHERE source = :source AND semesterId = :semesterId")
    fun observe(source: String, semesterId: Int): Flow<TimetableSyncEntity?>

    @Query("UPDATE timetable_sync SET lastError = :error, lastErrorAt = :at WHERE source = :source AND semesterId = :semesterId")
    suspend fun markError(source: String, semesterId: Int, error: String, at: Long)

    @Query("DELETE FROM timetable_sync")
    suspend fun clear()

    @Query("DELETE FROM timetable_sync WHERE source = :source")
    suspend fun clearBySource(source: String)
}

// ── 上课记录 ────────────────────────────────────────────────────────────

@Entity(tableName = "course_sessions")
data class CourseSessionEntity(
    @PrimaryKey val id: String,
    val courseKey: String,
    val semesterId: Int,
    /** "eas" = 教务课程，"custom" = 自己添加的日程（P3 起） */
    val source: String,
    val courseName: String,
    val weekIndex: Int,
    val weekday: Int,
    val startUnit: Int,
    val endUnit: Int,
    val date: String?,
    val startTime: String?,
    val endTime: String?,
    val teacherName: String?,
    val roomText: String?,
    val campusText: String?,
    val color: String?,
    /** 实验/见习类排课（print-data 的 experiment 标志；micro-server 主数据恒为 false） */
    val experiment: Boolean = false,
    val updatedAt: Long,
)

/**
 * 显示覆盖（issue #6 保底）：用户对教务课程的**本地显示**做的自定义，
 * 存自己的表、按 key 定位，教务刷新永不触碰。
 *
 * - `kind = "name"`：课程名覆盖，作用整门课；
 * - `kind = "room"`：教室覆盖，作用到「星期 + 起始节」这个时段
 *   （同一门课不同时段常在不同教室）。
 *   [weeks] 为 null = 该时段**全部周**；否则只作用于逗号分隔的这些教学周——
 *   单双周换教室的课（单周 302、双周 405）靠它分成两条互不干扰。
 * 应用在仓库的读取口（网格/今天/组件/详情统一生效）。
 */
@Entity(tableName = "display_overrides")
data class DisplayOverrideEntity(
    @PrimaryKey val id: String,     // "<semesterId>|<courseCode>|name" 或 "|room|<weekday>|<startUnit>|<weeks|all>"
    val semesterId: Int,
    val courseCode: String,
    val kind: String,               // "name" | "room"
    val weekday: Int?,
    val startUnit: Int?,
    val weeks: String? = null,      // kind=room 时的教学周（如 "1,3,5"）；null = 全部周
    val value: String,
    val updatedAt: Long,
)

@Dao
interface DisplayOverrideDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DisplayOverrideEntity)

    @Query("SELECT * FROM display_overrides WHERE semesterId = :semesterId")
    suspend fun bySemester(semesterId: Int): List<DisplayOverrideEntity>

    @Query("DELETE FROM display_overrides WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 清掉某时段的**全部**教室覆盖（不分周；保存「全部周」覆盖前先清场） */
    @Query(
        "DELETE FROM display_overrides WHERE semesterId = :semesterId AND courseCode = :courseCode " +
            "AND kind = 'room' AND weekday = :weekday AND startUnit = :startUnit",
    )
    suspend fun deleteRoomBySlot(semesterId: Int, courseCode: String, weekday: Int, startUnit: Int)

    @Query("DELETE FROM display_overrides WHERE semesterId = :semesterId AND courseCode = :courseCode")
    suspend fun deleteByCourse(semesterId: Int, courseCode: String)

    @Query("DELETE FROM display_overrides WHERE semesterId = :semesterId")
    suspend fun deleteBySemester(semesterId: Int)
}

@Dao
interface CourseSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CourseSessionEntity>)

    @Query("SELECT * FROM course_sessions WHERE semesterId = :semesterId")
    suspend fun bySemester(semesterId: Int): List<CourseSessionEntity>

    @Query("SELECT * FROM course_sessions WHERE semesterId = :semesterId")
    fun observeBySemester(semesterId: Int): Flow<List<CourseSessionEntity>>

    @Query("SELECT * FROM course_sessions WHERE semesterId = :semesterId AND source = :source")
    suspend fun bySemesterAndSource(semesterId: Int, source: String): List<CourseSessionEntity>

    /** 只清理教务来源的记录，**不能碰自定义日程** */
    @Query("DELETE FROM course_sessions WHERE semesterId = :semesterId AND source = 'eas'")
    suspend fun deleteEasBySemester(semesterId: Int)

    /**
     * 按来源清理（研究生落库用 `source = 'gms'`）。
     *
     * ⚠️ 注意与 [deleteEasBySemester] 的区别：那边把 `'eas'` 写死在 SQL 里，
     * 这里按参数删。**任何来源都不能顺手删到 `'custom'`** —— 那是用户自己加的日程。
     */
    @Query("DELETE FROM course_sessions WHERE semesterId = :semesterId AND source = :source")
    suspend fun deleteBySemesterAndSource(semesterId: Int, source: String)

    /** 重写某个自定义日程：先删掉它的全部周次记录，再按新的周次插回去 */
    @Query("DELETE FROM course_sessions WHERE courseKey = :courseKey")
    suspend fun deleteByCourseKey(courseKey: String)

    @Query("SELECT COUNT(*) FROM course_sessions WHERE semesterId = :semesterId")
    suspend fun countBySemester(semesterId: Int): Int
}

// ── 节次 ────────────────────────────────────────────────────────────────

@Entity(tableName = "periods")
data class PeriodEntity(
    @PrimaryKey val indexNo: Int,
    val nameZh: String,
    val startTime: Int,
    val endTime: Int,
    val dayPart: String,
    val segmentIndex: Int,
    val updatedAt: Long,
)

@Dao
interface PeriodDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PeriodEntity>)

    @Query("SELECT * FROM periods ORDER BY indexNo")
    suspend fun all(): List<PeriodEntity>

    @Query("SELECT * FROM periods ORDER BY indexNo")
    fun observeAll(): Flow<List<PeriodEntity>>
}

// ── 教室占用缓存（空教室页）────────────────────────────────────────────

/**
 * 某个日期、某间教室的占用快照。
 *
 * 与课表缓存的关键区别：**空教室是"当天某时刻"的数据**，过期了会误导人
 * （显示空、跑过去有人）。所以按 (date, roomId) 存，只缓存今天，跨天直接失效；
 * [occupations] 存的是**原始占用时间段**（"800-845,905-950"），不是布尔格子 ——
 * 节次时间表变了还能重新算格子。
 */
@Entity(tableName = "room_occupancy", primaryKeys = ["date", "roomId"])
data class RoomOccupancyEntity(
    val date: String,
    val roomId: Long,
    val campusId: Long,
    val code: String,
    val buildingId: Long,
    val buildingName: String,
    val campusName: String,
    /** 占用时间段，逗号分隔的 `startTime-endTime`（HHmm 整数） */
    val occupations: String,
    val fetchedAt: Long,
)

@Dao
interface RoomOccupancyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<RoomOccupancyEntity>)

    @Query("SELECT * FROM room_occupancy WHERE date = :date AND campusId = :campusId")
    suspend fun byDateAndCampus(date: String, campusId: Long): List<RoomOccupancyEntity>

    @Query("SELECT MAX(fetchedAt) FROM room_occupancy WHERE date = :date AND campusId = :campusId")
    suspend fun fetchedAt(date: String, campusId: Long): Long?

    /** 覆盖写入前先清掉同一天同一校区的旧快照 */
    @Query("DELETE FROM room_occupancy WHERE date = :date AND campusId = :campusId")
    suspend fun deleteByDateAndCampus(date: String, campusId: Long)

    /** 只保留 [minDate, maxDate] 窗口内的快照（空教室保留 7 天用） */
    @Query("DELETE FROM room_occupancy WHERE date < :minDate OR date > :maxDate")
    suspend fun pruneOutside(minDate: String, maxDate: String)
}
