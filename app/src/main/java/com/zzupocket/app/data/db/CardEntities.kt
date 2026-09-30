package com.zzupocket.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 校园卡流水。
 *
 * 主键用**流水号**（`journo`）—— 天然唯一、天然幂等，
 * 所以"重复拉同一段时间"不会重复计，也不需要去重逻辑。
 * 只在客户端做增量，从不修改历史记录。
 */
@Entity(
    tableName = "card_transactions",
    indices = [Index("ts")],
)
data class CardTransactionEntity(
    @PrimaryKey val journo: String,
    val summary: String,
    val merchantName: String,
    /** 服务端原始时间文本 "2026-09-19 17:45:17"（解析失败时留档用） */
    val dateText: String,
    /** 本地时区下的时间戳，用于排序与聚合；0 = 解析失败 */
    val ts: Long,
    /** 单位：元。消费为负、充值为正 —— 服务端就是这么给的 */
    val amount: Double,
    val isRefund: Boolean,
    val tradeType: String,
    val fetchedAt: Long,
)

@Dao
interface CardTransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CardTransactionEntity>): List<Long>

    @Query("SELECT * FROM card_transactions ORDER BY ts DESC")
    fun observeAll(): Flow<List<CardTransactionEntity>>

    @Query("SELECT * FROM card_transactions ORDER BY ts DESC")
    suspend fun all(): List<CardTransactionEntity>

    @Query("SELECT * FROM card_transactions ORDER BY ts ASC LIMIT 1")
    suspend fun earliest(): CardTransactionEntity?

    @Query("SELECT COUNT(*) FROM card_transactions")
    suspend fun count(): Int

    @Query("SELECT MAX(ts) FROM card_transactions")
    suspend fun latestTs(): Long?

    @Query("DELETE FROM card_transactions")
    suspend fun clear()
}
