package com.zzupocket.app.domain

/** 一间教室某天的占用时间段（HHmm 整数，和节次表同一套格式） */
data class RoomOccupation(val startTime: Int, val endTime: Int, val activityType: String = "")

/** 教室 + 它的格子（与节次表一一对应） */
data class RoomRow(
    val id: Long,
    /** 显示用：**房间号**（101 这种，取自 nameZh）；楼栋在分组标题里 */
    val code: String,
    val buildingId: Long,
    val buildingName: String,
    val campusName: String,
    /** 与节次表等长；true = 那节有课 */
    val occupied: List<Boolean>,
    /** 长按自检用的原始字段（接口原样，不做加工） */
    val rawNameZh: String = "",
    val rawCode: String = "",
) {
    val freeAllDay: Boolean get() = occupied.none { it }
    val occupiedCount: Int get() = occupied.count { it }
}

/**
 * 教室名的解析。
 *
 * ⚠️ 踩过的坑：接口有两个字段 —— `nameZh`（人类可读，"北1_101"）和 `code`
 * （**不稳定**：同样是 北1 的教室，探测时是 "北1_101"，实际使用中却是 **12 位内部编码**）。
 * 所以显示房间号时**优先用 `nameZh`**，绝不直接显示 `code`。
 */
object RoomNames {

    private val DIGITS = Regex("\\d+")

    /**
     * 房间号：
     *  - "北1_101" → "101"（取最后一段连续数字）
     *  - "北1-101" → "101"
     *  - "101"     → "101"
     *  - nameZh 取不出 → 退回 code；code 也取不出 → 显示原值（绝不显示空白）
     */
    fun numberOf(nameZh: String, code: String): String {
        lastDigits(nameZh)?.let { return it }
        lastDigits(code)?.let { return it }
        return nameZh.ifBlank { code }
    }

    private fun lastDigits(text: String): String? =
        DIGITS.findAll(text).lastOrNull()?.value?.takeIf { it.isNotBlank() }
}

/**
 * 楼栋展示顺序。
 *
 * 「北1–北5、南1–南5」是日常最常去的那几栋，置顶；其余保持接口给的顺序
 * （服务端按楼栋 id 排，那就是学校的默认顺序）。
 * 筛选面板和表格分组**共用这一套顺序**，两处不能不一致 ——
 * 否则筛完北1，结果表里北1跑到中间去了。
 */
object RoomBuildingOrder {

    private val PINNED = listOf(
        "北1", "北2", "北3", "北4", "北5",
        "南1", "南2", "南3", "南4", "南5",
    )

    fun rank(name: String): Int {
        val index = PINNED.indexOf(name.trim())
        return if (index >= 0) index else PINNED.size
    }

    /** 稳定排序：置顶项按 [PINNED] 顺序，其余保持原相对顺序 */
    fun <T> sorted(items: List<T>, nameOf: (T) -> String): List<T> =
        items.sortedBy { rank(nameOf(it)) }
}

/**
 * 教室占用表的纯逻辑。
 *
 * 判定规则**照抄学校 H5 的做法**（页面代码里的 `isOccupied`）：
 * 用"占用时间段"和"节次时间段"做重叠判断，跨节次的课自然会被涂满对应的几格。
 * 这样不需要服务端给节次序号（实测 `unitStart/unitEnd` 是 null）。
 */
object RoomOccupancyGrid {

    /**
     * 时间段是否与节次重叠。
     *
     * 学校原版：
     * ```
     * occ.startTime < unit.startTime ? occ.endTime > unit.startTime : occ.startTime < unit.endTime
     * ```
     * 语义：**首尾相接不算占用**（第 1 节 08:00-08:45、第 2 节 08:55-09:40，
     * 一门 08:45-08:55 的课不会把两节都涂上）。
     */
    fun overlaps(occStart: Int, occEnd: Int, unitStart: Int, unitEnd: Int): Boolean =
        if (occStart < unitStart) occEnd > unitStart else occStart < unitEnd

    /** 一间教室的格子：对每节课判断有没有被占用 */
    fun grid(
        occupations: List<RoomOccupation>,
        units: List<Pair<Int, Int>>,
    ): List<Boolean> = units.map { (start, end) ->
        occupations.any { overlaps(it.startTime, it.endTime, start, end) }
    }

    /** 教室号升序的比较器：数字部分按**数值**比，否则 "北1_101" 会排在 "北1_1001" 后面 */
    val byCode: Comparator<String> = Comparator { a, b ->
        val ka = sortKey(a)
        val kb = sortKey(b)
        ka.compareTo(kb)
    }

    /** "北1_101" → "北000001_000101"（数字补零到 6 位，长度一致才能正确比较） */
    private fun sortKey(code: String): String =
        code.replace(Regex("\\d+")) { m -> m.value.padStart(6, '0') }

    /**
     * 按楼分组、组内按教室号排序。
     * [buildingOrder] 是楼栋字典里的顺序（服务端按楼栋 id 给，这就是学校的展示顺序）。
     */
    fun groupByBuilding(
        rooms: List<RoomRow>,
        buildingOrder: List<Long>,
    ): List<Pair<String, List<RoomRow>>> {
        val order = buildingOrder.withIndex().associate { (index, id) -> id to index }
        return rooms
            .groupBy { it.buildingId }
            .toList()
            .sortedBy { (buildingId, _) -> order[buildingId] ?: Int.MAX_VALUE }
            .map { (_, list) ->
                val name = list.first().buildingName
                name to list.sortedWith(compareBy(byCode) { it.code })
            }
    }
}
