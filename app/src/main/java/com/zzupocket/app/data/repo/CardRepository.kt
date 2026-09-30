package com.zzupocket.app.data.repo

import com.zzupocket.app.data.auth.AuthRepository
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.db.CardTransactionEntity
import com.zzupocket.app.data.ecard.EcardClient
import com.zzupocket.app.domain.CardStats
import com.zzupocket.app.domain.CardTransaction
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 实体 → 领域模型 */
fun CardTransactionEntity.toDomain(): CardTransaction = CardTransaction(
    journo = journo,
    summary = summary,
    merchantName = merchantName,
    dateText = dateText,
    ts = ts,
    amount = amount,
    isRefund = isRefund,
    tradeType = tradeType,
)

/**
 * 校园卡流水仓库。
 *
 * 数据来源就是官方的 `/user/tradeList`（P4-0 实测过字段），**只读、只增**：
 * 流水号做主键，所以反复拉同一段时间不会重复，也不需要"最后更新时间"之类的状态。
 * 拉下来之后所有统计都在本地算（见 [CardStats]），离线也能看。
 */
class CardRepository(
    private val db: AppDatabase,
    private val ecard: EcardClient,
    private val auth: AuthRepository,
) {

    private val dao = db.cardTransactionDao()

    fun observeAll(): Flow<List<CardTransaction>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun all(): List<CardTransaction> = dao.all().map { it.toDomain() }

    suspend fun count(): Int = dao.count()

    suspend fun latestTs(): Long? = dao.latestTs()

    suspend fun clear() = dao.clear()

    /** 校园卡余额（元）。走 info.s.zzu.edu.cn，用 CAS 的 userToken */
    suspend fun balance(): Double = ecard.fetchCardBalance(auth.requireCasToken())

    /**
     * 增量同步：只拉"从最近一条记录往前 [overlapDays] 天"到现在。
     *
     * 留几天的重叠是为了兜住"昨天的交易今天才入账"这种情况 ——
     * 流水号主键让重复记录自动覆盖，不会算重。
     */
    suspend fun syncRecent(overlapDays: Long = 7): Int {
        val token = auth.requireEcardAccessToken()
        val today = LocalDate.now()
        val from = dao.latestTs()
            ?.let { CardStats.dayKey(it).minusDays(overlapDays) }
            ?: today.minusMonths(DEFAULT_MONTHS)
        return fetchRange(token, from, today, maxPages = 10)
    }

    /** 首次全量：从 [from] 一路翻页往回拉，直到没有更多。 */
    suspend fun syncAll(from: LocalDate = LocalDate.now().minusYears(3), maxPages: Int = 40): Int {
        val token = auth.requireEcardAccessToken()
        return fetchRange(token, from, LocalDate.now(), maxPages = maxPages)
    }

    /**
     * 分页拉取一个区间。服务端是"新→旧"返回，所以按页往后翻；
     * 一页返回不满就说明到底了。
     */
    private suspend fun fetchRange(
        token: String,
        from: LocalDate,
        to: LocalDate,
        maxPages: Int,
    ): Int {
        var saved = 0
        var page = 0
        val pageSize = 50
        while (page < maxPages) {
            val records = ecard.fetchTradeList(
                accessToken = token,
                // "1,2,3" = 全部（消费 + 充值 + 补助）—— 只拉消费会漏掉充值记录
                tradeType = EcardClient.TRADE_TYPE_ALL,
                fromDate = from.toString(),
                toDate = to.toString(),
                pageNo = page,
                pageSize = pageSize,
            )
            if (records.isEmpty()) break
            val now = System.currentTimeMillis()
            val entities = records
                .filter { it.journo.isNotBlank() }
                .map { dto ->
                    val value = dto.amt.toDoubleOrNull() ?: 0.0
                    CardTransactionEntity(
                        journo = dto.journo,
                        summary = dto.summary,
                        merchantName = dto.merchantName,
                        dateText = dto.date,
                        ts = CardStats.parseTs(dto.date),
                        amount = value,
                        isRefund = dto.isRefund == "1",
                        // 服务端不在流水里回类型，用金额符号反推：
                        // 消费为负、充值与补助为正。界面上再按 summary/商户文案区分。
                        tradeType = if (value < 0) {
                            EcardClient.TRADE_TYPE_SPEND
                        } else {
                            EcardClient.TRADE_TYPE_RECHARGE
                        },
                        fetchedAt = now,
                    )
                }
            if (entities.isNotEmpty()) {
                dao.upsertAll(entities)
                saved += entities.size
            }
            if (records.size < pageSize) break
            page++
        }
        return saved
    }

    private companion object {
        /** 第一次同步往前拉多久（服务端能追多久就多少，拉不到就自然停） */
        const val DEFAULT_MONTHS = 6L
    }
}
