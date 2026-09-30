package com.zzupocket.app.data.ecard

import com.zzupocket.app.core.AppGraph
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归测试：电量查询请求体必须把**每个字段**都发出去，包括空字符串和等于默认值的字段。
 *
 * 背景：kotlinx.serialization 默认 `encodeDefaults = false`，带默认值的字段不会被序列化。
 * EnergyQueryBody 里 `utilityType`、`bigArea`、`unit`、`subArea` 恰好都有默认值，
 * 于是请求体少了 `utilityType`，服务端返回空的 templateList，
 * 前端表现为"电量响应里没有 quantity 字段"——极难从现象反推原因。
 *
 * 这里刻意使用生产环境的 [AppGraph.json]，这样一旦有人去掉 encodeDefaults，
 * 测试就会直接失败。
 */
class EnergyQuerySerializationTest {

    private val json = AppGraph.json

    @Test
    fun `每个字段都要出现在请求体里，包括空字符串`() {
        val body = json.encodeToString(
            EcardClient.EnergyQueryBody.serializer(),
            EcardClient.EnergyQueryBody(
                area = "99",
                building = "16",
                level = "32",
                room = "99-16--32-8101",
            ),
        )
        val obj = json.parseToJsonElement(body).jsonObject

        assertEquals("electric", obj["utilityType"]?.jsonPrimitive?.content)
        assertEquals("", obj["bigArea"]?.jsonPrimitive?.content)
        assertEquals("99", obj["area"]?.jsonPrimitive?.content)
        assertEquals("16", obj["building"]?.jsonPrimitive?.content)
        assertEquals("", obj["unit"]?.jsonPrimitive?.content)
        assertEquals("32", obj["level"]?.jsonPrimitive?.content)
        assertEquals("99-16--32-8101", obj["room"]?.jsonPrimitive?.content)
        assertEquals("", obj["subArea"]?.jsonPrimitive?.content)
    }

    @Test
    fun `房间号按 area-building--level-room 正确拆分`() {
        val parsed = EcardClient.parseRoomId("99-16--32-8101")
        assertEquals("99", parsed.area)
        assertEquals("16", parsed.building)
        assertEquals("32", parsed.level)
        assertEquals("99-16--32-8101", parsed.room)
    }
}
