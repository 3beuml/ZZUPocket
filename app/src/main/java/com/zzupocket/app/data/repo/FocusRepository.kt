package com.zzupocket.app.data.repo

import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.db.FocusSessionEntity
import com.zzupocket.app.data.db.FocusTagEntity
import com.zzupocket.app.domain.FocusRecord
import com.zzupocket.app.domain.FocusStats
import com.zzupocket.app.domain.Phase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 数据库实体 → 统计/界面用的领域模型 */
fun FocusSessionEntity.toRecord(): FocusRecord = FocusRecord(
    id = id,
    tagId = tagId,
    tagName = tagName,
    phase = Phase.fromDb(phase),
    startedAt = startedAt,
    endedAt = endedAt,
    actualSeconds = actualSeconds,
    completed = completed,
    contextRef = contextRef,
    note = note,
)

/**
 * 番茄钟的持久化。标签删除用的是**归档**而不是真删 ——
 * 历史记录里的 tagName 是冗余存的，但标签本身留着便于以后恢复。
 */
class FocusRepository(private val db: AppDatabase) {

    private val tagDao = db.focusTagDao()
    private val sessionDao = db.focusSessionDao()

    // ── 标签 ────────────────────────────────────────────────────────────

    fun observeTags(): Flow<List<FocusTagEntity>> = tagDao.observeAll()

    suspend fun tags(): List<FocusTagEntity> = tagDao.active()

    /**
     * 第一次用时写进四个默认标签：听课 / 自习 / 背单词 / 写代码。
     *
     * 只在**一张标签都没有过**的时候种一次 —— 用户全删光之后不该被重新塞回来。
     * 种下去之后它们就是普通标签，能改名也能删。
     */
    suspend fun ensureDefaultTags(): List<FocusTagEntity> {
        if (tagDao.count() > 0) return tagDao.active()
        val now = System.currentTimeMillis()
        DEFAULT_TAGS.forEachIndexed { index, name ->
            tagDao.insert(FocusTagEntity(name = name, sortOrder = index + 1, createdAt = now))
        }
        return tagDao.active()
    }

    /**
     * 按名字找标签，没有就建一个。
     * 前后空格、大小写都归一化 —— 免得"高数"和"高数 "变成两个标签。
     */
    suspend fun ensureTag(name: String, colorArgb: Int = 0): FocusTagEntity? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        tagDao.byName(clean)?.let { existing ->
            if (existing.archived) tagDao.update(existing.copy(archived = false))
            return existing.copy(archived = false)
        }
        val id = tagDao.insert(
            FocusTagEntity(
                name = clean,
                colorArgb = colorArgb,
                sortOrder = (tagDao.maxSortOrder() ?: 0) + 1,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return tagDao.active().firstOrNull { it.id == id }
    }

    suspend fun renameTag(id: Long, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) return
        tagDao.byId(id)?.let { tagDao.update(it.copy(name = clean)) }
    }

    suspend fun setTagColor(id: Long, colorArgb: Int) {
        tagDao.byId(id)?.let { tagDao.update(it.copy(colorArgb = colorArgb)) }
    }

    suspend fun archiveTag(id: Long) = tagDao.archive(id)

    // ── 专注记录 ────────────────────────────────────────────────────────

    fun observeRecords(sinceMs: Long): Flow<List<FocusRecord>> =
        sessionDao.observeSince(sinceMs).map { list -> list.map { it.toRecord() } }

    suspend fun records(): List<FocusRecord> = sessionDao.all().map { it.toRecord() }

    suspend fun recordsSince(sinceMs: Long): List<FocusRecord> =
        sessionDao.since(sinceMs).map { it.toRecord() }

    /** 开始一段；返回数据库里的 id */
    suspend fun startSession(
        phase: Phase,
        tagId: Long?,
        tagName: String,
        plannedSeconds: Int,
        startedAt: Long,
        contextRef: String = "",
        note: String = "",
    ): Long {
        val sequence = if (phase == Phase.FOCUS) {
            val day = FocusStats.dayKey(startedAt)
            sessionDao.completedFocusCount(
                FocusStats.dayStart(day),
                FocusStats.dayEnd(day),
            ) + 1
        } else {
            0
        }
        return sessionDao.insert(
            FocusSessionEntity(
                tagId = tagId,
                tagName = tagName.trim(),
                phase = phase.dbValue,
                startedAt = startedAt,
                endedAt = 0,
                plannedSeconds = plannedSeconds,
                actualSeconds = 0,
                completed = false,
                sequenceInDay = sequence,
                note = note,
                contextRef = contextRef,
            ),
        )
    }

    /** 收尾：写入实际时长与是否走完 */
    suspend fun finishSession(
        id: Long,
        endedAt: Long,
        actualSeconds: Int,
        completed: Boolean,
    ) {
        if (id <= 0L) return
        val row = sessionDao.byId(id) ?: return
        sessionDao.update(
            row.copy(
                endedAt = endedAt,
                actualSeconds = actualSeconds.coerceAtLeast(0),
                completed = completed,
            ),
        )
    }

    suspend fun updateNote(id: Long, note: String) {
        val row = sessionDao.byId(id) ?: return
        sessionDao.update(row.copy(note = note))
    }

    suspend fun deleteSession(id: Long) = sessionDao.delete(id)

    /** 手动补录（忘了开计时器时用）：直接写一条已完成的记录 */
    suspend fun addManualSession(
        startedAt: Long,
        actualSeconds: Int,
        tagId: Long?,
        tagName: String,
        note: String = "",
    ): Long = startSession(
        phase = Phase.FOCUS,
        tagId = tagId,
        tagName = tagName,
        plannedSeconds = actualSeconds,
        startedAt = startedAt,
        note = note,
    ).also { id ->
        finishSession(
            id = id,
            endedAt = startedAt + actualSeconds * 1000L,
            actualSeconds = actualSeconds,
            completed = true,
        )
    }

    suspend fun unfinished(): List<FocusRecord> = sessionDao.unfinished().map { it.toRecord() }

    suspend fun exists(id: Long): Boolean = sessionDao.exists(id) > 0

    /**
     * 把上一次进程没来得及收尾的记录关掉。
     *
     * 进程被系统杀掉时，进行中的那条记录 endedAt 还是 0。
     * 用"计划时长"封顶，避免出现一条永远在跑的记录污染统计。
     * [exceptId] 用来跳过"快照里还在跑的那一段"—— 它是要接着跑的，不能封掉。
     */
    suspend fun closeUnfinished(now: Long, exceptId: Long = 0L): Int {
        var n = 0
        sessionDao.unfinished().forEach { row ->
            if (row.id == exceptId) return@forEach
            val elapsed = ((now - row.startedAt) / 1000L).coerceAtLeast(0L).toInt()
            val actual = minOf(elapsed, row.plannedSeconds)
            sessionDao.update(
                row.copy(
                    endedAt = row.startedAt + actual * 1000L,
                    actualSeconds = actual,
                    completed = false,
                ),
            )
            n++
        }
        return n
    }

    suspend fun completedFocusCount(from: Long, to: Long): Int =
        sessionDao.completedFocusCount(from, to)

    companion object {
        val DEFAULT_TAGS = listOf("听课", "自习", "背单词", "写代码")
    }
}
