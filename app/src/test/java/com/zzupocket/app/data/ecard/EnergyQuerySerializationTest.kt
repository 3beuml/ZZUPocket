package com.zzupocket.app.data.ecard

import com.zzupocket.app.core.AppGraph
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * 房间树的请求体：**每个字段都要发出去**（`locationType` 是要问的那一层，
     * 其余是已选中的祖先）。这段形状与响应解析对齐的是**我们自己探针抓到的原文**
     * （见下面那个测试），不是猜的。
     */
    @Test
    fun `房间树请求体带全所有层级字段`() {
        val body = json.encodeToString(
            EcardClient.LocationQueryBody.serializer(),
            EcardClient.LocationQueryBody(
                locationType = "building",
                area = "106",
            ),
        )
        val obj = json.parseToJsonElement(body).jsonObject
        assertEquals("electric", obj["utilityType"]?.jsonPrimitive?.content)
        assertEquals("building", obj["locationType"]?.jsonPrimitive?.content)
        assertEquals("106", obj["area"]?.jsonPrimitive?.content)
        listOf("bigArea", "building", "unit", "level", "room", "subArea").forEach { key ->
            assertEquals("$key 必须发空串，不能省掉", "", obj[key]?.jsonPrimitive?.content)
        }
    }

    /** 层级名 → 字段名（上游 zzupy 的 get_room_dict 就是这么拼的） */
    @Test
    fun `选中一层时把 id 填进对应字段`() {
        val scope = EcardClient.LocationScope()
            .with("area", "106")
            .with("building", "3")
            .with("level", "215")
        assertEquals("106", scope.area)
        assertEquals("3", scope.building)
        assertEquals("215", scope.level)
        assertEquals("", scope.bigArea)
        // H5 里这个层级叫 subarea（全小写），两种都得认
        assertEquals("x", scope.with("subarea", "x").subArea)
        assertEquals("y", scope.with("subArea", "y").subArea)
        // 不认识的层级不要瞎改
        assertEquals(scope, scope.with("garbage", "z"))
    }

    /**
     * 房间树的响应解析 —— 用的是**探针抓到的真实原文**（`/utilities/location`，
     * 见作者本机 `_research/ecard_probe/v2_logcat.txt`）：区域层返回 7 个区域，
     * 其中 **106 就是亿达学生公寓**（静态房间表里从来没有它，只有这条路能拿到）。
     */
    @Test
    fun `区域层响应能解析出七个区域并带出亿达学生公寓`() {
        val raw = """
            {"success":true,"message":"成功","resultData":{"locationType":"area",
            "locationList":[{"id":"102","name":"东校区"},{"id":"99","name":"主校区"},
            {"id":"106","name":"亿达学生公寓"},{"id":"101","name":"北校区"},
            {"id":"103","name":"南校区"},{"id":"104","name":"护理学院"},
            {"id":"105","name":"洛阳校区"}],"nextLocationType":"building","end":false},"code":""}
        """.trimIndent()
        val env = json.decodeFromString<EcardClient.LocationEnvelope>(raw)
        assertTrue("success 应为 true", env.success)
        val level = env.resultData!!.toLevel("area")

        assertEquals("area", level.locationType)
        assertEquals("building", level.nextLocationType)
        assertEquals(false, level.end)
        assertEquals(7, level.nodes.size)
        assertEquals(
            listOf("东校区", "主校区", "亿达学生公寓", "北校区", "南校区", "护理学院", "洛阳校区"),
            level.nodes.map { it.name },
        )
        assertEquals("106", level.nodes.first { it.name == "亿达学生公寓" }.id)
    }

    /** 叶子层（end=true）：节点 id 就是完整房间 ID，可直接拿去查电量 */
    @Test
    fun `房间层响应 end 为 true 且节点 id 是完整房间号`() {
        val raw = """
            {"success":true,"message":"成功","resultData":{"locationType":"room",
            "locationList":[{"id":"106-3--215-305","name":"305房间"}],
            "nextLocationType":null,"end":true},"code":""}
        """.trimIndent()
        val level = json.decodeFromString<EcardClient.LocationEnvelope>(raw).resultData!!.toLevel("room")

        assertTrue("这一层应该就是房间", level.end)
        assertEquals("room", level.locationType)
        assertEquals(null, level.nextLocationType)
        // 查电量要的就是这个形状（area-building--level-room）
        assertEquals("106-3--215-305", level.nodes.single().id)
        val parsed = EcardClient.parseRoomId(level.nodes.single().id)
        assertEquals("106", parsed.area)
        assertEquals("3", parsed.building)
        assertEquals("215", parsed.level)
    }

    @Test
    fun `层级名 subarea 统一成 subArea`() {
        val raw = """{"success":true,"resultData":{"locationType":"subarea","locationList":[],"end":false}}"""
        val level = json.decodeFromString<EcardClient.LocationEnvelope>(raw).resultData!!.toLevel("x")
        assertEquals("subArea", level.locationType)
        // locationType 缺失时退回调用方问的那一层
        val empty = json.decodeFromString<EcardClient.LocationEnvelope>("""{"success":true,"resultData":{}}""")
        assertEquals("building", empty.resultData!!.toLevel("building").locationType)
    }

    @Test
    fun `绑定房间的显示名去掉层级名，空调房间按 levelName 识别`() {
        // 例子统一用**柳园20号楼 101**（`99-16--31-101` / `99-16--32-8101`）——
        // 公开仓库里不要拿真实寝室当样例（那等于把作者的房间号写进去）
        val light = EcardClient.EcardLocation(
            area = "99",
            areaName = "主校区",
            building = "16",
            buildingName = "柳园20号楼",
            level = "31",
            levelName = "照明房间",
            room = "99-16--31-101",
            roomName = "101房间",
        )
        assertEquals("主校区 柳园20号楼 101房间", light.shortName)
        assertEquals(false, light.isAcMeter)

        val ac = light.copy(levelName = "空调房间", room = "99-16--32-8101")
        assertEquals("主校区 柳园20号楼 101房间", ac.shortName)
        assertEquals(true, ac.isAcMeter)

        // 楼层式分类（亿达/洛阳那种服务端不给 roomName）：退回内部房间 ID，别丢房间身份
        val floor = light.copy(levelName = "5层", roomName = "")
        assertEquals("主校区 柳园20号楼 99-16--31-101", floor.shortName)
        assertEquals(false, floor.isAcMeter)
    }
}
