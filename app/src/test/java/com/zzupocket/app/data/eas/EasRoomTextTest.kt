package com.zzupocket.app.data.eas

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 教室文本拼接规则（issue #6 楼名重复的源头修复）。
 *
 * 教务的教室名常自带楼栋前缀（`物理馆X101`、`中3302`），以前无条件拼楼栋名
 * 就重复两遍；现在教室名已含楼栋名时不再拼。这里钉住各形态。
 */
class EasRoomTextTest {

    private val client = EasClient(okhttp3.OkHttpClient(), Json { ignoreUnknownKeys = true })

    private fun room(building: String?, name: String?, custom: String? = null) =
        EasClient.ScheduleDto(
            room = EasClient.RoomDto(
                building = EasClient.NamedDto(nameZh = building),
                nameZh = name,
            ),
            customPlace = custom,
        )

    @Test
    fun `教室名自带楼栋前缀时剥掉不重复`() {
        assertEquals("物理馆 X101", client.roomTextOf(room("物理馆", "物理馆X101")))
        // 中3 楼里的 302：教室名"中3302"= 楼名"中3"+房号"302"
        assertEquals("中3 302", client.roomTextOf(room("中3", "中3302")))
    }

    @Test
    fun `教室名不含楼栋名时正常拼`() {
        assertEquals("南2 206", client.roomTextOf(room("南2", "南2_206")))
        assertEquals("南1 206", client.roomTextOf(room("南1", "206")))
    }

    @Test
    fun `只有教室名或只有自定义地点`() {
        assertEquals("北1301", client.roomTextOf(room(null, "北1301")))
        assertEquals("信息工程学院实验室", client.roomTextOf(room(null, null, custom = "信息工程学院实验室")))
        assertNull(client.roomTextOf(room(null, null)))
    }
}
