package com.zzupocket.app.data.room

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
     * 各校区的空调号规律都是**生成资产时用两侧真实房号算好的**，运行时只套用。
     * 这条把四个校区的代表楼钉住 —— 以前只认"8+房号"，别的校区全对不上，用户只能手抄。
     */
    @Test
    fun `各校区的空调号都能算出来而不是靠猜`() {
        val data = loadRealAsset()
        fun building(campus: String, name: String) =
            data.campuses.first { it.name == campus }.buildings.first { it.name == name }

        // 主校区：8 + 房号
        assertEquals(listOf("8212"), building("主校区", "松园15号楼").acRoomNumbersFor("212"))
        // 北校区：+ 楼号×1000
        assertEquals(listOf("1101"), building("北校区", "北区1号楼").acRoomNumbersFor("101"))
        // 南校区：+ 楼号×1000（7号楼 → +7000）
        assertEquals(listOf("7101"), building("南校区", "南区7号楼").acRoomNumbersFor("101"))
        // 东校区：负偏移（9号楼：照明 9201 → 空调 201）
        assertEquals(listOf("201"), building("东校区", "东区9号楼").acRoomNumbersFor("9201"))
        // 护理学院：8 + 房号
        assertEquals(listOf("8101"), building("护理学院", "主校区护理1#女生").acRoomNumbersFor("101"))
    }

    /** 荷园式：一间房多个空调户 → 必须返回**全部**，界面才能让用户挑自己的那个 */
    @Test
    fun `一间房多个空调户时返回全部户号`() {
        val he3 = loadRealAsset().buildings.first { it.name == "荷园3号楼" }
        assertEquals(listOf("3021", "3022", "3023"), he3.acRoomNumbersFor("302"))
        assertEquals(3, he3.acPerRoom)
        assertEquals(
            listOf("99-88--194-3021", "99-88--194-3022", "99-88--194-3023"),
            he3.acRoomIds("302"),
        )
    }

    /**
     * **不许凭空造房号**：每个照明房号算出来的空调号都必须真的在那栋楼的空调表里。
     * 老实现用 `endsWith` 猜，会把 1101 当成 101 室的空调号 —— 那是别人家的表。
     */
    @Test
    fun `算出来的空调号必须真实存在于该楼空调表`() {
        var checked = 0
        var withAc = 0
        loadRealAsset().buildings.forEach { b ->
            b.light.forEach { room ->
                checked++
                val got = b.acRoomNumbersFor(room)
                got.forEach { n ->
                    assertTrue("${b.name} $room → $n 不在该楼空调表里", b.ac.contains(n))
                }
                if (got.isNotEmpty()) withAc++
            }
        }
        assertTrue("房间太少：$checked", checked > 10000)
        assertTrue("能算出空调号的房间太少：$withAc/$checked", withAc > checked * 0.85)
    }

    /** 没有空调表的楼栋如实返回空，不硬凑一个 */
    @Test
    fun `没有空调表的楼栋返回空`() {
        val luoyang = loadRealAsset().campuses
            .first { it.name == "洛阳校区" }.buildings.first()
        assertTrue(luoyang.acRoomNumbersFor(luoyang.light.first()).isEmpty())
        assertTrue(!luoyang.acRuleIsExact())
    }

    /**
     * 单表计（一个「电量」卡）**只给亿达**（area 106）。
     *
     * 用户 2026-10-11 明确：只能确定亿达是整间一个表 —— 洛阳校区、柳园17号楼虽然资产里
     * 也只有一路表，界面仍照旧按双表计显示（空调那格写"未设置房间"），不要顺手套上单表计。
     */
    @Test
    fun `只有亿达走单表计`() {
        assertTrue(isSingleMeterRoom("106-1--215-305"))
        assertFalse(isSingleMeterRoom("99-16--31-101"))
        assertFalse(isSingleMeterRoom("101-39--75-101"))
        assertFalse(isSingleMeterRoom("105-105--215-12001"))
        assertFalse(isSingleMeterRoom("104-100--207-101"))
        // 没设置 / 格式不对：都不是单表计
        assertFalse(isSingleMeterRoom(""))
        assertFalse(isSingleMeterRoom(null))
        assertFalse(isSingleMeterRoom("abc"))
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
