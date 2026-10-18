package com.zzupocket.app.data.gms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 探针报告脱敏的回归测试。
 *
 * 背景：这份报告要贴到公开 issue 上。2026-10-18 报告者指出两处真泄漏并各自复现过 ——
 * 表单形态的请求体（明文用户名/密码）与路径里的会话 guid。这里把两类都钉成断言。
 */
class GmsRedactTest {

    private val appKey = "MyHeartWillGoOn"
    private val guid = "3f2b9c1e-4d5a-4b6c-8e7f-0a1b2c3d4e5f"

    /** 与 `GmsScheduleParser.encodeRoute` 保持一致的编码方式 */
    private fun encodeRoute(modulePath: String): String {
        val raw = "$appKey$modulePath#$guid"
        return Base64.getUrlEncoder().encodeToString(raw.toByteArray())
            .replace('=', '_')
    }

    @Test
    fun `表单请求体按字段名打码_非敏感参数保留`() {
        val body = "username=2020123456&password=Secret1!&identityType=1&kblx=xs&termcode=20261"
        val masked = GmsRedact.body(body)
        assertFalse("明文密码不能出现", masked.contains("Secret1!"))
        assertFalse("用户名不能出现", masked.contains("2020123456"))
        assertTrue("账号字段名要打码", masked.contains("username=***"))
        assertTrue("密码字段名要打码", masked.contains("password=***"))
        assertTrue("非敏感参数保留原值，便于诊断", masked.contains("kblx=xs"))
        assertTrue("学期号要保留", masked.contains("termcode=20261"))
    }

    @Test
    fun `JSON 请求体按字段名打码`() {
        val body = """{"password":"Secret1!","token":"abcdef","userToken":"tok-123","kblx":"xs"}"""
        val masked = GmsRedact.body(body)
        assertFalse(masked.contains("Secret1!"))
        assertFalse(masked.contains("tok-123"))
        assertTrue(masked.contains("kblx"))
        assertTrue(masked.contains("xs"))
    }

    @Test
    fun `长数字与长令牌串兜底盖掉`() {
        val masked = GmsRedact.body("note=2020123456789 tail=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        assertFalse(masked.contains("2020123456789"))
        assertFalse(masked.contains("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
    }

    @Test
    fun `路径里的路由段保留模块路径_guid 打成星号`() {
        val path = GmsRedact.path("https://gmsstu.zzu.edu.cn/stu/" + encodeRoute("pygl/xskbcx/loadKb"))
        assertFalse("会话 guid 不能出现", path.contains(guid))
        assertFalse("base64 原文不能出现", path.contains(encodeRoute("pygl/xskbcx/loadKb")))
        assertTrue("模块路径要保留，否则报告失去诊断价值", path.contains("pygl/xskbcx/loadKb"))
        assertTrue(path.startsWith("/stu/"))
    }

    @Test
    fun `完整 URL 同时打码查询串与路径`() {
        val url = "https://gmsstu.zzu.edu.cn/stu/" + encodeRoute("pygl/xskbcx/loadKb") + "?termcode=20261&ticket=ST-123"
        val masked = GmsRedact.url(url)
        assertFalse(masked.contains(guid))
        assertFalse(masked.contains("ST-123"))
        assertFalse(masked.contains("20261"))
        assertTrue(masked.contains("termcode=*"))
        assertTrue(masked.contains("ticket=*"))
        assertTrue(masked.contains("loadKb"))
    }

    @Test
    fun `普通路径原样保留`() {
        assertEquals("/stu/index", GmsRedact.path("https://gmsstu.zzu.edu.cn/stu/index"))
        assertEquals("/cas/a/login", GmsRedact.path("/cas/a/login?service=x"))
    }

    @Test
    fun `不像路由的长段整段盖掉`() {
        val masked = GmsRedact.path("https://gmsstu.zzu.edu.cn/stu/abcdefghijklmnopqrstuvwxyz012345")
        assertFalse(masked.contains("abcdefghijklmnopqrstuvwxyz012345"))
    }
}
