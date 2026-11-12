package com.zzupocket.app.data.repo

import com.zzupocket.app.data.auth.AuthRepository
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.db.KvCacheEntity
import com.zzupocket.app.data.eas.EasClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * 教室占用仓库。
 *
 * 缓存策略按"数据变化速度"分三档：
 *  - **字典**（校区/楼栋/教室类型）：进程内缓存 + **本地落盘**（kv_cache，7 天 TTL）。
 *    为什么要落盘：断网/教务挂掉时占用数据有本地快照、字典却没了，
 *    筛选弹窗会一个楼栋选项都出不来（2026-11-10 用户实测）。
 *    落盘后：内存没 有 → 读盘（未过期直接用；过期了也当降级兜底）→ 才走网络；
 *  - **占用数据**：**内存缓存 8 分钟**。占用是分钟级的实时数据，
 *    缓存太久会误导人（界面上永远标「数据截至 HH:MM」）；
 *  - 不落库：占用数据出了当天就没意义，存下来只会有"过期数据被当成现状"的风险。
 */
class RoomRepository(
    private val eas: EasClient,
    private val auth: AuthRepository,
    private val db: AppDatabase,
) {

    private var campusesCache: List<EasClient.CampusDto>? = null
    private val buildingsCache = HashMap<Long, List<EasClient.BuildingDto>>()
    private val pageCache = HashMap<String, Pair<Long, EasClient.ClassroomPageDto>>()

    suspend fun campuses(force: Boolean = false): List<EasClient.CampusDto> {
        if (!force) campusesCache?.let { return it }
        // 盘里有没过期的就直接用（字典是慢变数据，不值得每次进页都赌网络）
        if (!force) persistedDict<List<EasClient.CampusDto>>(KEY_CAMPUSES, requireFresh = true)
            ?.takeIf { it.isNotEmpty() }?.let {
                campusesCache = it
                return it
            }
        val list = try {
            eas.fetchCampuses(auth.requireCasToken())
        } catch (e: Exception) {
            // 网络失败：盘里就算过期也当降级兜底——总好过筛选弹窗空空如也
            persistedDict<List<EasClient.CampusDto>>(KEY_CAMPUSES, requireFresh = false)
                ?.takeIf { it.isNotEmpty() }?.also { campusesCache = it }
                ?: throw e
        }
        campusesCache = list
        // ⚠️ 空列表**不落盘**：服务端瞬态抽风返回 [] 的话，落了盘就是 7 天的
        // "字典毒化"——每次冷启动都从盘里读到空、断网兜底也兜不出东西。
        if (list.isNotEmpty()) persistDict(KEY_CAMPUSES, list)
        return list
    }

    suspend fun buildings(campusId: Long, force: Boolean = false): List<EasClient.BuildingDto> {
        if (!force) buildingsCache[campusId]?.let { return it }
        if (!force) persistedDict<List<EasClient.BuildingDto>>(keyBuildings(campusId), requireFresh = true)
            ?.takeIf { it.isNotEmpty() }?.let {
                buildingsCache[campusId] = it
                return it
            }
        val list = try {
            eas.fetchBuildings(auth.requireCasToken(), campusId)
        } catch (e: Exception) {
            persistedDict<List<EasClient.BuildingDto>>(keyBuildings(campusId), requireFresh = false)
                ?.takeIf { it.isNotEmpty() }?.also { buildingsCache[campusId] = it }
                ?: throw e
        }
        buildingsCache[campusId] = list
        if (list.isNotEmpty()) persistDict(keyBuildings(campusId), list)
        return list
    }

    // ── 字典落盘（kv_cache；序列化失败/缺列一律静默回退网络路径）──────────
    private val dictJson = Json { ignoreUnknownKeys = true }

    /**
     * 读落盘字典。[requireFresh] = 只拿未过期的（正常路径，省一次网络）；
     * false = 过期也返回（网络失败时的降级兜底——慢变字典的旧数据仍远好过没有）。
     */
    private suspend inline fun <reified T> persistedDict(key: String, requireFresh: Boolean): T? {
        val row = db.kvCacheDao().get(key) ?: return null
        if (requireFresh && System.currentTimeMillis() - row.fetchedAt > row.ttlSeconds * 1000) return null
        return runCatching { dictJson.decodeFromString<T>(row.payload) }.getOrNull()
    }

    private suspend inline fun <reified T> persistDict(key: String, value: T) {
        runCatching {
            db.kvCacheDao().put(
                KvCacheEntity(
                    key = key,
                    payload = dictJson.encodeToString(value),
                    fetchedAt = System.currentTimeMillis(),
                    ttlSeconds = DICT_TTL_SECONDS,
                ),
            )
        }
    }

    /**
     * 拉一页教室（含每间教室当天的占用）。
     * ⚠️ 请求体里**不能带 campusAssoc**（服务端那个域名模型没法反序列化，会 500）；
     * 校区过滤是靠传该校区下的楼栋 id 实现的。
     */
    suspend fun roomsPage(
        date: LocalDate,
        buildingIds: List<Long>,
        page: Int,
        pageSize: Int = PAGE_SIZE,
        force: Boolean = false,
    ): EasClient.ClassroomPageDto {
        val key = "$date|${buildingIds.sorted().joinToString(",")}|$page|$pageSize"
        if (!force) {
            pageCache[key]?.let { (at, cached) ->
                if (System.currentTimeMillis() - at < CACHE_TTL_MS) return cached
            }
        }
        val result = eas.fetchRooms(
            userToken = auth.requireCasToken(),
            body = EasClient.RoomQueryBody(
                currentPage = page,
                pageSize = pageSize,
                buildingIds = buildingIds,
                date = date.toString(),
            ),
        )
        pageCache[key] = System.currentTimeMillis() to result
        return result
    }

    /** 换校区/日期/楼栋后清掉占用缓存（字典缓存留着） */
    fun invalidateRooms() = pageCache.clear()

    companion object {
        const val KEY_CAMPUSES = "room-dict:campuses"
        fun keyBuildings(campusId: Long) = "room-dict:buildings:$campusId"

        /** 字典是慢变数据：7 天内不联网刷新 */
        const val DICT_TTL_SECONDS = 7L * 24 * 60 * 60

        const val PAGE_SIZE = 30

        /** 选定了楼栋时用大页：一栋楼通常 15–60 间，一次拿完省得手动续页 */
        const val PAGE_SIZE_LARGE = 100

        /** 占用数据的有效期：8 分钟 */
        const val CACHE_TTL_MS = 8 * 60 * 1000L
    }
}
