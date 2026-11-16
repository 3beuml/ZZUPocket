package com.zzupocket.app.domain

import java.security.MessageDigest

/** 冲突显示用于周网格和首页时间轴；原始课程与小组件日程均保留。 */
data class ConflictDisplayChoice(
    val semesterId: Int,
    val conflictKey: String,
    val weekIndex: Int,
    val sessionKey: String,
)

/** 一段连续节次；sessions 始终保留这一段的全部课程，包括用户选择不显示的课。 */
data class ScheduleBlock(
    val startUnit: Int,
    val endUnit: Int,
    val sessions: List<Session>,
    val selectedSessionKey: String? = null,
    val scopeKey: String? = null,
) {
    val semesterId: Int get() = sessions.first().semesterId
    val weekIndex: Int get() = sessions.first().weekIndex
    val weekday: Int get() = sessions.first().weekday
    val isConflict: Boolean get() = sessions.size > 1
    val selectedSession: Session?
        get() = sessions.firstOrNull { it.conflictDisplayKey() == selectedSessionKey }
    val showPlaceholder: Boolean get() = isConflict && selectedSession == null
    val displaySession: Session get() = selectedSession ?: sessions.first()

    /** 不含周次、日期、课程名/教室等显示文本；重命名、重新同步和接口排序不影响选择。 */
    val conflictKey: String
        get() = stableKey(
            listOf("conflict-v1", semesterId.toString(), scopeKey.orEmpty(), weekday.toString(), startUnit.toString(), endUnit.toString()) +
                sessions.map { it.conflictDisplayKey() }.sorted(),
        )
}

/** 同一门课不同时段、不同来源、理论/实验分别定位，允许对各周做不同选择。 */
fun Session.conflictDisplayKey(): String = stableKey(
    listOf(source, courseCode, weekday.toString(), startUnit.toString(), endUnit.toString(), experiment.toString()),
)

/**
 * 按当天每一节的实际占用切段，只有占用集合不变的相邻节次才合并。
 * 例如甲 1–4、乙 3–6 → 甲 1–2 / 冲突 3–4 / 乙 5–6，不能整段跳过乙。
 * 各学期/周/星期独立计算；已有选择只在课程集合和重叠范围都没变时生效。
 */
fun scheduleBlocks(
    sessions: List<Session>,
    choices: List<ConflictDisplayChoice> = emptyList(),
    maxUnit: Int = 10,
    scopeKey: String? = null,
): List<ScheduleBlock> {
    val byChoice = choices.associateBy { Triple(it.semesterId, it.conflictKey, it.weekIndex) }
    return sessions
        .filter { it.weekIndex > 0 && it.weekday in 1..7 && it.startUnit > 0 && it.endUnit >= it.startUnit }
        .groupBy { Triple(it.semesterId, it.weekIndex, it.weekday) }
        .flatMap { (_, day) ->
            val ordered = day.distinctBy { it.id }.sortedWith(
                compareBy({ it.startUnit }, { it.courseCode }, { it.source }, { it.endUnit }, { it.experiment }, { it.id }),
            )
            val blocks = mutableListOf<ScheduleBlock>()
            for (unit in 1..maxUnit) {
                val active = ordered.filter { unit in it.startUnit..it.endUnit }
                if (active.isEmpty()) continue
                val previous = blocks.lastOrNull()
                if (previous != null && previous.endUnit == unit - 1 && previous.sessions == active) {
                    blocks[blocks.lastIndex] = previous.copy(endUnit = unit)
                } else {
                    blocks += ScheduleBlock(unit, unit, active, scopeKey = scopeKey)
                }
            }
            blocks.map { block ->
                val choice = if (block.isConflict) {
                    byChoice[Triple(block.semesterId, block.conflictKey, block.weekIndex)]
                } else null
                block.copy(selectedSessionKey = choice?.sessionKey)
            }
        }
        .sortedWith(compareBy({ it.semesterId }, { it.weekIndex }, { it.weekday }, { it.startUnit }))
}

private fun stableKey(parts: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 15])
        }
    }
}
