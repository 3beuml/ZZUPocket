package com.zzupocket.app.data.repo

import com.zzupocket.app.data.auth.AuthRepository
import com.zzupocket.app.data.db.AppDatabase
import com.zzupocket.app.data.eas.EasClient
import java.time.LocalDate

/**
 * 教室占用仓库。
 *
 * 缓存策略按"数据变化速度"分三档：
 *  - **字典**（校区/楼栋/教室类型）：进程内缓存，基本不变；
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
        val list = eas.fetchCampuses(auth.requireCasToken())
        campusesCache = list
        return list
    }

    suspend fun buildings(campusId: Long, force: Boolean = false): List<EasClient.BuildingDto> {
        if (!force) buildingsCache[campusId]?.let { return it }
        val list = eas.fetchBuildings(auth.requireCasToken(), campusId)
        buildingsCache[campusId] = list
        return list
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
        const val PAGE_SIZE = 30

        /** 选定了楼栋时用大页：一栋楼通常 15–60 间，一次拿完省得手动续页 */
        const val PAGE_SIZE_LARGE = 100

        /** 占用数据的有效期：8 分钟 */
        const val CACHE_TTL_MS = 8 * 60 * 1000L
    }
}
