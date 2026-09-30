package com.zzupocket.app.data.eas

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
        for (datum in data) {
            val c = datum.course
            for (s in datum.schedules) {
                if (s.weekIndex <= 0 || s.weekday !in 1..7 || s.startUnit <= 0) continue
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
                    date = s.date?.take(10),
                    startTime = s.startTime,
                    endTime = s.endTime,
                    teacherName = s.teacherName?.takeIf { it.isNotBlank() },
                    roomText = roomTextOf(s),
                    campusText = s.room?.campus?.nameZh,
                    customPlace = s.customPlace?.takeIf { it.isNotBlank() },
                )
            }
        }
        return out
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
            if (it.code == 401 || it.code == 403) {
                throw AppError.ReLoginRequired("教务登录态已失效，请重新登录")
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
            env.data ?: throw AppError.Parse("教室查询响应缺少 data")
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
            if (it.code == 401 || it.code == 403) {
                throw AppError.ReLoginRequired("教务登录态已失效，请重新登录")
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
        private const val JWXT = "https://jwxt.zzu.edu.cn"

        /** 教务微服务前缀：教室占用那批接口都在这里 */
        private const val MICRO = "$JWXT/eams-micro-server/api/v1"
        private const val ALL_SEMESTERS_URL = "$JWXT/eams-door/api/v1/calendar/get-all-semesters"
        private const val COURSE_URL = "$JWXT/eams-micro-server/api/v1/lesson/student/course-table"
        private const val CURRENT_SEMESTER_URL = "$JWXT/eams-micro-server/api/v1/semester/current-semester"
        private const val PERIODS_URL = "$JWXT/eams-micro-server/api/v1/room/system/course-units"
    }
}
