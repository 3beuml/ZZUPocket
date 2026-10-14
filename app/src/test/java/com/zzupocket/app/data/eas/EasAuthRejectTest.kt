package com.zzupocket.app.data.eas

import com.zzupocket.app.core.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 401 与 403 必须是两句不同的话。
 *
 * 401 = 网关不认这个令牌 → 重新登录有意义；
 * 403 = 令牌被识别但无权限 → 重新登录**没用**，是账号/院系权限问题。
 *
 * 2026-10-14 有用户报告"一卡通正常、唯独课表报教务登录态已失效，重新登录仍复现"，
 * 当时这两种状态被合并成同一句文案，用户只能被引导去反复重新登录。这两个断言就是防止回退。
 */
class EasAuthRejectTest {

    @Test
    fun `401 要点明状态码并引导重新登录`() {
        val err = EasClient.authRejectOf(401, "课表")
        assertTrue("401 应映射为需要重新登录", err is AppError.ReLoginRequired)
        val msg = err!!.message.orEmpty()
        assertTrue("文案要点明 HTTP 401：$msg", msg.contains("401"))
        assertTrue("文案要带上出问题的接口：$msg", msg.contains("课表"))
        assertTrue("401 要引导重新登录：$msg", msg.contains("请重新登录"))
    }

    @Test
    fun `403 不能说成登录失效、也不能引导重登`() {
        val err = EasClient.authRejectOf(403, "课表")
        assertEquals("403 应保留状态码", 403, (err as AppError.Server).code)
        val msg = err.message.orEmpty()
        assertTrue("文案要点明 HTTP 403：$msg", msg.contains("403"))
        assertTrue("403 要说明重登无用：$msg", msg.contains("重新登录无用"))
        assertFalse("403 不该引导用户去重新登录：$msg", msg.contains("请重新登录"))
        assertFalse("403 不是登录态问题：$msg", msg.contains("未通过鉴权"))
    }

    @Test
    fun `其他状态码不当作鉴权失败处理`() {
        assertNull(EasClient.authRejectOf(200, "课表"))
        assertNull(EasClient.authRejectOf(302, "课表"))
        assertNull(EasClient.authRejectOf(404, "课表"))
        assertNull(EasClient.authRejectOf(500, "课表"))
    }
}
