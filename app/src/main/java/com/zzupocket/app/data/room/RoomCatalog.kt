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

    /**
     * 楼栋简称：柳园20号楼 → 柳20。
     *
     * **只给主校区那四个园用**（柳/荷/菊/松 + 号楼）。别硬套"首字 + 数字"：
     * 别的校区有 `北区1号楼`（会缩成"北1"，还算能用，但没必要）、`1号宿舍楼 2层`（会缩成"12"）、
     * `主校区护理1#女生`（会缩成"主1"）—— 认不出来，还不如原名。名字不匹配就用原名。
     */
    val shortName: String
        get() {
            val m = Regex("""^([柳荷菊松]园)(\d+)""").find(name) ?: return name
            return m.groupValues[1].take(1) + m.groupValues[2]
        }

    /** 界面用的紧凑房间名：柳园20号楼 + 212 → `柳20-101` */
    fun shortLabel(room: String): String = "$shortName-$room"

    fun lightRoomId(room: String): String? =
        if (lightUnit > 0) "$area-$building--$lightUnit-$room" else null

    /** 空调表里对应的房号；找不到就返回 null（界面会提示"这个房号没有空调表"） */
    fun acRoomNumberFor(room: String): String? =
        ac.firstOrNull { it == "8$room" } ?: ac.firstOrNull { it.endsWith(room) }

    /**
     * 这个房号的空调号是不是**按常见规律**（照明房号前加 `8`）对上的。
     *
     * 只做判断，**不改变 [acRoomId] 的取值** —— 界面拿它决定要不要提醒用户去学校电费页核对。
     *
     * 为什么要提醒：楼栋之间的空调编号规律并不统一。主校区实测 8839/9730 条是"8+房号"，
     * 但荷园2–9号楼是"房号 + 一位住户序号"（`302` → `3021/3022/3023`，**一间房每人一个空调户**），
     * 松园8/9/10/11/13号楼、北校区（楼号×1000）、东校区（偏移为负）、南校区各有各的偏移。
     * 这些楼里 [acRoomNumberFor] 的回退分支 `endsWith` 可能对上**别人**的房间，
     * 所以要么核对，要么干脆别填那一路。
     */
    fun acGuessIsReliable(room: String): Boolean = ac.any { it == "8$room" }

    fun acRoomId(room: String): String? {
        if (acUnit <= 0) return null
        val acRoom = acRoomNumberFor(room) ?: return null
        return "$area-$building--$acUnit-$acRoom"
    }
}

/**
 * 一个校区。
 *
 * 一卡通里管它叫"区域"，[code] 就是房间 ID 开头那个 area 码（主校区 `99`、北校区 `101`……），
 * 所以**校区可以直接从房间 ID 里读出来**。
 *
 * ⚠️ 房间表只覆盖 6 个区域；**亿达学生公寓（area `106`）上游从来没导出过**，
 * 那里只能靠"从学校账户读取绑定房间"或手动填房间号（见 `ElectricityRepository.boundRoom`）。
 */
@Serializable
data class RoomCampus(
    val code: String = "",
    val name: String = "",
    val buildings: List<RoomBuilding> = emptyList(),
) {
    val roomCount: Int get() = buildings.sumOf { it.roomCount }
}

@Serializable
data class RoomCatalogData(
    val campuses: List<RoomCampus> = emptyList(),
) {
    /** 全部楼栋（跨校区）；反查与老的调用点用它就够 */
    val buildings: List<RoomBuilding> get() = campuses.flatMap { it.buildings }

    fun campusOf(code: String?): RoomCampus? =
        code?.let { c -> campuses.firstOrNull { it.code == c } }

    fun findBuilding(name: String?): RoomBuilding? =
        name?.let { n -> buildings.firstOrNull { it.name == n } }

    /**
     * 用房间 ID 反查它属于哪个校区、哪栋楼、哪个房号。
     *
     * 空调 ID（`99-16--32-8101`）会被换回照明表的房号（`101`），
     * 因为学生认的是寝室号，不是空调表里那个多一个前导 8 的编号。
     * area 码在校区之间不重复，所以一次遍历就能定下来。
     */
    fun locate(roomId: String?): LocatedRoom? {
        val parts = decodeRoomId(roomId) ?: return null
        for (campus in campuses) {
            val building = campus.buildings.firstOrNull {
                it.area == parts.area && it.building == parts.building
            } ?: continue
            // ⚠️ unit 为 0 表示"这栋楼没有这一套表"，不能拿 0 去比
            val room = when {
                building.lightUnit > 0 && parts.unit == building.lightUnit -> parts.room
                building.acUnit > 0 && parts.unit == building.acUnit ->
                    parts.room.removePrefix("8").takeIf { it in building.light } ?: parts.room
                else -> continue
            }
            return LocatedRoom(campus, building, room)
        }
        return null
    }
}

/** [RoomCatalogData.locate] 的结果 */
data class LocatedRoom(val campus: RoomCampus, val building: RoomBuilding, val room: String)

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
 * 数据来自社区项目 **ZZU-Electricity-Monitor**（MIT）维护的 `rooms/{99,101..105}.json`
 * 六个区域文件，已精简成"**校区 → 楼栋 → 照明/空调房号列表**"（见 assets/rooms.json）。
 * 生成脚本与原始副本在作者本机 `_research/`（原始文件不入库）。
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
     * 拿**一个**房间 ID 换紧凑可读名（`柳20-101`）。
     *
     * 用途：老用户（或手动填过 ID 的人）本来只会看到 `99-16--31-101` 这种内部代码，
     * 有了反查就能直接显示寝室号，不需要他们再去选一次房间。查不到返回 null。
     *
     * ⚠️ 一次只查一个房间，别写成"两个 ID 一起查第一个能查到的" ——
     * 照明与空调**可以是两个不同的房间**，混着查会让两张卡显示同一个名字
     * （历史上就是这么错的，用户看到的是"怎么查都显示我自己寝室"）。
     */
    suspend fun labelForRoom(roomId: String?): String? {
        if (roomId.isNullOrBlank()) return null
        val data = runCatching { load() }.getOrNull() ?: return null
        val hit = data.locate(roomId) ?: return null
        val label = hit.building.shortLabel(hit.room)
        // 楼栋简称跨校区会重名（北1 / 东1 / 南1 都是一号楼），非主校区带上校区名
        return if (hit.campus.code == MAIN_CAMPUS_CODE) label else "${hit.campus.name} $label"
    }

    companion object {
        const val ASSET = "rooms.json"

        /** 主校区的 area 码（也是默认校区；界面上的简称在这一个校区里不需要前缀） */
        const val MAIN_CAMPUS_CODE = "99"

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
