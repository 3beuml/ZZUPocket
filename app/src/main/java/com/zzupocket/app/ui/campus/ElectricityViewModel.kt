package com.zzupocket.app.ui.campus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.toUserMessage
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.data.ecard.EcardClient
import com.zzupocket.app.data.repo.ElectricityRepository
import com.zzupocket.app.data.room.RoomCatalogData
import com.zzupocket.app.data.room.isSingleMeterRoom
import com.zzupocket.app.domain.DayUsage
import com.zzupocket.app.domain.EnergySample
import com.zzupocket.app.domain.EnergyStats
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一行"按日明细" */
data class UsageDayRow(
    val day: LocalDate,
    val usage: DayUsage,
    val cost: Double,
)

data class MeterUi(
    val label: String,
    val roomId: String,
    val kwh: Double?,
    val fetchedAt: Long?,
    val error: String?,
    val daysLeft: Double?,
    /**
     * **这一路自己的**房间可读名。
     *
     * ⚠️ 千万别让两路共用一行名字：照明与空调可以是两个不同的房间
     * （换校区试查、或学校只回了绑定房间那一路），共用就会显示成"怎么查都是我自己的寝室"。
     */
    val roomLabel: String = "",
)

/** 「从学校账户读取房间」的结果，给界面显示一行反馈 */
data class RoomReadUi(val ok: Boolean, val text: String)

/** 在线房间树里已经走过的一层（面包屑） */
data class LocationStep(val type: String, val id: String, val name: String)

/** 在线房间树的一次查询：问哪一层 + 已经选中的祖先 */
data class OnlineRequest(
    val requestType: String,
    val scope: EcardClient.LocationScope,
    val steps: List<LocationStep>,
)

/**
 * 在线选房间的界面状态。
 *
 * [leaf] 为 true 表示当前这一层就是房间（服务端 `end=true`），点一下就选中。
 * 深度不写死：学校有几层就跟着 `nextLocationType` 走几层
 * （ZZU 实测是 区域 → 楼栋 → 层级（照明房间/空调用电）→ 房间）。
 */
data class OnlinePickerUi(
    val loading: Boolean = false,
    val error: String? = null,
    /** 刚展示的这一层用的查询范围（往下走时在它基础上填 id） */
    val scope: EcardClient.LocationScope = EcardClient.LocationScope(),
    val steps: List<LocationStep> = emptyList(),
    /** 服务端刚返回的这一层（area / building / level / room …）—— 也是要填进 scope 的字段名 */
    val currentType: String = "",
    /** **下一层要问什么**（服务端给的 nextLocationType）；[leaf] 时为 null */
    val nextType: String? = null,
    val nodes: List<EcardClient.LocationNode> = emptyList(),
    val leaf: Boolean = false,
    /** 走过的查询，用于「返回上一层」重新取一次 */
    val backStack: List<OnlineRequest> = emptyList(),
) {
    /**
     * 往下走一层。
     *
     * ⚠️ 两个类型别搞混（这里踩过坑，真机上表现为"点了一层，列表没变"）：
     * 要**填**的是当前这一层的字段（`currentType`，例如 area），
     * 要**问**的是服务端说的下一层（`nextType`，例如 building）。
     * 返回 null 表示这一层已经是房间（应该选中它），或者服务端没给下一层。
     */
    fun descend(node: EcardClient.LocationNode): OnlineRequest? {
        val next = nextType ?: return null
        if (leaf) return null
        val fieldType = currentType.ifBlank { ROOT_LEVEL }
        return OnlineRequest(
            requestType = next,
            scope = scope.with(fieldType, node.id),
            steps = steps + LocationStep(fieldType, node.id, node.name),
        )
    }

    companion object {
        /** 最外层从哪一层开始问 */
        const val ROOT_LEVEL = "area"
    }
}

data class ElectricityUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false,
    val price: Double = EnergyStats.DEFAULT_PRICE_PER_KWH.toDouble(),
    val light: MeterUi? = null,
    val ac: MeterUi? = null,
    val rangeDays: Int = 30,
    /** 最近 rangeDays 天：日期 → 用量（缺的天为 0） */
    val series: List<Pair<LocalDate, DayUsage>> = emptyList(),
    /** 堆叠柱状图用的柱子（照明 + 空调 + 是否估算） */
    val bars: List<UsageBar> = emptyList(),
    val estimatedDays: Set<LocalDate> = emptySet(),
    /** 余额折线：(时间戳, 度数) */
    val lightBalance: List<Pair<Long, Double>> = emptyList(),
    val acBalance: List<Pair<Long, Double>> = emptyList(),
    val yesterday: DayUsage = DayUsage(),
    val today: DayUsage = DayUsage(),
    val avgDaily: Double = 0.0,
    val sampleCount: Int = 0,
    val lastSampleAt: Long? = null,
    /** 按日明细（新→旧，只保留有用电量的天） */
    val dayRows: List<UsageDayRow> = emptyList(),
    val selectedDay: LocalDate? = null,
    /** 房间的可读名（"柳园20号楼 101"）；为空说明是手动填的 ID 或还没设置 */
    val roomLabel: String = "",
    /** 内置房间表（选楼栋 → 选房号用）；读不出来时为 null */
    val roomCatalog: RoomCatalogData? = null,
    val roomCatalogError: String? = null,
    /** 「从学校账户读取房间」的进行中 / 结果反馈 */
    val roomReading: Boolean = false,
    val roomRead: RoomReadUi? = null,
    /**
     * 只设了**一路**表计（另一路没有房间号）。
     *
     * 这类楼是"整间一个表"：亿达学生公寓、洛阳校区、柳园17号楼（留学生）那种。
     * 界面上按**一个电量**呈现 —— 不摆一张空的空调卡，也不写"照明 + 空调"这种文案。
     */
    val singleMeter: Boolean = false,
    /** 在线选房间（走学校房间树）；null = 没打开 */
    val online: OnlinePickerUi? = null,
) {
    val todayCost: Double get() = EnergyStats.cost(today.total, price)

    /** 选中的那一天在区间里的用量（没选中返回 null） */
    val selectedUsage: DayUsage?
        get() = selectedDay?.let { day -> series.firstOrNull { it.first == day }?.second }
}

class ElectricityViewModel : ViewModel() {

    private val repo: ElectricityRepository = AppGraph.electricityRepository
    private val prefsStore = AppGraph.uiPrefsStore

    private val _state = MutableStateFlow(ElectricityUiState())
    val state: StateFlow<ElectricityUiState> = _state.asStateFlow()

    private var lightRoom = ""
    private var acRoom = ""

    init {
        viewModelScope.launch {
            // 房间号没有默认值：用户设置优先，没设置就从本机已有读数反推（老版本升级上来的人不用重填）
            val (light, ac) = repo.effectiveRooms()
            lightRoom = light
            acRoom = ac
            _state.update { it.copy(loggedIn = AppGraph.authRepository.isLoggedIn()) }
            load()
            loadRoomCatalog()
            refresh()
        }
    }

    /** 房间表是 assets 里的静态文件，读一次就够了；读不出来也不影响手动填 ID */
    private fun loadRoomCatalog() {
        viewModelScope.launch {
            runCatching { AppGraph.roomCatalog.load() }
                .onSuccess { catalog -> _state.update { it.copy(roomCatalog = catalog) } }
                .onFailure { e ->
                    _state.update { it.copy(roomCatalogError = e.message ?: "读取失败") }
                }
        }
    }

    fun refresh() {
        if (_state.value.loading) return
        // 还没设置房间：没有可查的表计，直接读库（界面会显示"去设置房间"）
        if (lightRoom.isBlank() && acRoom.isBlank()) {
            load()
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val snapshot = repo.refreshAll(lightRoom, acRoom)
            val error = snapshot.light.error?.toUserMessage()
                ?: snapshot.ac.error?.toUserMessage()
            _state.update { it.copy(loading = false, error = error) }
            load()
        }
    }

    /**
     * 保存宿舍房间。
     *
     * [label] 是选择器给的可读名（"柳园20号楼 101"）；手动填 ID 时传空字符串，
     * 界面就会退回显示两串 ID。房间号进数据库，可读名进偏好存储（不涉及数据库迁移）。
     */
    fun saveRooms(light: String, ac: String, label: String = "") {
        viewModelScope.launch {
            // 选择器选的是**一间宿舍**的两套表计，所以两路共用一个可读名
            persistRooms(light, ac, lightLabel = label, acLabel = label)
        }
    }

    /**
     * 从学校账户读取"我绑定的房间"，填进对应的那一路。
     *
     * 用途：内置房间表没有的楼栋（亿达学生公寓、护理学院、洛阳校区……）也能用 ——
     * 不需要用户知道任何内部房间号，学校那边住哪就读到哪。
     *
     * 规则：服务端给 `levelName`（实测"照明房间"/"空调房间"），据此决定填哪一路；
     * **另一路一律不动**，只如实说明 —— 服务端只返回一个绑定房间，
     * 拿它去猜另一个（或留下别的楼的旧值）都会给出错误的表计。
     */
    fun readRoomFromSchool() {
        if (_state.value.roomReading) return
        _state.update { it.copy(roomReading = true, roomRead = null) }
        viewModelScope.launch {
            val result = runCatching { repo.boundRoom() }
            val location = result.getOrNull()
            if (location == null) {
                _state.update {
                    it.copy(
                        roomReading = false,
                        roomRead = RoomReadUi(
                            ok = false,
                            text = "读取失败：${result.exceptionOrNull()?.toUserMessage() ?: "未知错误"}",
                        ),
                    )
                }
                return@launch
            }
            val target = if (location.isAcMeter) "空调" else "照明"
            val light = if (location.isAcMeter) lightRoom else location.room
            val ac = if (location.isAcMeter) location.room else acRoom
            // 只写填的那一路的名字 —— 另一路（可能是别的楼、别的房间）的名字必须留着
            if (location.isAcMeter) {
                persistRooms(light, ac, acLabel = location.shortName)
            } else {
                persistRooms(light, ac, lightLabel = location.shortName)
            }
            // 另一路没值就说清楚：服务端只给一个绑定房间，不能替用户猜第二个
            val otherBlank = if (location.isAcMeter) light.isBlank() else ac.isBlank()
            _state.update {
                it.copy(
                    roomReading = false,
                    roomRead = RoomReadUi(
                        ok = true,
                        text = "已读取到你的房间：${location.shortName}（${location.levelName}），" +
                            "已填入「$target」。" +
                            if (otherBlank) {
                                "学校只返回这一个绑定房间，另一路要你自己设 —— " +
                                    "只填这一路也能查（有些楼栋本来就只有一套表）。"
                            } else {
                                ""
                            },
                    ),
                )
            }
        }
    }

    // ── 在线选房间（学校房间树）─────────────────────────────────────────
    //
    // 为什么要有这条路：静态房间表只覆盖 6 个区域，**亿达学生公寓（area 106）上游从来没导出过**，
    // 主校区的 99.json 里也没有那些楼 —— 那里的住户在选楼栋时根本找不到自己的楼。
    // 学校自己的电费页不靠静态表，它按层级问服务端；这里复刻同一套，所以 106 也能选到。

    /** 打开在线房间树：从最外层（area）开始问 */
    fun openOnlinePicker() {
        loadOnlineLevel(
            OnlineRequest(OnlinePickerUi.ROOT_LEVEL, EcardClient.LocationScope(), emptyList()),
            backStack = emptyList(),
        )
    }

    /** 选一个节点：叶子就是房间（直接落盘），否则带着它继续往下问一层 */
    fun onlineChoose(node: EcardClient.LocationNode) {
        val ui = _state.value.online ?: return
        if (ui.leaf) {
            viewModelScope.launch { applyOnlineRoom(node, ui) }
            return
        }
        val request = ui.descend(node) ?: return
        val here = OnlineRequest(ui.currentType.ifBlank { OnlinePickerUi.ROOT_LEVEL }, ui.scope, ui.steps)
        loadOnlineLevel(request, backStack = ui.backStack + here)
    }

    /** 返回上一层：把上一次的查询重新发一遍（保证和服务端一致） */
    fun onlineBack() {
        val ui = _state.value.online ?: return
        val prev = ui.backStack.lastOrNull()
        if (prev == null) {
            closeOnlinePicker()
            return
        }
        loadOnlineLevel(prev, backStack = ui.backStack.dropLast(1))
    }

    fun closeOnlinePicker() = _state.update { it.copy(online = null) }

    private fun loadOnlineLevel(request: OnlineRequest, backStack: List<OnlineRequest>) {
        _state.update {
            it.copy(
                online = OnlinePickerUi(
                    loading = true,
                    scope = request.scope,
                    steps = request.steps,
                    currentType = request.requestType,
                    backStack = backStack,
                ),
            )
        }
        viewModelScope.launch {
            val result = runCatching { repo.locationLevel(request.requestType, request.scope) }
            val level = result.getOrNull()
            if (level == null) {
                _state.update {
                    it.copy(
                        online = it.online?.copy(
                            loading = false,
                            error = "读取房间列表失败：${result.exceptionOrNull()?.toUserMessage() ?: "未知错误"}",
                        ),
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    online = it.online?.copy(
                        loading = false,
                        error = null,
                        currentType = level.locationType,
                        nextType = level.nextLocationType,
                        nodes = level.nodes,
                        leaf = level.end,
                    ),
                )
            }
        }
    }

    /** 选中一个房间节点：id 就是完整房间 ID，按面包屑里的层级名决定填哪一路 */
    private suspend fun applyOnlineRoom(node: EcardClient.LocationNode, ui: OnlinePickerUi) {
        val roomId = node.id
        // 层级名（"照明房间"/"空调用电"…）在面包屑里，用它判断这是哪一路表计。
        // ⚠️ 但**不是所有区域都按表计分层**：亿达学生公寓、洛阳校区是按楼层分的（2层/3层…），
        // 那种楼通常整间一个表 —— 这时填主表计那一行（"照明"），并且如实告诉用户。
        val levelName = ui.steps.lastOrNull { it.type.equals("level", ignoreCase = true) }?.name
            ?: ui.steps.lastOrNull()?.name.orEmpty()
        val isAc = levelName.contains("空调")
        val namedMeter = isAc || levelName.contains("照明")
        val target = if (isAc) "空调" else "照明"
        val light = if (isAc) lightRoom else roomId
        val ac = if (isAc) roomId else acRoom
        val label = (ui.steps.map { it.name } + node.name).filter { it.isNotBlank() }.joinToString(" ")
        // 同上：只写刚选的那一路的名字
        if (isAc) {
            persistRooms(light, ac, acLabel = label)
        } else {
            persistRooms(light, ac, lightLabel = label)
        }
        val otherBlank = if (isAc) light.isBlank() else ac.isBlank()
        _state.update {
            it.copy(
                online = null,
                roomRead = RoomReadUi(
                    ok = true,
                    text = "已选中：$label，已填入「$target」（$roomId）。" +
                        if (!namedMeter) {
                            "这一层不是照明/空调分类（这类楼多半整间一个表），所以填在主表计那一行。"
                        } else {
                            ""
                        } +
                        if (otherBlank) "另一路还没设，需要的话再选一次那个层级（如「空调房间」）。" else "",
                ),
            )
        }
    }

    /** 落盘 + 刷新，[saveRooms] 与 [readRoomFromSchool] 共用 */
    /**
     * 落盘 + 刷新。
     *
     * [lightLabel] / [acLabel] 只写传进来的那一路 —— **null 表示"这一路的房间名原样保留"**。
     * 这是必须的：两路可以是两个不同的房间，只改一路时把另一路的名字一起覆盖，
     * 就会出现"查了别的房间却显示自己寝室名"。
     */
    private suspend fun persistRooms(
        light: String,
        ac: String,
        lightLabel: String? = null,
        acLabel: String? = null,
    ) {
        val current = repo.settings()
        repo.saveSettings(
            current.copy(
                lightRoomId = light.trim(),
                acRoomId = ac.trim(),
            ),
        )
        prefsStore.setRoomLabels(light = lightLabel, ac = acLabel)
        lightRoom = light.trim()
        acRoom = ac.trim()
        load()
        refresh()
    }

    /** 只读库：断网也能看历史 */
    fun load() {
        viewModelScope.launch {
            val settings = repo.settings()
            if (lightRoom.isBlank()) lightRoom = settings.lightRoomId.orEmpty()
            if (acRoom.isBlank()) acRoom = settings.acRoomId.orEmpty()

            val prefs = prefsStore.flow.first()
            _state.update { it.copy(price = prefs.energyPricePerKwh.toDouble()) }

            // 房间显示名：**按槽位各自算**。选择器/读取写下的名字优先（表里没有的楼栋只能靠它），
            // 没有就用内置房间表按这一路的房间 ID 反查（柳20-101），再没有就显示房间 ID。
            val lightLabel = prefs.lightRoomLabel?.takeIf { it.isNotBlank() }
                ?: AppGraph.roomCatalog.labelForRoom(lightRoom).orEmpty()
            val acLabel = prefs.acRoomLabel?.takeIf { it.isNotBlank() }
                ?: AppGraph.roomCatalog.labelForRoom(acRoom).orEmpty()

            val since = EnergyStats.dayStart(
                LocalDate.now().minusDays(WINDOW_DAYS.toLong()),
            )
            val light = repo.readingsSince(ElectricityRepository.ACCOUNT_TYPE_LIGHT, lightRoom, since)
            val ac = repo.readingsSince(ElectricityRepository.ACCOUNT_TYPE_AC, acRoom, since)

            val lightSamples = light.map { EnergySample(it.ts, it.kwh) }
            val acSamples = ac.map { EnergySample(it.ts, it.kwh) }
            val daily = EnergyStats.mergeDaily(lightSamples, acSamples)
            val estimated = EnergyStats.estimatedDays(lightSamples) +
                EnergyStats.estimatedDays(acSamples)

            val today = LocalDate.now()
            val avg = EnergyStats.averageDaily(daily)
            val lightKwh = repo.latest(ElectricityRepository.ACCOUNT_TYPE_LIGHT, lightRoom)?.kwh
            val acKwh = repo.latest(ElectricityRepository.ACCOUNT_TYPE_AC, acRoom)?.kwh

            // 「还能用几天」必须按**表计各自**的日均算（照明余额 ÷ 照明日均），
            // 而且窗口由 EnergyStats 统一 —— 这样桌面小组件上的「可用约 N 天」与这里
            // 是同一个数。以前两路都除以上面那个"合计日均"（还是 120 天的），
            // 照明/空调的可用天数一起被低估（真机：15.9 天 vs 28 天）。
            val lightAvgForDaysLeft = EnergyStats.recentDailyAverage(lightSamples)
            val acAvgForDaysLeft = EnergyStats.recentDailyAverage(acSamples)

            _state.update { s ->
                val series = EnergyStats.series(daily, today, s.rangeDays)
                s.copy(
                    price = prefs.energyPricePerKwh.toDouble(),
                    series = series,
                    bars = series.map { (day, usage) ->
                        UsageBar(
                            day = day,
                            light = usage.light,
                            ac = usage.ac,
                            estimated = day in estimated,
                        )
                    },
                    estimatedDays = estimated,
                    lightBalance = lightSamples.map { it.ts to it.kwh },
                    acBalance = acSamples.map { it.ts to it.kwh },
                    yesterday = daily[today.minusDays(1)] ?: DayUsage(),
                    today = daily[today] ?: DayUsage(),
                    avgDaily = avg,
                    sampleCount = lightSamples.size + acSamples.size,
                    lastSampleAt = (lightSamples + acSamples).maxOfOrNull { it.ts },
                    dayRows = daily.entries
                        .filter { it.value.total > 0.0 }
                        .sortedByDescending { it.key }
                        .take(60)
                        .map { (day, usage) ->
                            UsageDayRow(
                                day = day,
                                usage = usage,
                                cost = EnergyStats.cost(usage.total, prefs.energyPricePerKwh.toDouble()),
                            )
                        },
                    light = MeterUi(
                        label = "照明",
                        roomId = lightRoom,
                        kwh = lightKwh,
                        fetchedAt = light.lastOrNull()?.ts,
                        error = null,
                        daysLeft = EnergyStats.daysLeft(lightKwh ?: 0.0, lightAvgForDaysLeft),
                        roomLabel = lightLabel,
                    ),
                    ac = MeterUi(
                        label = "空调",
                        roomId = acRoom,
                        kwh = acKwh,
                        fetchedAt = ac.lastOrNull()?.ts,
                        error = null,
                        daysLeft = EnergyStats.daysLeft(acKwh ?: 0.0, acAvgForDaysLeft),
                        roomLabel = acLabel,
                    ),
                    // 单表计：**只有亿达**那种"整间一个表"的区域（判定见 isSingleMeterRoom）。
                    // 别的楼即使只设了一路，也照旧按双表计显示（空调那格写"未设置房间"）。
                    singleMeter = isSingleMeterRoom(lightRoom) || isSingleMeterRoom(acRoom),
                )
            }
        }
    }

    fun setRange(days: Int) {
        _state.update { it.copy(rangeDays = days) }
        load()
    }

    fun selectDay(day: LocalDate?) = _state.update {
        it.copy(selectedDay = if (it.selectedDay == day) null else day)
    }

    fun setPrice(value: Double) {
        viewModelScope.launch {
            prefsStore.setEnergyPricePerKwh(value.toFloat())
            load()
        }
    }

    private companion object {
        /** 只读最近这么多天的读数，够画 90 天图和日均 */
        const val WINDOW_DAYS = 120
    }
}
