package com.zzupocket.app.data.ecard

import android.util.Log
import com.zzupocket.app.core.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 一卡通（校园卡 / 电费）客户端。
 *
 * 协议对齐 ZZU.Py 的 `ECardClient`（MIT）：
 *
 *  1. GET  /server/auth/host/open?host=11&org=2&token={userToken}
 *     → **不跟随重定向**，从 Location 头里取 `tid`
 *  2. POST /server/auth/getToken   body {"tid": ...}      → resultData.{accessToken, refreshToken}
 *  3. POST /server/utilities/account  header Authorization: {accessToken}
 *     body {utilityType, bigArea, area, building, unit, level, room, subArea}
 *     → resultData.templateList 里 code == "quantity" 的那一项就是剩余电量
 *
 * 关键结论（已由 ZZUElectricityMonitor 实证）：
 * **照明与空调是两套电表，走同一个接口、同一套参数，只有 room 不同**。
 * utilityType 恒为 "electric"，不需要区分。
 *
 * 房间 ID 形如 `99-16--32-8101`，含义为 area-building--level-room。
 */
class EcardClient(
    private val client: OkHttpClient,
    private val json: Json,
) {

    @Serializable
    private data class TokenEnvelope(
        val resultData: TokenDto? = null,
        val success: Boolean? = null,
        val message: String? = null,
    )

    @Serializable
    private data class TokenDto(val accessToken: String? = null, val refreshToken: String? = null)

    @Serializable
    private data class AccountEnvelope(
        val resultData: AccountDto? = null,
        val success: Boolean? = null,
        val message: String? = null,
    )

    @Serializable
    private data class AccountDto(val templateList: List<TemplateDto> = emptyList())

    @Serializable
    private data class TemplateDto(val code: String? = null, val name: String? = null, val value: JsonPrimitive? = null)

    @Serializable
    private data class TidBody(val tid: String)

    /**
     * 电量查询请求体。
     *
     * ⚠️ 这里的每个字段都必须真的发出去，包括空字符串和默认值 ——
     * 服务端靠 `utilityType` 判断查哪种费用，缺了它就返回空的 templateList。
     * 序列化用 AppGraph.json（encodeDefaults = true），并有单元测试守着。
     */
    @Serializable
    internal data class EnergyQueryBody(
        val utilityType: String = UTILITY_TYPE_ELECTRIC,
        val bigArea: String = "",
        val area: String,
        val building: String,
        val unit: String = "",
        val level: String,
        val room: String,
        val subArea: String = "",
    )

    data class Tokens(val accessToken: String, val refreshToken: String)

    /**
     * 用 CAS 的 userToken 换 tid。
     * ⚠️ 必须不跟随重定向 —— tid 在 302 的 Location 头里。
     */
    suspend fun fetchTid(userToken: String): String = withContext(Dispatchers.IO) {
        val url = TID_URL.toHttpUrl().newBuilder()
            .addQueryParameter("host", "11")
            .addQueryParameter("org", "2")
            .addQueryParameter("token", userToken)
            .build()
        val response = try {
            client.newCall(Request.Builder().url(url).get().build()).execute()
        } catch (e: IOException) {
            throw AppError.Network("无法连接一卡通服务", e)
        }
        response.use {
            val location = it.header("Location")
            if (location == null) {
                // 报错要带上"实际收到了什么"，否则只能靠猜
                throw AppError.Parse(
                    "一卡通登录未返回重定向（HTTP ${it.code}，" +
                        "content-type=${it.header("Content-Type") ?: "无"}）。" +
                        "可能是统一认证令牌不被一卡通接受，或一卡通接口已变更",
                )
            }
            // Location 可能是绝对 URL，也可能是相对路径（/xxx?tid=...）。
            // 用 request.url.resolve() 两种都能处理；再留一个正则兜底。
            val resolved = it.request.url.resolve(location)
            val tid = resolved?.queryParameter("tid")
                ?: Regex("[?&]tid=([^&\\s]+)").find(location)?.groupValues?.get(1)
            if (tid.isNullOrBlank()) {
                throw AppError.Parse(
                    "一卡通重定向地址里没有 tid 参数" +
                        "（Location=${resolved?.let { u -> "${u.host}${u.encodedPath}" } ?: "相对路径 $location"}，" +
                        "实际参数名：${resolved?.queryParameterNames?.joinToString(",") ?: "无法解析"}）",
                )
            }
            tid
        }
    }

    suspend fun fetchTokens(tid: String): Tokens = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(TidBody.serializer(), TidBody(tid))
        val response = try {
            client.newCall(
                Request.Builder()
                    .url(TOKEN_URL)
                    .post(payload.toRequestBody(JSON_MEDIA))
                    .build(),
            ).execute()
        } catch (e: IOException) {
            throw AppError.Network("无法连接一卡通服务", e)
        }
        val (status, text) = response.use { it.code to it.body?.string().orEmpty() }
        if (status !in 200..299) {
            throw AppError.Server(status, "一卡通换取令牌失败（HTTP $status，路径 /server/auth/getToken）")
        }
        val env = try {
            json.decodeFromString<TokenEnvelope>(text)
        } catch (e: Exception) {
            // 只报字段名，不报值 —— 这个响应体里有令牌
            throw AppError.Parse(
                "一卡通令牌响应无法解析（HTTP $status，顶层字段：${topLevelKeys(text)}）",
                e,
            )
        }
        val dto = env.resultData
        val access = dto?.accessToken
        val refresh = dto?.refreshToken
        if (access.isNullOrEmpty() || refresh.isNullOrEmpty()) {
            throw AppError.Parse(
                "一卡通令牌响应缺少字段（HTTP $status，顶层字段：${topLevelKeys(text)}，" +
                    "resultData 内字段：${resultDataKeysOf(text)}）",
            )
        }
        Tokens(access, refresh)
    }

    /** 查询某个房间的剩余电量（度）。照明房间与空调房间都用这个方法，只是 room 不同。 */
    suspend fun queryRemainingKwh(accessToken: String, roomId: String): Double = withContext(Dispatchers.IO) {
        val parsed = parseRoomId(roomId)
        val payload = json.encodeToString(
            EnergyQueryBody.serializer(),
            EnergyQueryBody(
                area = parsed.area,
                building = parsed.building,
                level = parsed.level,
                room = parsed.room,
            ),
        )
        val response = try {
            client.newCall(
                Request.Builder()
                    .url(ACCOUNT_URL)
                    .header("Authorization", accessToken)
                    .post(payload.toRequestBody(JSON_MEDIA))
                    .build(),
            ).execute()
        } catch (e: IOException) {
            throw AppError.Network("无法连接一卡通服务", e)
        }
        val text = response.use {
            if (!it.isSuccessful) throw AppError.Server(it.code, "电量查询失败（HTTP ${it.code}）")
            it.body?.string().orEmpty()
        }
        val env = try {
            json.decodeFromString<AccountEnvelope>(text)
        } catch (e: Exception) {
            throw AppError.Parse("电量响应无法解析（顶层字段：${topLevelKeys(text)}）", e)
        }
        val templates = env.resultData?.templateList.orEmpty()
        val template = templates.firstOrNull { it.code == "quantity" }
            ?: throw AppError.Parse(
                "电量响应里没有 quantity 字段。" +
                    "resultData 内字段：${resultDataKeysOf(text)}；" +
                    "templateList 条数：${templates.size}；" +
                    "响应片段：${text.take(320)}",
            )
        template.value?.content?.toDoubleOrNull()
            ?: throw AppError.Parse("quantity 不是数字：${template.value?.content}")
    }

    /**
     * 原始调用：给「自检」用。
     *
     * 故意**不做任何反序列化** —— 自检的目的就是看服务端到底返回了什么字段，
     * 所以这里把状态码和响应原文一起交出去。
     */
    suspend fun rawCall(
        path: String,
        accessToken: String?,
        bodyJson: String? = null,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url("$BASE/server$path")
        if (!accessToken.isNullOrEmpty()) builder.header("Authorization", accessToken)
        if (bodyJson == null) {
            builder.get()
        } else {
            builder.post(bodyJson.toRequestBody(JSON_MEDIA))
        }
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw AppError.Network("无法连接一卡通服务", e)
        }
        response.use { it.code to it.body?.string().orEmpty() }
    }

    // ── 校园卡消费 ──────────────────────────────────────────────────────
    // 字段来自 P4-0 的接口自检（v2）实测：
    //   tradeListType → [{type:"1,2,3",name:"全部"},{type:"1",name:"消费"},
    //                    {type:"2",name:"充值"},{type:"3",name:"补助"}]
    //   tradeList     → [{summary, merchantName, date:"YYYY-MM-DD HH:mm:ss",
    //                    amt:"-0.93"（元、带符号、消费为负）, isRefund, operation, journo}]

    /** 交易类型字典：消费 / 充值 / 补助，以及「全部」 */
    suspend fun fetchTradeTypes(accessToken: String): List<TradeTypeDto> {
        val (status, text) = rawCall("/user/tradeListType", accessToken, "{}")
        if (status !in 200..299) {
            throw AppError.Server(status, "查询交易类型失败（HTTP $status）")
        }
        val env = decodeList<TradeTypeDto>(text)
            ?: throw AppError.Parse("交易类型响应无法解析：${text.take(160)}")
        if (!env.success || env.resultData == null) {
            throw AppError.Parse("交易类型查询失败：${env.message ?: "服务端未返回成功"}")
        }
        return env.resultData.orEmpty()
    }

    /**
     * 交易流水。[tradeType] 用字典里的值：`1` 消费 / `2` 充值 / `3` 补助 /
     * `1,2,3` 全部。**必填** —— 传空串服务端会直接报"交易类型不能为空"。
     */
    suspend fun fetchTradeList(
        accessToken: String,
        tradeType: String,
        fromDate: String,
        toDate: String,
        pageNo: Int = 0,
        pageSize: Int = 50,
    ): List<TradeRecordDto> {
        val body = json.encodeToString(
            TradeListBody.serializer(),
            TradeListBody(
                pageSize = pageSize.toString(),
                pageNo = pageNo,
                tradeType = tradeType,
                fromDate = fromDate,
                toDate = toDate,
            ),
        )
        val (status, text) = rawCall("/user/tradeList", accessToken, body)
        if (status !in 200..299) {
            throw AppError.Server(status, "查询消费流水失败（HTTP $status）")
        }
        val env = decodeList<TradeRecordDto>(text)
            ?: throw AppError.Parse("消费流水响应无法解析：${text.take(160)}")
        if (!env.success) {
            throw AppError.Parse("消费流水查询失败：${env.message ?: "服务端未返回成功"}")
        }
        return env.resultData.orEmpty()
    }

    /**
     * 校园卡余额。
     *
     * ⚠️ 走的是**另一个域名**（`info.s.zzu.edu.cn`），头部是 `X-Id-Token`
     * 而且用的是 **CAS 的 userToken**，不是一卡通的 accessToken。
     *
     * ⚠️ 取值有个坑（实测踩过）：响应 `data` 是个**数组**，里面**第一个带 amount 的
     * 条目是 0**（占位/另一张卡），直接"找第一个 amount"会得到 0。
     * 真正的余额在 `data[1]`（zzupy 源码也是取 `[1]`）。所以这里按
     * 「下标 1 优先 → 其次最后一个 → 再退回首个」的顺序取，
     * 并把所有候选写进日志（只记下标和金额，不记任何令牌）方便核对。
     */
    suspend fun fetchCardBalance(userToken: String): Double {
        val (status, text) = rawCardBalance(userToken)
        if (status !in 200..299) {
            throw AppError.Server(status, "查询校园卡余额失败（HTTP $status）")
        }
        return parseCardBalance(text)
    }

    /** 原始响应（自检用）：状态码 + 原文 */
    suspend fun rawCardBalance(userToken: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(
                Request.Builder().url(BALANCE_URL).header("X-Id-Token", userToken).get().build(),
            ).execute()
        } catch (e: IOException) {
            throw AppError.Network("无法连接校园卡服务", e)
        }
        response.use { it.code to it.body?.string().orEmpty() }
    }

    /** 从余额响应里挑出正确的金额，见上面注释里的坑 */
    internal fun parseCardBalance(text: String): Double {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw AppError.Parse("校园卡余额响应不是 JSON：${text.take(120)}")
        val data = root["data"] as? JsonArray
            ?: throw AppError.Parse("校园卡余额响应缺少 data 字段（字段：${root.keys.joinToString(",")}）")

        val candidates = data.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val amount = obj["amount"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            amount?.let { index to it }
        }
        runCatching {
            Log.i(
                TAG_BALANCE,
                "余额候选：" + data.mapIndexed { index, element ->
                    val obj = element as? JsonObject
                    val keys = obj?.keys?.joinToString("|") ?: "非对象"
                    val amount = obj?.get("amount")?.jsonPrimitive?.contentOrNull ?: "无amount"
                    "[$index] amount=$amount keys=$keys"
                }.joinToString("  "),
            )
        }
        data.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: return@forEachIndexed
            val keys = obj.keys.filterNot { it == "amount" }.take(6).joinToString(",")
            runCatching { Log.i(TAG_BALANCE, "data[$index] 其它字段：$keys") }
        }

        val picked = candidates.firstOrNull { it.first == 1 }?.second
            ?: candidates.lastOrNull()?.second
            ?: throw AppError.Parse("校园卡余额响应里没有可解析的 amount：${text.take(200)}")
        return picked
    }

    private inline fun <reified T> decodeList(text: String): ListEnvelope<T>? = try {
        json.decodeFromString<ListEnvelope<T>>(text)
    } catch (_: Exception) {
        null
    }

    @Serializable
    internal data class ListEnvelope<T>(
        val success: Boolean = false,
        val message: String? = null,
        val resultData: List<T>? = null,
        val code: String? = null,
    )

    @Serializable
    data class TradeTypeDto(val type: String = "", val name: String = "")

    @Serializable
    data class TradeRecordDto(
        val summary: String = "",
        val merchantName: String = "",
        val date: String = "",
        /** 单位是**元**、带符号：消费为负、充值为正 */
        val amt: String = "0",
        val isRefund: String = "0",
        val operation: Boolean = false,
        val journo: String = "",
    )

    @Serializable
    private data class TradeListBody(
        val pageSize: String,
        val pageNo: Int,
        val tradeType: String,
        val fromDate: String,
        val toDate: String,
    )

    /** 只取 JSON 顶层字段名，用于诊断（不会带出任何值 / 令牌） */
    private fun topLevelKeys(json_text: String): String = try {
        val obj = json.parseToJsonElement(json_text) as? JsonObject
        obj?.keys?.joinToString(",") ?: "（不是 JSON 对象）"
    } catch (_: Exception) {
        "（无法解析为 JSON）"
    }

    /** resultData 里的字段名，用于判断"字段名变了"还是"服务端确实返回空" */
    private fun resultDataKeysOf(json_text: String): String = try {
        val obj = json.parseToJsonElement(json_text) as? JsonObject
        val rd = obj?.get("resultData") as? JsonObject
        rd?.keys?.joinToString(",") ?: "（无 resultData 或不是对象）"
    } catch (_: Exception) {
        "（无法解析）"
    }

    data class ParsedRoom(val area: String, val building: String, val level: String, val room: String)

    companion object {
        private const val TAG_BALANCE = "EcardBalance"

        const val UTILITY_TYPE_ELECTRIC = "electric"

        private const val BASE = "https://ecard.v.zzu.edu.cn"
        private const val TID_URL = "$BASE/server/auth/host/open"
        private const val TOKEN_URL = "$BASE/server/auth/getToken"
        private const val ACCOUNT_URL = "$BASE/server/utilities/account"

        /** 校园卡余额在**另一个域名**上，头部是 X-Id-Token + CAS userToken */
        private const val BALANCE_URL =
            "https://info.s.zzu.edu.cn/portal-api/v1/thrid-adapter/get-person-info-card-list"

        /** 交易流水的类型值（实测自 /user/tradeListType） */
        const val TRADE_TYPE_ALL = "1,2,3"
        const val TRADE_TYPE_SPEND = "1"
        const val TRADE_TYPE_RECHARGE = "2"
        const val TRADE_TYPE_SUBSIDY = "3"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** 一卡通 accessToken 官方周期是 45 分钟，这里用 40 分钟提前刷新 */
        const val TOKEN_TTL_MS: Long = 40 * 60 * 1000L

        /** `99-16--32-8101` → area=99, building=38, level=74, room=99-16--32-8101 */
        fun parseRoomId(roomId: String): ParsedRoom {
            val parts = roomId.split("--")
            if (parts.size != 2) throw AppError.Parse("房间号格式不正确：$roomId（应为 area-building--level-room）")
            val left = parts[0].split("-")
            val right = parts[1].split("-")
            if (left.size != 2 || right.size != 2) {
                throw AppError.Parse("房间号格式不正确：$roomId")
            }
            return ParsedRoom(area = left[0], building = left[1], level = right[0], room = roomId)
        }
    }
}
