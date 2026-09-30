package com.zzupocket.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ── 番茄钟 · 标签 ──────────────────────────────────────────────────────

/**
 * 专注标签。课名是天然的候选标签，但**不预先生成** ——
 * 只有用户真用过的标签才落库，免得一进去就塞 20 个没用的芯片。
 */
@Entity(tableName = "focus_tags")
data class FocusTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 0 表示"未指定颜色"，界面用主题色 */
    val colorArgb: Int = 0,
    /** 归档后不出现在选择列表里，但历史记录的标签名照旧显示 */
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
)

// ── 番茄钟 · 专注记录 ────────────────────────────────────────────────────

/**
 * 一段专注/休息记录。开始时就落库（endedAt=0 表示"进行中"），
 * 这样即使 App 被系统杀掉、记录也能在恢复时对上号。
 *
 * tagName 冗余存一份：标签被删掉之后历史记录还看得懂。
 */
@Entity(
    tableName = "focus_sessions",
    indices = [Index("startedAt"), Index("tagId"), Index("contextRef")],
)
data class FocusSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tagId: Long? = null,
    val tagName: String = "",
    /** FOCUS / SHORT_BREAK / LONG_BREAK */
    val phase: String,
    val startedAt: Long,
    /** 结束时刻（含暂停时长）；0 = 进行中 */
    val endedAt: Long = 0,
    /** 计划时长（秒） */
    val plannedSeconds: Int,
    /** 实际专注秒数（已扣掉暂停） */
    val actualSeconds: Int = 0,
    /** 走完计划时长 = true；提前结束/放弃 = false */
    val completed: Boolean = false,
    /** 这一天里是第几个完成的专注（第 1 个 = 1）；仅 FOCUS 有意义 */
    @ColumnInfo(defaultValue = "0") val sequenceInDay: Int = 0,
    val note: String = "",
    /**
     * 关联对象。P3-3 起：课表里的一节课 `"session:<id>"`、
     * 自定义日程 `"plan:<id>"`。空串 = 无关联系自由专注。
     */
    val contextRef: String = "",
)

@Dao
interface FocusTagDao {

    @Query("SELECT * FROM focus_tags WHERE archived = 0 ORDER BY sortOrder ASC, id ASC")
    suspend fun active(): List<FocusTagEntity>

    @Query("SELECT * FROM focus_tags ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<FocusTagEntity>>

    @Query("SELECT * FROM focus_tags WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): FocusTagEntity?

    @Query("SELECT * FROM focus_tags WHERE id = :id")
    suspend fun byId(id: Long): FocusTagEntity?

    @Insert
    suspend fun insert(tag: FocusTagEntity): Long

    @Update
    suspend fun update(tag: FocusTagEntity)

    @Query("UPDATE focus_tags SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("SELECT MAX(sortOrder) FROM focus_tags")
    suspend fun maxSortOrder(): Int?

    /** 含归档的总数：用来判断"是不是一张标签都没有过" */
    @Query("SELECT COUNT(*) FROM focus_tags")
    suspend fun count(): Int
}

@Dao
interface FocusSessionDao {

    @Insert
    suspend fun insert(session: FocusSessionEntity): Long

    @Update
    suspend fun update(session: FocusSessionEntity)

    @Query("SELECT * FROM focus_sessions WHERE id = :id")
    suspend fun byId(id: Long): FocusSessionEntity?

    @Query("SELECT COUNT(*) FROM focus_sessions WHERE id = :id")
    suspend fun exists(id: Long): Int

    /** 进行中的那些记录（正常情况下最多一条） */
    @Query("SELECT * FROM focus_sessions WHERE endedAt = 0 ORDER BY startedAt DESC")
    suspend fun unfinished(): List<FocusSessionEntity>

    @Query("SELECT * FROM focus_sessions WHERE startedAt >= :from ORDER BY startedAt DESC")
    suspend fun since(from: Long): List<FocusSessionEntity>

    @Query("SELECT * FROM focus_sessions WHERE startedAt >= :from ORDER BY startedAt DESC")
    fun observeSince(from: Long): Flow<List<FocusSessionEntity>>

    @Query("SELECT * FROM focus_sessions ORDER BY startedAt DESC")
    suspend fun all(): List<FocusSessionEntity>

    @Query("SELECT COUNT(*) FROM focus_sessions WHERE phase = 'FOCUS' AND completed = 1 AND startedAt >= :from AND startedAt < :to")
    suspend fun completedFocusCount(from: Long, to: Long): Int

    @Query("DELETE FROM focus_sessions WHERE id = :id")
    suspend fun delete(id: Long)
}
