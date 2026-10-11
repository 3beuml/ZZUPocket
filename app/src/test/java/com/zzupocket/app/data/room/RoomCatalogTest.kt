package com.zzupocket.app.data.room

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 房间表与 ID 组装的回归测试。
 *
 * 这里**直接读真实的 assets/rooms.json**（Gradle 单测的工作目录就是模块目录），
 * 所以房间表被改坏、或者格式变了，会在这里立刻暴露。
 *
 * 例子统一用**柳园20号楼 101**（`99-16--31-101` / `99-16--32-8101`）：
 * 别拿某个真实寝室当样例 —— 那等于把作者本人的房间号写进公开仓库。
 */
class RoomCatalogTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadRealAsset(): RoomCatalogData {
        val file = File("src/main/assets/rooms.json")
        assertTrue("找不到内置房间表：${file.absolutePath}", file.exists())
        return json.decodeFromString(file.readText())
    }

    @Test
    fun `内置房间表能解析且覆盖主校区各宿舍楼`() {
        val data = loadRealAsset()
        assertEquals("主校区", data.campus)
        assertTrue("楼栋太少：${data.buildings.size}", data.buildings.size >= 50)

        val totalRooms = data.buildings.sumOf { it.roomCount }
        assertTrue("房号总数太少：$totalRooms", totalRooms >= 9000)

        // 每栋楼都要能算出至少一套表计 ID
        data.buildings.forEach { b ->
            assertTrue("${b.name} 既没有照明也没有空调表", b.lightUnit > 0 || b.acUnit > 0)
        }
    }

    @Test
    fun `柳园20号楼 101 能带出照明与空调两套表计 ID`() {
        val building = loadRealAsset().buildings.first { it.name == "柳园20号楼" }
        assertEquals("99-16--31-101", building.lightRoomId("101"))
        assertEquals("99-16--32-8101", building.acRoomId("101"))
    }

    @Test
    fun `空调房号优先取照明房号前加 8`() {
        val b = RoomBuilding(
            name = "测试楼",
            area = 99,
            building = 16,
            lightUnit = 31,
            acUnit = 32,
            light = listOf("101"),
            ac = listOf("8101", "1101"),
        )
        assertEquals("8101", b.acRoomNumberFor("101"))
    }

    @Test
    fun `空调表里没有对应房号时返回 null`() {
        val b = RoomBuilding(
            name = "测试楼",
            area = 99,
            building = 16,
            lightUnit = 31,
            acUnit = 32,
            light = listOf("101"),
            ac = listOf("9999"),
        )
        assertNull(b.acRoomNumberFor("101"))
        assertNull(b.acRoomId("101"))
    }

    /**
     * 核对提示的判据：空调号是不是"照明号前加 8"对上的。
     *
     * ⚠️ 这里**只断言"可靠/不可靠"这个判断**，不断言具体取到哪个房号 ——
     * 因为那些楼的真实对应关系还没定论（见 README 里"房间号规律因楼而异"）。
     */
    @Test
    fun `常见规律的楼栋算可靠，靠后缀猜的算不可靠`() {
        // 柳园20号楼：空调表里确实有 8101，对得上
        val liu20 = loadRealAsset().buildings.first { it.name == "柳园20号楼" }
        assertTrue("柳园20号楼 101 应该按常见规律对上", liu20.acGuessIsReliable("101"))

        // 荷园3号楼：空调表是"房号 + 一位住户序号"（101 → 1011/1012/1013），101 对不上 8+101
        val he3 = loadRealAsset().buildings.first { it.name == "荷园3号楼" }
        assertTrue("荷园3号楼应该有空调表", he3.ac.size > 0)
        assertTrue("荷园3号楼 101 不该算常见规律", !he3.acGuessIsReliable("101"))

        // 构造：空调表里既没有 8101、也没有任何以 101 结尾的房号
        val b = RoomBuilding(
            name = "测试楼",
            area = 99,
            building = 16,
            lightUnit = 31,
            acUnit = 32,
            light = listOf("101"),
            ac = listOf("1101"),
        )
        assertTrue(!b.acGuessIsReliable("101"))
    }

    @Test
    fun `没有照明表时照明 ID 为 null 但空调照旧`() {
        val b = RoomBuilding(
            name = "只有空调的楼",
            area = 99,
            building = 87,
            lightUnit = 0,
            acUnit = 192,
            light = emptyList(),
            ac = listOf("8101"),
        )
        assertNull(b.lightRoomId("101"))
        assertEquals("99-87--192-8101", b.acRoomId("101"))
        // 房号列表此时退回空调表的房号，保证界面仍有东西可选
        assertEquals(listOf("8101"), b.roomNumbers)
    }

    @Test
    fun `所有房间 ID 都符合 area-building--unit-room 格式`() {
        val pattern = Regex("""^\d+-\d+--\d+-.+$""")
        loadRealAsset().buildings.forEach { b ->
            b.light.forEach { room ->
                val id = b.lightRoomId(room)
                assertTrue("$id 格式不对", id != null && pattern.matches(id))
            }
        }
    }

    @Test
    fun `楼栋简称：柳园20号楼 到 柳20`() {
        assertEquals("柳20", RoomBuilding("柳园20号楼", 99, 16, 31, 32).shortName)
        assertEquals("柳1", RoomBuilding("柳园1号楼", 99, 1, 1, 2).shortName)
        // 名字里没有数字时用原名，不要瞎截
        assertEquals("荷园", RoomBuilding("荷园", 99, 99, 181, 182).shortName)
    }

    @Test
    fun `房间显示名是紧凑形式 柳20-101`() {
        val building = loadRealAsset().buildings.first { it.name == "柳园20号楼" }
        assertEquals("柳20-101", building.shortLabel("101"))
    }

    @Test
    fun `能用照明 ID 反查出楼栋与寝室号`() {
        val hit = loadRealAsset().locate("99-16--31-101")
        assertTrue("没查到", hit != null)
        assertEquals("柳园20号楼", hit!!.first.name)
        assertEquals("101", hit.second)
    }

    @Test
    fun `用空调 ID 也能反查出同一个寝室号`() {
        val hit = loadRealAsset().locate("99-16--32-8101")
        assertTrue("没查到", hit != null)
        assertEquals("柳园20号楼", hit!!.first.name)
        // 空调表里是 8101，但界面上要显示学生认的 101
        assertEquals("101", hit.second)
    }

    @Test
    fun `无法解析或不存在的 ID 返回 null`() {
        val data = loadRealAsset()
        assertNull(decodeRoomId("abc"))
        assertNull(decodeRoomId(null))
        assertNull(data.locate("abc"))
        assertNull(data.locate("1-2--3-4"))
    }
}
