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
    fun `内置房间表能解析且覆盖六个校区`() {
        val data = loadRealAsset()
        assertEquals(
            listOf("主校区", "北校区", "东校区", "南校区", "护理学院", "洛阳校区"),
            data.campuses.map { it.name },
        )
        // 默认校区排第一，且 area 码就是校区别
        assertEquals(RoomCatalog.MAIN_CAMPUS_CODE, data.campuses.first().code)

        val main = data.campuses.first()
        assertTrue("主校区楼栋太少：${main.buildings.size}", main.buildings.size >= 60)
        val totalRooms = main.buildings.sumOf { it.roomCount }
        assertTrue("主校区房号总数太少：$totalRooms", totalRooms >= 9000)

        // 每栋楼都要能算出至少一套表计 ID
        data.buildings.forEach { b ->
            assertTrue("${b.name} 既没有照明也没有空调表", b.lightUnit > 0 || b.acUnit > 0)
        }
    }

    /**
     * 分类名不能按白名单取：主校区这两栋楼的分类名不是"照明房间/空调房间"，
     * 早期版本的资产生成器把柳园17号楼整栋丢了、松园12号楼的照明表也丢了。
     */
    @Test
    fun `分类名不标准的楼栋也要在表里`() {
        val main = loadRealAsset().campuses.first()

        // 柳园17号楼（留学生）：分类叫"房间用电"，整间一表，没有空调那一路
        val liu17 = main.buildings.first { it.name.startsWith("柳园17") }
        assertTrue("柳园17号楼应该有房间（唯一一路）", liu17.light.size >= 100)
        assertEquals(0, liu17.ac.size)
        assertEquals("99-104--214-207", liu17.lightRoomId("207"))

        // 松园12号楼：分类叫"照明用电"，unit 227
        val song12 = main.buildings.first { it.name.startsWith("松园12") }
        assertTrue("松园12号楼应该有照明表", song12.lightUnit > 0 && song12.light.size >= 200)
        assertTrue("松园12号楼应该有空调表", song12.ac.size >= 200)
    }

    /** 洛阳校区是按楼层分的，一个 unit 一条记录（否则一层都选不出来） */
    @Test
    fun `按楼层分类的校区拆成一层一条`() {
        val luoyang = loadRealAsset().campuses.first { it.name == "洛阳校区" }
        assertTrue("洛阳校区应该有楼层条目：${luoyang.buildings.map { it.name }}", luoyang.buildings.size >= 2)
        assertTrue(
            "洛阳校区的条目名要带楼层：${luoyang.buildings.map { it.name }}",
            luoyang.buildings.any { it.name.contains("层") },
        )
        luoyang.buildings.forEach { b ->
            assertTrue("${b.name} 的 unit 没取到", b.lightUnit > 0)
            assertTrue("${b.name} 没有房号", b.light.isNotEmpty())
        }
    }

    /** 反过来：别的校区的房间 ID 也要能定出校区，而不是只在主校区里找 */
    @Test
    fun `跨校区反查能定出校区`() {
        val beiqu = loadRealAsset().locate("101-39--75-101")
        assertTrue("没查到北校区那间房", beiqu != null)
        assertEquals("北校区", beiqu!!.campus.name)
        assertEquals("北区1号楼", beiqu.building.name)
        assertEquals("101", beiqu.room)
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
        // 别的校区的名字不缩写 —— 硬套"首字+数字"会缩成 12 / 主1 这种认不出来的东西
        assertEquals("1号宿舍楼 2层", RoomBuilding("1号宿舍楼 2层", 105, 105, 216, 0).shortName)
        assertEquals("主校区护理1#女生", RoomBuilding("主校区护理1#女生", 104, 100, 207, 0).shortName)
        assertEquals("北区1号楼", RoomBuilding("北区1号楼", 101, 39, 75, 77).shortName)
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
        assertEquals("主校区", hit!!.campus.name)
        assertEquals("柳园20号楼", hit.building.name)
        assertEquals("101", hit.room)
    }

    @Test
    fun `用空调 ID 也能反查出同一个寝室号`() {
        val hit = loadRealAsset().locate("99-16--32-8101")
        assertTrue("没查到", hit != null)
        assertEquals("柳园20号楼", hit!!.building.name)
        // 空调表里是 8101，但界面上要显示学生认的 101
        assertEquals("101", hit.room)
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
