package com.zzupocket.app.data.room

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一栋宿舍楼。
 *
 * 房间 ID 的格式是 `area-building--unit-room`（注意中间是**双横线**），例如
 * 柳园20号楼 101 室的照明表是 `99-16--31-101`、空调表是 `99-16--32-8101`：
 * 同一栋楼里照明与空调是**两套独立表计**（unit 不同），空调的房号还多一个前导 8。
 */
@Serializable
data class RoomBuilding(
    val name: String,
    val area: Int,
    val building: Int,
    /** 照明表计的 unit（0 = 该楼没有照明表） */
    val lightUnit: Int = 0,
    /** 空调表计的 unit（0 = 该楼没有空调表） */
    val acUnit: Int = 0,
    /** 照明表下的房号 */
    val light: List<String> = emptyList(),
    /** 空调表下的房号（通常是照明房号前加 8） */
    val ac: List<String> = emptyList(),
) {
    /** 学生在宿舍门口看到的房号：优先用照明表的（那才是寝室号本身） */
    val roomNumbers: List<String> get() = if (light.isNotEmpty()) light else ac

    val roomCount: Int get() = roomNumbers.size

    /** 楼栋简称：柳园20号楼 → 柳20；名字里没有数字就用原名 */
    val shortName: String
        get() {
            val m = Regex("""^(.+?)(\d+)""").find(name) ?: return name
            return m.groupValues[1].take(1) + m.groupValues[2]
        }

    /** 界面用的紧凑房间名：柳园20号楼 + 212 → `柳20-101` */
    fun shortLabel(room: String): String = "$shortName-$room"

    fun lightRoomId(room: String): String? =
        if (lightUnit > 0) "$area-$building--$lightUnit-$room" else null

    /** 空调表里对应的房号；找不到就返回 null（界面会提示"这个房号没有空调表"） */
    fun acRoomNumberFor(room: String): String? =
        ac.firstOrNull { it == "8$room" } ?: ac.firstOrNull { it.endsWith(room) }

    fun acRoomId(room: String): String? {
        if (acUnit <= 0) return null
        val acRoom = acRoomNumberFor(room) ?: return null
        return "$area-$building--$acUnit-$acRoom"
    }
}

@Serializable
data class RoomCatalogData(
    val campus: String = "",
    val buildings: List<RoomBuilding> = emptyList(),
) {
    fun findBuilding(name: String?): RoomBuilding? =
        name?.let { n -> buildings.firstOrNull { it.name == n } }

    /**
     * 用房间 ID 反查它属于哪栋楼、哪个房号。
     *
     * 空调 ID（`99-16--32-8101`）会被换回照明表的房号（`101`），
     * 因为学生认的是寝室号，不是空调表里那个多一个前导 8 的编号。
     */
    fun locate(roomId: String?): Pair<RoomBuilding, String>? {
        val parts = decodeRoomId(roomId) ?: return null
        val building = buildings.firstOrNull {
            it.area == parts.area && it.building == parts.building
        } ?: return null
        return when (parts.unit) {
            building.lightUnit -> building to parts.room
            building.acUnit -> {
                val asLightRoom = parts.room.removePrefix("8").takeIf { it in building.light }
                building to (asLightRoom ?: parts.room)
            }
            else -> null
        }
    }
}

/** 拆解内部房间 ID：`99-16--31-101` → area=99, building=16, unit=31, room=101 */
data class RoomIdParts(val area: Int, val building: Int, val unit: Int, val room: String) {
    val id: String get() = "$area-$building--$unit-$room"
}

/** 解析房间 ID；格式不对返回 null */
fun decodeRoomId(raw: String?): RoomIdParts? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val m = Regex("""^(\d+)-(\d+)--(\d+)-(.+)$""").find(text) ?: return null
    return RoomIdParts(
        area = m.groupValues[1].toIntOrNull() ?: return null,
        building = m.groupValues[2].toIntOrNull() ?: return null,
        unit = m.groupValues[3].toIntOrNull() ?: return null,
        room = m.groupValues[4],
    )
}

/**
 * 宿舍房间表。
 *
 * 数据来自社区项目 **ZZU-Electricity-Monitor**（MIT）维护的 `rooms/99.json`，
 * 已精简成"楼栋 → 照明/空调房号列表"（见 assets/rooms.json）。
 *
 * 为什么内置而不是联网取：这是用来**选房间**的，断网也得能选；而且它变化很慢
 * （新楼落成才会变），跟着版本发就行。取不到时会抛出异常，界面会退化成"手动填房间 ID"。
 */
class RoomCatalog(private val context: Context) {

    private var cached: RoomCatalogData? = null

    suspend fun load(): RoomCatalogData = cached ?: withContext(Dispatchers.IO) {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val parsed = JSON.decodeFromString<RoomCatalogData>(text)
        cached = parsed
        parsed
    }

    /**
     * 拿两个房间 ID 换一个紧凑可读名（`柳20-101`）。
     *
     * 用途：老用户（或手动填过 ID 的人）本来只会看到 `99-16--31-101` 这种内部代码，
     * 有了反查就能直接显示寝室号，不需要他们再去选一次房间。查不到返回 null。
     */
    suspend fun labelFor(lightRoomId: String?, acRoomId: String?): String? {
        val data = runCatching { load() }.getOrNull() ?: return null
        val hit = listOfNotNull(lightRoomId, acRoomId)
            .firstNotNullOfOrNull { data.locate(it) }
            ?: return null
        return hit.first.shortLabel(hit.second)
    }

    companion object {
        const val ASSET = "rooms.json"

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
