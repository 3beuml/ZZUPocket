package com.zzupocket.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录输入体检的回归测试。
 *
 * 这些用例钉住的是**真实踩过的坑**：中文输入法全角状态下打出的数字/字母，
 * 以及超过 RSA 单块上限的长输入 —— 两者都必须被挡住并给出可操作的解释。
 */
class LoginInputTest {

    @Test
    fun `正常半角密码通过`() {
        assertNull(LoginInput.inspect("Abc123!@#"))
        assertNull(LoginInput.inspect("zzu2026"))
        assertNull(LoginInput.inspect(""))
    }

    @Test
    fun `全角数字被判定为全角问题`() {
        // ２０２６ 是全角数字，屏幕上看和 2026 差不多
        val issue = LoginInput.inspect("２０２６abcd")
        assertTrue(issue is LoginInput.Issue.FullWidth)
        assertTrue("全角问题应当可以一键修好", issue!!.fixableByWidening)
    }

    @Test
    fun `全角字母与全角空格也算全角`() {
        assertTrue(LoginInput.inspect("ａｂｃ") is LoginInput.Issue.FullWidth)
        assertTrue(LoginInput.inspect("abc　def") is LoginInput.Issue.FullWidth)
    }

    @Test
    fun `中文被判定为非 ASCII 且不能靠转半角修好`() {
        val issue = LoginInput.inspect("密码abc")
        assertTrue(issue is LoginInput.Issue.NonAscii)
        assertFalse(issue!!.fixableByWidening)
    }

    @Test
    fun `超过 RSA 单块上限时报过长`() {
        val issue = LoginInput.inspect("a".repeat(LoginInput.MAX_RSA_BYTES + 1))
        assertTrue(issue is LoginInput.Issue.TooLong)
        assertEquals(LoginInput.MAX_RSA_BYTES + 1, (issue as LoginInput.Issue.TooLong).bytes)
    }

    @Test
    fun `刚好处在上限内不报错`() {
        assertNull(LoginInput.inspect("a".repeat(LoginInput.MAX_RSA_BYTES)))
    }

    @Test
    fun `转半角只动全角字符中文保持原样`() {
        assertEquals("2026abc", LoginInput.toHalfWidth("２０２６ａｂｃ"))
        assertEquals("a b", LoginInput.toHalfWidth("a　b"))            // 全角空格 → 半角空格
        assertEquals("密码2026", LoginInput.toHalfWidth("密码２０２６")) // 中文不动
        assertEquals("Abc123!@#", LoginInput.toHalfWidth("Abc123!@#"))  // 半角原样
    }

    @Test
    fun `转半角后能通过体检`() {
        val fullWidth = "２０２６ａｂｃ"
        assertTrue(LoginInput.inspect(fullWidth) is LoginInput.Issue.FullWidth)
        assertNull(LoginInput.inspect(LoginInput.toHalfWidth(fullWidth)))
    }

    @Test
    fun `短信频繁的错误被翻译成人话`() {
        assertEquals(
            "发送太频繁了，请等 1 分钟后再点「重新发送」",
            LoginInput.friendlySmsError("发送验证码过于频繁，请稍后再试"),
        )
        assertEquals(
            "发送太频繁了，请等 1 分钟后再点「重新发送」",
            LoginInput.friendlySmsError("Too many requests"),
        )
        // 无关的错误不要乱翻译
        assertNull(LoginInput.friendlySmsError("验证码错误或已过期"))
    }

    // ── 重发验证码的冷却倒计时 ───────────────────────────────────────────
    // 这一组钉的是"用户退出登录页再进来"这个真实动作：学校的重发限流按账号算，
    // 不会因为我们重建了界面就重置，倒计时必须接着算。

    private val t0 = 1_700_000_000_000L

    @Test
    fun `刚发出去时剩余满额`() {
        assertEquals(60, LoginInput.cooldownLeftSec(t0, t0, 60))
    }

    @Test
    fun `过了多久就少多少秒`() {
        assertEquals(45, LoginInput.cooldownLeftSec(t0, t0 + 15_000, 60))
        assertEquals(1, LoginInput.cooldownLeftSec(t0, t0 + 59_000, 60))
        // 不足一秒的零头不显示成一行 0
        assertEquals(60, LoginInput.cooldownLeftSec(t0, t0 + 400, 60))
    }

    @Test
    fun `冷却走完后是 0 而不是负数`() {
        assertEquals(0, LoginInput.cooldownLeftSec(t0, t0 + 60_000, 60))
        assertEquals(0, LoginInput.cooldownLeftSec(t0, t0 + 30 * 60_000, 60))
    }

    @Test
    fun `从没发过时没有冷却`() {
        assertEquals(0, LoginInput.cooldownLeftSec(null, t0, 60))
    }

    @Test
    fun `时钟倒退也不会算出超长或负数`() {
        // 用户改过系统时间 / 自动对时往回跳：剩余时间要夹在 0..冷却时长之间
        assertEquals(60, LoginInput.cooldownLeftSec(t0, t0 - 5 * 60_000, 60))
    }
}
