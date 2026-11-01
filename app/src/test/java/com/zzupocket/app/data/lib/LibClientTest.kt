package com.zzupocket.app.data.lib

import com.zzupocket.app.core.AppError
import com.zzupocket.app.core.AppGraph
import java.time.LocalDate
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图书馆记录接口的解析/聚合单测。
 *
 * 响应形状来自 2026-10-29 的 H5 逆向（docs/interfaces/图书馆空间预约系统接口逆向.md）：
 * `{code:0, message:"操作成功", data:{data:[...], total:N}}`。日期字段名与值形态以
 * 座位/空间详情页组件实际画出来的为准（create_time / beginTime …）；响应里的日期
 * 可能是多种形态（"2026-10-29 12:34:56"、ISO、毫秒数），这里全覆盖。
 */
class LibClientTest {

    private val json = AppGraph.json
    private val client = LibClient(
        client = okhttp3.OkHttpClient(),
        json = json,
        session = { LibSession() },
    )

    @Test
    fun `标准信封能解出列表与 total`() {
        val text = """
            {"code":0,"message":"操作成功","data":{
              "data":[
               {"id":"12","name":"201座位","status_name":"已使用","status":"2",
                "nameMerge":"中心馆 三楼","create_time":"2026-10-28 20:01:02",
                "beginTime":"2026-10-28 20:30","endTime":"2026-10-28 23:00"},
               {"id":"13","name":"202座位","status_name":"违约","status":"3",
                "nameMerge":"中心馆 三楼","create_time":"2026-10-01 08:00:00",
                "beginTime":"2026-10-01 08:30","endTime":"2026-10-01 12:00"}],
              "total":2}}
        """.trimIndent()
        val page = client.parsePage(text)
        assertEquals(2, page?.total)
        assertEquals(2, page?.items?.size)
        val first = page!!.items.first()
        assertEquals("201座位", (first["name"] as? JsonPrimitive)?.content)
        assertEquals("2", (first["status"] as? JsonPrimitive)?.content)
    }

    @Test
    fun `total 缺失时按条数兜底，空 data 按 0 条处理`() {
        val noTotal = client.parsePage("""{"code":0,"data":{"data":[{"a":1},{"b":2}]}}""")
        assertEquals(2, noTotal?.total)
        val empty = client.parsePage("""{"code":0,"message":"操作成功","data":{"data":[]}}""")
        assertEquals(0, empty?.total)
        assertTrue(empty?.items?.isEmpty() == true)
    }

    @Test
    fun `会话失效 10001 抛需要重新登录`() {
        val text = """{"code":10001,"message":"您尚未登录","data":[]}"""
        val error = runCatching { client.parsePage(text) }.exceptionOrNull()
        assertTrue(error is AppError.ReLoginRequired)
    }

    @Test
    fun `业务错误码按原样上抛`() {
        val text = """{"code":1,"message":"操作失败"}"""
        val error = runCatching { client.parsePage(text) }.exceptionOrNull()
        assertTrue(error is AppError.Server)
        assertEquals(1, (error as AppError.Server).code)
    }

    @Test
    fun `并非 JSON 时返回 null`() {
        assertNull(client.parsePage("<!DOCTYPE html>"))
    }

    /** 2026-10-31 真机实测：my_wait 在没有待处理预约时回**空响应体**，语义是 0 条 */
    @Test
    fun `空响应体按 0 条处理而不是解析失败`() {
        val page = client.parsePage("")
        assertEquals(0, page?.total)
        assertTrue(page?.items?.isEmpty() == true)
    }

    /** 热力图聚合所需的日期字段：多种值形态都要能解出日期 */
    @Test
    fun `日期字段按候选顺序取、值形态各解`() {
        // create_time 的标准形态
        val standard = buildJsonObject { put("create_time", "2026-10-28 20:01:02") }
        assertEquals(LocalDate.of(2026, 10, 28), LibClient.firstReservationDay(standard))

        // 毫秒时间戳（浮起来按本地时区换算 —— 这里直接用界外的日期锚点算）
        val tsMillis = buildJsonObject { put("create_time", "1794321600000") }
        val msDay = LocalDate.ofInstant(
            java.time.Instant.ofEpochMilli(1794321600000L),
            java.time.ZoneId.systemDefault(),
        )
        assertEquals(msDay, LibClient.firstReservationDay(tsMillis))

        // 秒时间戳
        val tsSec = buildJsonObject { put("create_time", "1794321600") }
        assertEquals(msDay, LibClient.firstReservationDay(tsSec))

        // beginTime 兜底
        val alt = buildJsonObject { put("beginTime", "2026-10-01 08:30") }
        assertEquals(LocalDate.of(2026, 10, 1), LibClient.firstReservationDay(alt))

        // ISO 字符串形态
        val iso = buildJsonObject { put("create_time", "2026-10-15T09:05:00") }
        assertEquals(LocalDate.of(2026, 10, 15), LibClient.firstReservationDay(iso))

        // 嘉候选全部为空 → null
        assertNull(LibClient.firstReservationDay(buildJsonObject { put("nothing", "x") }))
        assertNull(LibClient.firstReservationDay(buildJsonObject { put("create_time", "") }))
        assertNull(LibClient.firstReservationDay(buildJsonObject { put("create_time", "不是日期") }))
    }

    @Test
    fun `时段文本把 begin_end 拼成区间`() {
        val raw = buildJsonObject {
            put("beginTime", "2026-10-28 20:30")
            put("endTime", "2026-10-28 23:00")
        }
        assertEquals("2026-10-28 20:30 ~ 2026-10-28 23:00", LibClient.rangeText(raw))

        // 下划线字段名也要认（空间记录是 begin_time/end_time）
        val raw2 = buildJsonObject {
            put("begin_time", "2026-10-28 20:30")
            put("end_time", "2026-10-28 23:00")
        }
        assertEquals("2026-10-28 20:30 ~ 2026-10-28 23:00", LibClient.rangeText(raw2))

        // 只有一端时直接给那段
        val onlyBegin = buildJsonObject { put("beginTime", "2026-10-28 20:30") }
        assertEquals("2026-10-28 20:30", LibClient.rangeText(onlyBegin))

        assertNull(LibClient.rangeText(buildJsonObject {}))
    }

    @Test
    fun `会话快照的空判定`() {
        assertTrue(LibSession().isEmpty())
        assertFalse(LibSession(token = "t").isEmpty())
        assertFalse(LibSession(cookie = "PHPSESSID=abc").isEmpty())
    }
}
