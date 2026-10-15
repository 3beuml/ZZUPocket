package com.zzupocket.app.data.eas

import android.util.Log
import com.zzupocket.app.core.AppError
import com.zzupocket.app.core.ScheduleText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 教务系统（新版 EAMS / 树维）客户端。
 *
 * 协议对齐 ZZU.Py 的 `UndergradEASClient`（MIT）：
 *
 *  - 学期列表   GET /eams-door/api/v1/calendar/get-all-semesters
 *  - 当前学期   GET /eams-micro-server/api/v1/semester/current-semester
 *  - 整学期课表 GET /eams-micro-server/api/v1/lesson/student/course-table/{semesterId}
 *
 * ⚠️ 两个要点：
 *  1. 鉴权头是 `Authorization: <userToken>` —— **裸令牌，没有 `Bearer ` 前缀**。
 *  2. 整学期课表**一次请求拿全**，且每条上课记录自带 `weekIndex`（教学周号）
 *     与真实日期 `date`，所以切周 / 课程详情 / 课程总览全部可以在本地算，不需要再请求。
 *
 * JSON 字段是 camelCase（服务端契约如此）。
 */
class EasClient(
    private val client: OkHttpClient,
    private val json: Json,
) {

    // ── DTO ──────────────────────────────────────────────────────────────

    @Serializable
    private data class Envelope<T>(
        val result: Int = -1,
        val msg: String? = null,
        val message: String? = null,
        val data: T? = null,
    )

    @Serializable
    data class SemesterDto(
        val id: Int = 0,
        val code: String = "",
        val nameZh: String = "",
        val nameEn: String? = null,
        val schoolYear: String = "",
        val startDate: String? = null,
        val endDate: String? = null,
        val weekStartOnSunday: Boolean = false,
        val countInTerm: Boolean = false,
        val season: String? = null,
        val weekIndices: List<Int> = emptyList(),
    )

    @Serializable
    data class NamedDto(val nameZh: String? = null, val nameEn: String? = null, val code: String? = null)

    @Serializable
    data class RoomDto(
        val nameZh: String? = null,
        val building: NamedDto? = null,
        val campus: NamedDto? = null,
    )

    @Serializable
    data class CourseDto(
        val id: Int = 0,
        val code: String = "",
        val nameZh: String = "",
        val nameEn: String? = null,
        val credits: Double = 0.0,
    )

    @Serializable
    data class ScheduleDto(
        val weekday: Int = 0,
        val startUnit: Int = 0,
        val endUnit: Int = 0,
        val weekIndex: Int = 0,
        val date: String? = null,
        val startTime: String? = null,
        val endTime: String? = null,
        val teacherName: String? = null,
        val room: RoomDto? = null,
        val customPlace: String? = null,
        val startUnitNameZh: String? = null,
        val endUnitNameZh: String? = null,
        val lessonType: String? = null,
        val state: String? = null,
    )

    @Serializable
    data class DatumDto(
        val id: Int = 0,
        val course: CourseDto = CourseDto(),
        val schedules: List<ScheduleDto> = emptyList(),
        val teacherAssignmentList: List<String> = emptyList(),
        val openDepartment: NamedDto? = null,
        val stdCount: Int = 0,
    )

    /** 节次定义（含起止时间）。来自 /room/system/course-units */
    @Serializable
    data class PeriodDto(
        val indexNo: Int = 0,
        val nameZh: String = "",
        val startTime: Int = 0,
        val endTime: Int = 0,
        val dayPart: String = "",
        val segmentIndex: Int = 0,
    )

    /** 课表里的一节课（已扁平化，直接用） */
    data class Session(
        val courseCode: String,
        val courseName: String,
        val courseId: Int,
        val credits: Double,
        val openDepartment: String?,
        val teachers: List<String>,
        val weekIndex: Int,
        val weekday: Int,
        val startUnit: Int,
        val endUnit: Int,
        val date: String?,
        val startTime: String?,
        val endTime: String?,
        val teacherName: String?,
        val roomText: String?,
        val campusText: String?,
        val customPlace: String?,
    )

    // ── API ──────────────────────────────────────────────────────────────

    suspend fun fetchSemesters(userToken: String): List<SemesterDto> =
        getData(ALL_SEMESTERS_URL, userToken, "学期列表")

    suspend fun fetchCurrentSemester(userToken: String): SemesterDto? =
        getData(CURRENT_SEMESTER_URL, userToken, "当前学期")

    /** 节次时刻表（作息）。有了它，"第 3 节"才知道是几点几分。 */
    suspend fun fetchPeriods(userToken: String): List<PeriodDto> =
        getData(PERIODS_URL, userToken, "节次表")

    /**
     * 原始调用：给「教室接口自检」用。
     *
     * 故意**不做反序列化** —— 探测的目的就是看服务端到底返回了什么。
     * 状态码的意义：200 = 命中；**405 = 路径存在但要用 POST**；404 = 不存在；
     * 401/403 = 存在但鉴权不合（我们用的是登录后的教务令牌，正常不会出现）。
     */
    suspend fun rawCall(
        pathOrUrl: String,
        userToken: String,
        bodyJson: String? = null,
        usePost: Boolean = false,
        headerName: String = "Authorization",
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else "$JWXT$pathOrUrl"
        val builder = Request.Builder().url(url).header(headerName, userToken)
        if (usePost) {
            builder.post(
                (bodyJson ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
        } else {
            builder.get()
        }
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw AppError.Network("无法连接教务系统", e)
        }
        response.use { it.code to it.body?.string().orEmpty() }
    }

    /**
     * 拉取整学期课表并扁平化成 [Session] 列表。
     * 注意：这里**不**按周过滤，一次拿全，之后切周纯本地。
     */
    suspend fun fetchCourseTable(userToken: String, semesterId: Int): List<Session> {
        val url = "$COURSE_URL/$semesterId"
        val data = getData<List<DatumDto>>(url, userToken, "课表")
        val out = ArrayList<Session>(data.size * 2)
        var noGridKept = 0
        var dropped = 0
        for (datum in data) {
            val c = datum.course
            for (s in datum.schedules) {
                val date = s.date?.take(10)
                // ⚠️ 以前这里把"没有教学周 / 星期 / 节次"的记录**直接丢掉** ——
                // 而学校的**临时补课**恰恰经常只给一个日期，于是整条补课在 App 里根本不存在。
                // 现在：只要带日期就收下来（补不出网格的记录靠**日期匹配**显示，今天页/组件都能看到）；
                // 连日期都没有、又不在周网格里的，确实没有位置可放，只能丢。
                val hasGrid = s.weekIndex > 0 && s.weekday in 1..7 && s.startUnit > 0
                if (!hasGrid && date == null) {
                    dropped++
                    continue
                }
                if (!hasGrid) noGridKept++
                val endUnit = if (s.endUnit >= s.startUnit) s.endUnit else s.startUnit
                out += Session(
                    courseCode = c.code.ifBlank { c.nameZh },
                    courseName = c.nameZh,
                    courseId = c.id,
                    credits = c.credits,
                    openDepartment = datum.openDepartment?.nameZh,
                    teachers = datum.teacherAssignmentList.filter { it.isNotBlank() },
                    weekIndex = s.weekIndex,
                    weekday = s.weekday,
                    startUnit = s.startUnit,
                    endUnit = endUnit,
                    date = date,
                    startTime = s.startTime,
                    endTime = s.endTime,
                    teacherName = s.teacherName?.takeIf { it.isNotBlank() },
                    roomText = roomTextOf(s),
                    campusText = s.room?.campus?.nameZh,
                    customPlace = s.customPlace?.takeIf { it.isNotBlank() },
                )
            }
        }
        logScheduleShape(data, out.size, noGridKept, dropped)
        return out
    }

    /**
     * 课表原始形状的**一行小结**（`ScheduleRaw` 标签）。
     *
     * 只记**计数与枚举取值分布**，不含课程名 / 教室 / 教师 —— 出问题时看 logcat 就能判断：
     *   · 学校的"补课"到底带不带教学周/星期/节次（`noGrid`）；
     *   · `state`（课程状态）与 `lessonType`（课时类型）都有哪些取值 —— App 目前**没有用**这两个字段，
     *     要区分正常课/调课/补课就得先知道它们长什么样。
     */
    private fun logScheduleShape(data: List<DatumDto>, kept: Int, noGridKept: Int, dropped: Int) {
        runCatching {
            val all = data.flatMap { it.schedules }
            val states = all.groupingBy { it.state ?: "null" }.eachCount()
            val types = all.groupingBy { it.lessonType ?: "null" }.eachCount()
            Log.i(
                TAG_SCHEDULE,
                "课表原始：${data.size} 门课 / ${all.size} 条记录 → 入库 $kept 条" +
                    "（其中无周网格、只有日期 $noGridKept 条），丢弃 $dropped 条；" +
                    "state=$states lessonType=$types",
            )
        }
    }

    /** 优先取"楼栋+房间"，其次只有房间名，最后才用自定义地点 */
    private fun roomTextOf(s: ScheduleDto): String? {
        val room = s.room
        val building = room?.building?.nameZh
        val name = room?.nameZh
        val raw = when {
            !building.isNullOrBlank() && !name.isNullOrBlank() -> "$building $name"
            !name.isNullOrBlank() -> name
            !s.customPlace.isNullOrBlank() -> s.customPlace
            else -> null
        }
        // ⚠️ 学校给的教室名**自带楼栋前缀**（nameZh = "南2_206"），而楼栋名又是"南2"，
        // 直接拼会得到"南2 南2_206"。统一交给 ScheduleText 规整成"南2 206"。
        return ScheduleText.tidyPlace(raw)
    }

    // ── 教室占用（P5）─────────────────────────────────────────────────
    // 接口清单来自学校自己的 H5（jwxt.zzu.edu.cn/eams-room-occupy-app/，「教室占用」）。
    // 实测要点：
    //  · 鉴权用 Authorization: <教务令牌>，不需要 H5 那个 X-Id-Token
    //  · `/room/place/rooms` 的请求体**绝不能带 campusAssoc**（传字符串/对象全 500，
    //    服务端说 CampusAssoc 没有可用构造函数）→ 校区过滤靠传该校区下的楼栋 id
    //  · 返回里每间教室自带 roomOccupationInfoVms（当天的占用时间段，HHmm 整数）

    @Serializable
    data class CampusDto(
        val id: Long = 0,
        val nameZh: String = "",
        val code: String = "",
    )

    @Serializable
    data class BuildingDto(
        val id: Long = 0,
        val nameZh: String = "",
        val code: String = "",
        val campusAssoc: Long = 0,
    )

    @Serializable
    data class RoomTypeDto(
        val id: Long = 0,
        val nameZh: String = "",
        val code: String = "",
    )

    /** 一间教室当天的占用记录 */
    @Serializable
    data class OccupancyDto(
        val id: Long = 0,
        val activityId: Long = 0,
        val roomName: String = "",
        val date: String? = null,
        /** HHmm 整数，和节次表同一套格式（800 = 08:00） */
        val startTime: Int = 0,
        val endTime: Int = 0,
        val startTimeString: String? = null,
        val endTimeString: String? = null,
        /** Lesson / RoomBorrow / Exam / RoomOccupy … */
        val activityType: String? = null,
        /** 例："课程：流行病学(523057-001, 公共卫生学院)" —— 默认不显示 */
        val activityName: String? = null,
        val teacherName: String? = null,
    )

    @Serializable
    data class ClassroomDto(
        val id: Long = 0,
        val nameZh: String = "",
        val code: String = "",
        val buildingId: Long = 0,
        val campusNameZh: String? = null,
        /** ⚠️ 实测不可靠（北1_101 给的是 0），所以排序按教室号，不用它 */
        val floor: Int = 0,
        val virtual: Boolean = false,
        val enabled: Boolean = true,
        val seatsForLesson: Int = 0,
        val roomOccupationInfoVms: List<OccupancyDto> = emptyList(),
    )

    @Serializable
    data class PageInfoDto(
        val currentPage: Int = 1,
        val rowsInPage: Int = 0,
        val rowsPerPage: Int = 30,
        val totalRows: Int = 0,
        val totalPages: Int = 1,
    )

    @Serializable
    data class ClassroomPageDto(
        val data: List<ClassroomDto> = emptyList(),
        @kotlinx.serialization.SerialName("_page_") val page: PageInfoDto? = null,
    )

    @Serializable
    data class RoomQueryBody(
        val currentPage: Int,
        val pageSize: Int,
        val buildingIds: List<Long>,
        val roomTypeIds: List<Long> = emptyList(),
        val floors: List<Int> = emptyList(),
        val minSeat: String = "",
        val maxSeat: String = "",
        val date: String,
    )

    suspend fun fetchCampuses(userToken: String): List<CampusDto> =
        getData("$MICRO/room/place/campus", userToken, "校区列表")

    suspend fun fetchRoomTypes(userToken: String): List<RoomTypeDto> =
        getData("$MICRO/room/place/roomTypes", userToken, "教室类型")

    /** 楼层只是 1..20 的数字数组 */
    suspend fun fetchFloors(userToken: String): List<Int> =
        getData("$MICRO/room/place/floors", userToken, "楼层")

    /** 楼栋；[campusAssoc] 传校区 id */
    suspend fun fetchBuildings(userToken: String, campusAssoc: Long?): List<BuildingDto> =
        getData("$MICRO/room/place/building?campusAssoc=${campusAssoc ?: ""}", userToken, "楼栋列表")

    /**
     * 教室列表 + 每间教室当天的占用。
     * ⚠️ 请求体里**不能出现 campusAssoc**（见上面注释）。
     */
    suspend fun fetchRooms(
        userToken: String,
        body: RoomQueryBody,
    ): ClassroomPageDto = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(RoomQueryBody.serializer(), body)
        val request = Request.Builder()
            .url("$MICRO/room/place/rooms".toHttpUrl())
            .header("Authorization", userToken)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw AppError.Network("无法连接教务系统，请检查网络", e)
        }
        response.use {
            val text = it.body?.string().orEmpty()
            authRejectOf(it.code, "教室占用")?.let { err ->
                Log.w(TAG_AUTH, "教务拒绝：HTTP ${it.code} /room/place/rooms（响应体 ${text.length} 字节）")
                throw rejectExplained(it.code, "教室占用", userToken, err)
            }
            if (!it.isSuccessful) {
                throw AppError.Server(it.code, "教室查询失败（HTTP ${it.code}）：${serverMessageOf(text)}")
            }
            val env = try {
                json.decodeFromString<Envelope<ClassroomPageDto>>(text)
            } catch (e: Exception) {
                throw AppError.Parse("教室查询响应无法解析（顶层字段：${EasDiagnostics.topKeys(json, text)}）", e)
            }
            if (env.result != 0) {
                throw AppError.Server(env.result, "教室查询失败：${env.message ?: env.msg ?: "未知原因"}")
            }
            val page = env.data ?: throw AppError.Parse("教室查询响应缺少 data")
            logOccupancyTypes(page)
            page
        }
    }

    /**
     * 诊断：教室占用里出现了哪些 `activityType`（`RoomOccupancy` 标签）。
     *
     * 为什么关心：`activityType` 里有 **`ScheduleChange`（调课申请）**——
     * 学校如果把这门课的**补课/调课**登记进教务，那间教室当天就会多出这样一条记录。
     * 个人课表接口（course-table）实测不含周末补课，所以"补课到底还在哪"这个问题，
     * 用这条日志就能判断：如果这里能出现 ScheduleChange，我们就有路子在 App 里把它显示出来。
     * **只记类型与条数，不记课程名/教师姓名。**
     */
    private fun logOccupancyTypes(page: ClassroomPageDto) {
        runCatching {
            val rooms = page.data
            val rows = rooms.flatMap { it.roomOccupationInfoVms }
            val types = rows.map { it.activityType ?: "null" }
                .groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }
                .joinToString(" ") { "${it.key}=${it.value}" }
            Log.i(
                TAG_OCCUPANCY,
                "教室占用：${rooms.size} 间教室 / ${rows.size} 条记录；activityType: $types",
            )
        }
    }

    /** 从 Spring 的错误 JSON 里抠出 message —— 那才是"为什么失败"的唯一线索 */
    private fun serverMessageOf(text: String): String = try {
        val root = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
        val message = root?.get("message")?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        }
        message?.take(300) ?: text.take(200)
    } catch (_: Exception) {
        text.take(200)
    }

    /**
     * 教务拒绝时的「说明版」错误 —— 分清"令牌问题"与"这个账号本来就没角色"。
     *
     * 401/403 有两种原因，处置方式正好相反：
     *  - 令牌被吊销 / 无效 → 重新登录有用；
     *  - **这个账号在本科教务里没有角色**（研究生归研究生院管）→ 重登一万次也没用。
     * 用户报告里的"重登死循环"就出在这里（2026-10-14 issue #2）。
     *
     * 所以这里用**同一枚令牌**顺手探一次本科教务的用户信息接口（校外可达），
     * 判明学籍层次再决定话术。探针失败（网络问题等）**不猜**，退回原来的提示。
     */
    private suspend fun rejectExplained(
        code: Int,
        what: String,
        userToken: String,
        fallback: AppError,
    ): AppError {
        if (code != 401 && code != 403) return fallback
        val probe = runCatching { UndergradIdentityProbe.probe(userToken, client) }.getOrNull() ?: return fallback
        return when (probe.track) {
            com.zzupocket.app.domain.AcademicTrack.NOT_UNDERGRAD -> AppError.Server(
                code,
                "你的账号在本科教务里没有课表权限（$what · HTTP $code）。研究生的课表在研究生院那套系统里，" +
                    "本版还没接入 —— 重新登录也没用，这不是你的登录态问题。电费、校园卡不受影响。",
            )
            else -> fallback
        }
    }

    private suspend inline fun <reified T> getData(
        url: String,
        userToken: String,
        what: String,
    ): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url.toHttpUrl())
            // ⚠️ 裸令牌，不能加 "Bearer " 前缀
            .header("Authorization", userToken)
            .get()
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw AppError.Network("无法连接教务系统，请检查网络", e)
        }

        response.use {
            val text = it.body?.string().orEmpty()
            // 教务网关拒绝时响应体是**空的**（实测：不带鉴权/裸令牌/Bearer/cookie/X-Id-Token
            // 五种写法一律 401 + content-length 0），所以这里只留状态码与路径供 logcat 取证。
            authRejectOf(it.code, what)?.let { err ->
                Log.w(TAG_AUTH, "教务拒绝：HTTP ${it.code} ${url.substringAfter("zzu.edu.cn", url)}（响应体 ${text.length} 字节）")
                throw rejectExplained(it.code, what, userToken, err)
            }
            if (!it.isSuccessful) throw AppError.Server(it.code, "教务返回 HTTP ${it.code}（$what）")

            val env = try {
                json.decodeFromString<Envelope<T>>(text)
            } catch (e: Exception) {
                throw AppError.Parse(
                    "教务${what}响应无法解析（顶层字段：${EasDiagnostics.topKeys(json, text)}）",
                    e,
                )
            }
            if (env.result != 0) {
                throw AppError.Server(env.result, "教务${what}失败：${env.msg ?: env.message ?: "未知原因"}")
            }
            env.data ?: throw AppError.Parse("教务${what}响应缺少 data")
        }
    }

    companion object {
        private const val TAG_SCHEDULE = "ScheduleRaw"

        /** 教室占用的 activityType 分布（判断补课有没有被登记成 ScheduleChange） */
        private const val TAG_OCCUPANCY = "RoomOccupancy"
        /** 教务网关根地址；`EasAuthProbe`（鉴权自检）也要用，所以不是 private */
        internal const val JWXT = "https://jwxt.zzu.edu.cn"

        /** logcat 里取「教务拒绝了什么」的记录（只含状态码与路径，不含令牌值） */
        private const val TAG_AUTH = "EasAuth"

        /**
         * 401/403 各自的错误；其他状态码返回 null。
         *
         * **两者必须分开**：401 = 网关不认这个令牌（重登有意义）；403 = 令牌被识别但无权限
         * （重登没用，是账号/院系权限问题）。以前合并成同一句文案，等于把最有价值的线索丢掉
         * —— 2026-10-14 那位同学"一卡通正常、唯独教务报错"的报告，正是靠这个区分才能继续往下查。
         * 抽成纯函数是为了能单测文案（见 EasAuthRejectTest）。
         */
        internal fun authRejectOf(code: Int, what: String): AppError? = when (code) {
            401 -> AppError.ReLoginRequired(
                "教务未通过鉴权（HTTP 401 · $what）：登录态可能已被服务端吊销，请重新登录；" +
                    "重登后若依旧，请到「更多 → 登录状态 → 教务鉴权自检」跑一次并把结果发给开发者。",
            )
            403 -> AppError.Server(
                403,
                "教务拒绝访问（HTTP 403 · $what）：登录态被识别但无访问权限，多为账号/院系权限问题，重新登录无用。",
            )
            else -> null
        }

        /** 教务微服务前缀：教室占用那批接口都在这里 */
        private const val MICRO = "$JWXT/eams-micro-server/api/v1"
        private const val ALL_SEMESTERS_URL = "$JWXT/eams-door/api/v1/calendar/get-all-semesters"
        private const val COURSE_URL = "$JWXT/eams-micro-server/api/v1/lesson/student/course-table"
        private const val CURRENT_SEMESTER_URL = "$JWXT/eams-micro-server/api/v1/semester/current-semester"
        private const val PERIODS_URL = "$JWXT/eams-micro-server/api/v1/room/system/course-units"
    }
}
