package com.zzupocket.app.data.ecard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 校园卡余额的解析。
 *
 * 这个接口踩过坑：响应 `data` 数组里**第一个带 amount 的条目是 0**（占位/另一张卡），
 * 直接"找第一个 amount"会得到 0 —— 用户看到的就是"余额显示 0"。
 */
class CardBalanceParseTest {

    private val client = EcardClient(
        okhttp3.OkHttpClient(),
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true },
    )

    @Test
    fun `取下标 1 的金额 而不是第一个带 amount 的`() {
        val json = """
            {"code":"0","message":"成功","data":[
              {"cardNo":"0000","amount":"0"},
              {"cardNo":"1234","amount":"286.35"}
            ]}
        """.trimIndent()
        assertEquals(286.35, client.parseCardBalance(json), 0.001)
    }

    @Test
    fun `只有一条时用那一条`() {
        val json = """{"data":[{"amount":"12.50"}]}"""
        assertEquals(12.50, client.parseCardBalance(json), 0.001)
    }

    @Test
    fun `amount 是数字而不是字符串也能解析`() {
        val json = """{"data":[{"amount":0},{"amount":99.9}]}"""
        assertEquals(99.9, client.parseCardBalance(json), 0.001)
    }

    @Test
    fun `下标 1 没有金额时退回最后一个有金额的`() {
        val json = """{"data":[{"amount":"1.00"},{"cardNo":"x"},{"amount":"55.55"}]}"""
        assertEquals(55.55, client.parseCardBalance(json), 0.001)
    }

    @Test(expected = com.zzupocket.app.core.AppError.Parse::class)
    fun `没有 data 字段时报解析错而不是静默返回 0`() {
        client.parseCardBalance("""{"code":"0","message":"成功"}""")
    }

    @Test
    fun `欠费时的负数余额照常返回`() {
        val json = """{"data":[{"amount":"0"},{"amount":"-3.20"}]}"""
        assertEquals(-3.20, client.parseCardBalance(json), 0.001)
    }

    @Test(expected = com.zzupocket.app.core.AppError.Parse::class)
    fun `不是 JSON 时报解析错`() {
        client.parseCardBalance("<html>502 Bad Gateway</html>")
    }
}
