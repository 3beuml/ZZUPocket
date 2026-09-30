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
    @Query("SELECT DISTINCT nameZh FROM courses ORDER BY nameZh ASC")
    fun observeAllNames(): Flow<List<String>>
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
    val updatedAt: Long,
)

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
