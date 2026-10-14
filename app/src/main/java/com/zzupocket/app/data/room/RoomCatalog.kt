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
 * 同一栋楼里照明与空调是**两套独立表计**（unit 不同）。
 *
 * ## 空调房号怎么来的（重要）
 * 「空调号 = 照明号前加 8」**只是主校区的多数派**，别的楼各有各的规律。所以这份规则
 * **在生成资产时就用两侧真实房号列表算好了**（离线穷举，覆盖率 100% 或明确标"没有规律"），
 * 运行时只套用 [acRule] / [acOffset] / [acPerRoom] / [acOverride]，**不再用 `endsWith` 猜** ——
 * 猜错的后果是把别人的电表填给用户（历史上真发生过）。
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
    /** 空调表下的房号 */
    val ac: List<String> = emptyList(),
    /** 空调号推导规则：[AC_RULE_PREFIX8] / [AC_RULE_OFFSET] / [AC_RULE_SUFFIX] / [AC_RULE_NONE] */
    val acRule: String = AC_RULE_PREFIX8,
    /** [AC_RULE_OFFSET] 用：空调号 = 照明号 + acOffset（北校区 = 楼号×1000，东校区是负数） */
    val acOffset: Int = 0,
    /** [AC_RULE_SUFFIX] 用：一间房有几个空调户（荷园那种每人一个表就是 3） */
    val acPerRoom: Int = 1,
    /** 规则解释不了的房间：房号 → 它的空调号列表（**空数组 = 这间确实没有空调户**） */
    val acOverride: Map<String, List<String>> = emptyMap(),
) {
    /** 学生在宿舍门口看到的房号：优先用照明表的（那才是寝室号本身） */
    val roomNumbers: List<String> get() = if (light.isNotEmpty()) light else ac

    val roomCount: Int get() = roomNumbers.size

    private val acIndex: Set<String> by lazy { ac.toSet() }

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

    /**
     * 一个房号对应的**全部**空调户房号。
     *
     * - 空列表：这栋楼没有空调表，或者这个房号确实没有空调户（如荷园5号楼 101）；
     * - 多个：**一间房每人一个空调表**（荷园2–9号楼），界面必须让用户选自己是哪一个，
     *   不能默认取第一个 —— 那就是别人的表。
     */
    fun acRoomNumbersFor(room: String): List<String> {
        if (acUnit <= 0) return emptyList()
        // 例外优先（"这间房确实没有空调户"也记在这里）
        acOverride[room]?.let { return it }
        return when (acRule) {
            AC_RULE_PREFIX8 -> listOf("8$room").filter { it in acIndex }
            AC_RULE_OFFSET -> room.toIntOrNull()
                ?.plus(acOffset)
                ?.toString()
                ?.let { listOf(it) }
                ?.filter { it in acIndex }
                .orEmpty()
            AC_RULE_SUFFIX -> (1..4).map { room + it }.filter { it in acIndex }
            else -> emptyList()
        }
    }

    /** 只取第一个空调户（老调用点/界面默认值用）；没有就 null */
    fun acRoomNumberFor(room: String): String? = acRoomNumbersFor(room).firstOrNull()

    /**
     * 这栋楼的空调号是不是**有确切规律**（资产里算过、覆盖率已核）。
     *
     * 注意：true **不代表只有一套空调户** —— 荷园那种一间房 N 个户也是确切规律，
     * 只是必须让用户选一个（[acPerRoom] > 1）。
     */
    fun acRuleIsExact(): Boolean = acRule != AC_RULE_NONE

    fun acRoomIds(room: String): List<String> {
        if (acUnit <= 0) return emptyList()
        return acRoomNumbersFor(room).map { "$area-$building--$acUnit-$it" }
    }

    fun acRoomId(room: String): String? = acRoomIds(room).firstOrNull()
}

/** 空调号 = 照明号前加 `8`（主校区多数派） */
const val AC_RULE_PREFIX8 = "prefix8"

/** 空调号 = 照明号 + 数值偏移（北校区 = 楼号×1000、南校区 = 楼号×1000、东校区是负数） */
const val AC_RULE_OFFSET = "offset"

/** 空调号 = 照明号 + 一位住户序号（荷园式，一间房每人一个表） */
const val AC_RULE_SUFFIX = "suffix"

/** 数据里没有可靠的对应关系（例如松园2/4/5号楼的 2222/3333 占位号） */
const val AC_RULE_NONE = "none"

/**
 * 一个校区。
 *
 * 一卡通里管它叫"区域"，[code] 就是房间 ID 开头那个 area 码（主校区 `99`、北校区 `101`……），
 * 所以**校区可以直接从房间 ID 里读出来**。
 *
 * ⚠️ 房间表只覆盖 6 个区域；**亿达学生公寓（area `106`）上游从来没导出过**，
 * 那里只能靠"从学校账户读取绑定房间"、在线选房间、或手动填房间号
 * （见 `ElectricityRepository.boundRoom` / `locationLevel`）。
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
 * **亿达学生公寓**的 area 码 —— 目前唯一确认"整间只有一个电表"的区域。
 *
 * 用户 2026-10-11 确认：**只有亿达**是这样（没有照明/空调之分）；洛阳校区、柳园17号楼在房间表里
 * 虽然也只有一路表（`acUnit = 0`），但**没有确认过**，所以它们照旧按双表计显示。
 * 以后确认了别的区域，往这里加一条即可 —— 单表计判定只有这一处。
 */
const val SINGLE_METER_AREA = "106"

/**
 * 这个房间是不是"整间一个表"（走单表计显示：一个「电量」卡，不摆一个空的空调格）。
 *
 * 判定**只看区域码**，不看"资产里这栋楼有没有两路表" —— 理由见 [SINGLE_METER_AREA]。
 */
fun isSingleMeterRoom(roomId: String?): Boolean =
    decodeRoomId(roomId)?.area?.toString() == SINGLE_METER_AREA

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
