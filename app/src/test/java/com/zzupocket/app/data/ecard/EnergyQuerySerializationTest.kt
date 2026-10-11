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

    /**
     * 「从学校账户读绑定房间」用的请求体**只带 utilityType**、不带任何房间 ——
     * 服务端就是靠这一点返回账号当前绑定的房间（上游 zzupy 的 get_default_room 同款）。
     * 这个字段同样带默认值，所以和上面那个测试一样，必须守住 encodeDefaults。
     */
    @Test
    fun `读绑定房间的请求体只带 utilityType`() {
        val body = json.encodeToString(EcardClient.ConfigBody.serializer(), EcardClient.ConfigBody())
        val obj = json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("utilityType"), obj.keys)
        assertEquals("electric", obj["utilityType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `绑定房间的显示名去掉层级名，空调房间按 levelName 识别`() {
        val light = EcardClient.EcardLocation(
            area = "99",
            areaName = "主校区",
            building = "38",
            buildingName = "松园15号楼",
            level = "73",
            levelName = "照明房间",
            room = "99-38--73-212",
            roomName = "212房间",
        )
        assertEquals("主校区 松园15号楼 212房间", light.shortName)
        assertEquals(false, light.isAcMeter)

        val ac = light.copy(levelName = "空调房间", room = "99-38--74-8212")
        assertEquals("主校区 松园15号楼 212房间", ac.shortName)
        assertEquals(true, ac.isAcMeter)

        // 楼层式分类（洛阳校区那种）与整间一表的楼都按照明处理，别丢信息
        val floor = light.copy(levelName = "5层", roomName = "")
        assertEquals("主校区 松园15号楼 99-38--73-212", floor.shortName)
        assertEquals(false, floor.isAcMeter)
    }
}
