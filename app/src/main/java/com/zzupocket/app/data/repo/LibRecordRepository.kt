package com.zzupocket.app.data.repo

import com.zzupocket.app.core.AppError
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.lib.LibClient
import com.zzupocket.app.domain.LibRecord
import com.zzupocket.app.domain.LibRecordStats
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 图书馆预约记录的数据源。
 *
 * ## 本地缓存（2026-11-01，用户需求"不登录也能看历史记录"）
 * 预约记录是**历史数据、不会变**，而图书馆会话实测 1–2 天就过期、我们又只存令牌不存密码
 * （过期后只能靠用户打开一次图书馆页让 WebView 自动登录）。所以：
 *  - 每次拉取成功就把整份快照存进 `kv_cache`（[CACHE_KEY]，免 Room 迁移）；
 *  - 会话失效/网络不通时**回退到缓存**并标记 [Snapshot.fromCache]，
 *    界面显示"X 时间缓存的记录"横幅，历史照样可看；
 *  - 没有任何缓存时才把会话错误抛给界面（引导去图书馆页登录）。
 *
 * 取数口径（对齐 H5 的"座位预约记录"页）：
 *  - 座位：type 1/3/4（普通/研习/考研）三路合并，`limit=100`（2026-10-31 实测 18 条记录
 *    在 limit=100 下整页返回）。
 *  - 空间：`/v4/seminar/books`；违约：座位/空间两个 renege 接口。
 */
class LibRecordRepository(
    private val lib: LibClient,
    private val db: AppDatabase,
    private val json: Json,
) {

    /** 一次全量快照 */
    data class Snapshot(
        val records: List<LibRecord>,
        val reneges: List<LibRecord>,
        val fetchedAt: Long,
        /** true = 这份是本地缓存（当时拉取成功过），不是这次现拉的 */
        val fromCache: Boolean = false,
    ) {
        /** 热力图数据：按天聚合的预约次数 */
        val countsByDay: Map<LocalDate, Int> by lazy { LibRecordStats.countsByDay(records) }
    }

    suspend fun fetchAll(): Snapshot {
        val records = buildList {
            addAll(fetchSeatType(LibClient.TYPE_SEAT_NORMAL, "普通座位"))
            addAll(fetchSeatType(LibClient.TYPE_SEAT_STUDY, "研习座位"))
            addAll(fetchSeatType(LibClient.TYPE_SEAT_EXAM, "考研座位"))
            addAll(seminar())
        }
        val reneges = buildList {
            runCatching {
                lib.seatReneges(LibClient.TYPE_SEAT_NORMAL).items.forEach {
                    add(LibRecord.ofSeat(it, "座位违约"))
                }
            }
            runCatching {
                lib.seminarReneges().items.forEach {
                    add(LibRecord.ofSpace(it, "空间违约"))
                }
            }
            // 违约接口失败不挡主列表（多数人没有违约记录；字段形态以主接口为准）
        }
        val snap = Snapshot(
            records = records.sortedByDescending { it.day },
            reneges = reneges.sortedByDescending { it.day },
            fetchedAt = System.currentTimeMillis(),
        )
        saveCache(snap)
        return snap
    }

    /**
     * 现拉优先、缓存兜底。
     *
     * @return (快照, 是否来自缓存, 失败原因)。**缓存可看但不隐瞒**：界面用它标
     *         "这是 X 时间的记录"，并按 [Throwable] 区分"会话过期"与"网络不通"。
     *         现拉成功时第三项为 null。
     */
    suspend fun fetchAllWithCache(): Triple<Snapshot, Boolean, Throwable?> =
        try {
            Triple(fetchAll(), false, null)
        } catch (e: Throwable) {
            val cached = cachedSnapshot()
            if (cached != null) {
                // 会话失效是预期内的常规态（1-2 天就过期），不是故障 —— 别把异常往上传
                if (e is AppError) {
                    android.util.Log.i(TAG, "现拉失败（${e.javaClass.simpleName}），回退本地缓存")
                } else {
                    android.util.Log.w(TAG, "现拉异常，回退本地缓存", e)
                }
                Triple(cached, true, e)
            } else {
                throw e
            }
        }

    // ── 缓存（kv_cache 表，payload 为 JSON；历史记录不会变，整份覆盖即可） ──

    @Serializable
    private data class CachedEntry(val type: String, val kind: String, val raw: JsonObject)

    @Serializable
    private data class CachePayload(
        val records: List<CachedEntry>,
        val reneges: List<CachedEntry>,
        val fetchedAt: Long,
    )

    private suspend fun saveCache(snap: Snapshot) {
        runCatching {
            val payload = CachePayload(
                records = snap.records.map {
                    CachedEntry(
                        type = if (it.kind.contains("空间")) "space" else "seat",
                        kind = it.kind,
                        raw = it.raw,
                    )
                },
                reneges = snap.reneges.map {
                    CachedEntry(
                        type = if (it.kind.contains("空间")) "space" else "seat",
                        kind = it.kind,
                        raw = it.raw,
                    )
                },
                fetchedAt = snap.fetchedAt,
            )
            db.kvCacheDao().put(
                com.zzupocket.app.data.db.KvCacheEntity(
                    key = CACHE_KEY,
                    payload = json.encodeToString(CachePayload.serializer(), payload),
                    fetchedAt = snap.fetchedAt,
                    // 历史数据没有"过期"概念：缓存旧了只是不能代表今天，永远可看
                    ttlSeconds = 0,
                ),
            )
        }.onFailure { android.util.Log.w(TAG, "记录缓存写入失败（不影响本次显示）", it) }
    }

    suspend fun cachedSnapshot(): Snapshot? = runCatching {
        val entity = db.kvCacheDao().get(CACHE_KEY) ?: return@runCatching null
        val payload = try {
            json.decodeFromString(CachePayload.serializer(), entity.payload)
        } catch (_: Exception) {
            return@runCatching null   // 结构对不上（改版残留）当没有缓存处理
        }
        Snapshot(
            records = payload.records.map { rebuild(it) },
            reneges = payload.reneges.map { rebuild(it) },
            fetchedAt = payload.fetchedAt,
            fromCache = true,
        )
    }.getOrNull()

    private fun rebuild(entry: CachedEntry): LibRecord =
        if (entry.type == "space") LibRecord.ofSpace(entry.raw, entry.kind)
        else LibRecord.ofSeat(entry.raw, entry.kind)

    private suspend fun fetchSeatType(type: String, label: String): List<LibRecord> =
        lib.seatRecords(type, page = 1, limit = 100).items.map { LibRecord.ofSeat(it, label) }

    private suspend fun seminar(): List<LibRecord> =
        lib.seminarRecords(page = 1, limit = 100).items.map { LibRecord.ofSpace(it, "空间") }

    private companion object {
        const val TAG = "LibRecordRepo"
        const val CACHE_KEY = "lib_records_snapshot_v1"
    }
}
