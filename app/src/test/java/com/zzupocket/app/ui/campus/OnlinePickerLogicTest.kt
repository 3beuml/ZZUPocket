package com.zzupocket.app.ui.campus

import com.zzupocket.app.data.ecard.EcardClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线房间树「往下走一层」的纯逻辑回归测试。
 *
 * 这里的坑是真机上踩出来的：**要填的是当前这一层的字段，要问的是服务端的下一层**
 * （`currentType` vs `nextType`）。当初写成"用 currentType 去问"，结果点「亿达学生公寓」
 * 之后列表原封不动（又在问 area），表现为"点了没反应"。
 */
class OnlinePickerLogicTest {

    private fun ui(
        currentType: String,
        nextType: String?,
        leaf: Boolean = false,
        scope: EcardClient.LocationScope = EcardClient.LocationScope(),
        steps: List<LocationStep> = emptyList(),
    ) = OnlinePickerUi(
        currentType = currentType,
        nextType = nextType,
        leaf = leaf,
        scope = scope,
        steps = steps,
    )

    private val yida = EcardClient.LocationNode(id = "106", name = "亿达学生公寓")

    /** 区域层 → 选亿达：要问 building，且 area 要填成 106（不是再问一遍 area） */
    @Test
    fun `区域层选一个之后问下一层，并把 id 填进当前层的字段`() {
        val request = ui(currentType = "area", nextType = "building").descend(yida)
        assertTrue("应该能往下走", request != null)
        assertEquals("building", request!!.requestType)
        assertEquals("106", request.scope.area)
        assertEquals("", request.scope.building)
        assertEquals(listOf(LocationStep("area", "106", "亿达学生公寓")), request.steps)
    }

    /** 楼栋层继续往下：building 填进去，问 level（楼栋 id 是 106 下的编号） */
    @Test
    fun `楼栋层继续往下时楼栋 id 进 building 字段`() {
        val atBuilding = ui(
            currentType = "building",
            nextType = "level",
            scope = EcardClient.LocationScope(area = "106"),
            steps = listOf(LocationStep("area", "106", "亿达学生公寓")),
        )
        val request = atBuilding.descend(EcardClient.LocationNode(id = "3", name = "3号楼"))
        assertEquals("level", request!!.requestType)
        assertEquals("106", request.scope.area)
        assertEquals("3", request.scope.building)
        assertEquals(2, request.steps.size)
    }

    /** 叶子层（end=true）不能再往下走 —— 这时候点一下是"选中"，不是"下一层" */
    @Test
    fun `房间层不再往下走`() {
        val atRoom = ui(currentType = "room", nextType = null, leaf = true)
        assertNull(atRoom.descend(EcardClient.LocationNode(id = "106-3--215-305", name = "305房间")))
    }

    /** 服务端没给 nextLocationType 时不动（宁可停在原地，也不要瞎问一层） */
    @Test
    fun `没有下一层时不发请求`() {
        assertNull(ui(currentType = "area", nextType = null).descend(yida))
    }

    /** currentType 为空（服务端没回 locationType）时按照 area 处理，别把 id 丢了 */
    @Test
    fun `currentType 为空时按最外层处理`() {
        val request = ui(currentType = "", nextType = "building").descend(yida)
        assertEquals("106", request!!.scope.area)
        assertEquals(OnlinePickerUi.ROOT_LEVEL, request.steps.single().type)
    }
}
