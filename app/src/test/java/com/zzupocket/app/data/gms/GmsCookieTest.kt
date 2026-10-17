package com.zzupocket.app.data.gms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话 Cookie 处理的单测。
 *
 * 这段逻辑是"研究生课表能不能用"的入口：WebView 登录后我们只能拿到一个 `k=v; k2=v2`
 * 字符串，解析错了就等于没有会话（接口会被 302 回 CAS）。所以宁可多测几条边界。
 */
class GmsCookieTest {

    @Test
    fun `标准 Cookie 头能解析成键值`() {
        val m = GmsCookie.parse("JSESSIONID=ABC123; route=1a2b; _csrf=xyz")
        assertEquals(3, m.size)
        assertEquals("ABC123", m["JSESSIONID"])
        assertEquals("1a2b", m["route"])
        assertEquals("xyz", m["_csrf"])
    }

    @Test
    fun `值里含等号时只按第一个等号切`() {
        val m = GmsCookie.parse("token=abc=def==")
        assertEquals("abc=def==", m["token"])
    }

    @Test
    fun `空串与脏数据不炸、返回空表`() {
        assertTrue(GmsCookie.parse(null).isEmpty())
        assertTrue(GmsCookie.parse("").isEmpty())
        assertTrue(GmsCookie.parse("   ").isEmpty())
        assertTrue(GmsCookie.parse("; ; ;;").isEmpty())
        // 没有等号的项直接丢掉，不能当成 cookie
        assertTrue(GmsCookie.parse("novalue").isEmpty())
        assertTrue(GmsCookie.parse("=onlyvalue").isEmpty())
    }

    @Test
    fun `往返一致`() {
        val m = mapOf("a" to "1", "b" to "2")
        assertEquals(m, GmsCookie.parse(GmsCookie.join(m)))
    }

    @Test
    fun `注入后可被请求取到`() {
        val jar = GmsCookieJar()
        assertEquals(0, jar.size())
        jar.seed("gmsstu.zzu.edu.cn", mapOf("JSESSIONID" to "x"))
        assertEquals(1, jar.size())
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("gmsstu.zzu.edu.cn").build()
        assertEquals("x", jar.loadForRequest(url).firstOrNull()?.value)
        jar.clear()
        assertEquals(0, jar.size())
    }
}
