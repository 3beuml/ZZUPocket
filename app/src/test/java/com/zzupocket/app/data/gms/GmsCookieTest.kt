package com.zzupocket.app.data.gms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `会话 Cookie 的值就是 guid`() {
        val guid = "01234567-89ab-cdef-0123-456789abcdef"
        val m = GmsCookie.parse("cn_com_southsoft_gmis_stu=$guid; route=1a2b")
        assertEquals(guid, GmsCookie.guidOf(m))
    }

    @Test
    fun `会话值不是 UUID 时不当作 guid`() {
        assertNull(GmsCookie.guidOf(GmsCookie.parse("cn_com_southsoft_gmis_stu=not-a-uuid")))
        assertNull(GmsCookie.guidOf(emptyMap()))
    }

    @Test
    fun `响应只带新 cookie 时不会丢掉已注入的会话`() {
        val jar = GmsCookieJar()
        jar.seed("gmsstu.zzu.edu.cn", mapOf("cn_com_southsoft_gmis_stu" to "guid-value"))
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("gmsstu.zzu.edu.cn").build()
        val route = okhttp3.Cookie.Builder()
            .domain("gmsstu.zzu.edu.cn")
            .path("/")
            .name("route")
            .value("1a2b")
            .build()
        jar.saveFromResponse(url, listOf(route))
        val names = jar.loadForRequest(url).map { it.name }.toSet()
        assertTrue("cn_com_southsoft_gmis_stu" in names)
        assertTrue("route" in names)
    }
}
