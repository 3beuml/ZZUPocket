package com.zzupocket.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检测更新的纯逻辑回归：版本比较 + 红点判定。
 * 定义都在 AppUpdate（纯 Kotlin），这里钉的是对外承诺的行为。
 */
class AppUpdateTest {

    // ── compareVersions：语义比较，前缀 v 自动剥，短段补 0 ─────────────────

    @Test
    fun `比较_新版大于当前`() {
        assertEquals(1, AppUpdate.compareVersions("0.33.0", "0.32.5"))
        assertEquals(-1, AppUpdate.compareVersions("0.32.5", "0.33.0"))
        // 本地 versionName 没有v 前缀，tag常见的 v 前缀也能比
        assertEquals(1, AppUpdate.compareVersions("v0.33.0", "0.32.5"))
        assertEquals(1, AppUpdate.compareVersions("V0.33.0", "0.32.5"))
    }

    @Test
    fun `比较_相等与段数不齐`() {
        assertEquals(0, AppUpdate.compareVersions("0.32.5", "0.32.5"))
        // 短段补 0："0.33" 与 "0.33.0" 同义
        assertEquals(0, AppUpdate.compareVersions("0.33", "0.33.0"))
        assertEquals(0, AppUpdate.compareVersions(null, null))
        assertEquals(0, AppUpdate.compareVersions("", ""))
    }

    @Test
    fun `比较_非法段按0处理不抛异常`() {
        // tag 里偶尔会带尾巴（"0.33.0-rc1"）：数字前缀截断拿值，其余当 0
        assertEquals(1, AppUpdate.compareVersions("0.33.0-rc1", "0.32.5"))
        // 段首不是数字 → 该段 0；比大小看更前面的段
        assertEquals(1, AppUpdate.compareVersions("1.0", "0.x.1"))
    }

    @Test
    fun `比较_预发后缀_rc小于正式`() {
        // 核心场景：装了 rc1 的用户，正式 0.33.0 发布后必须能看到红点
        assertEquals(-1, AppUpdate.compareVersions("0.33.0-rc1", "0.33.0"))
        assertEquals(1, AppUpdate.compareVersions("0.33.0", "0.33.0-rc1"))
        assertEquals(0, AppUpdate.compareVersions("0.33.0-rc1", "0.33.0-rc1"))
        // rc 之间比后缀数字
        assertEquals(-1, AppUpdate.compareVersions("0.33.0-rc1", "0.33.0-rc2"))
        // 数字段有差时后缀不参与
        assertEquals(-1, AppUpdate.compareVersions("0.32.9-rc2", "0.33.0-rc1"))
    }

    // ── shouldShowDot：红点状态机 ───────────────────────────────────────

    @Test
    fun `红点_有新版才亮`() {
        assertTrue(AppUpdate.shouldShowDot("0.32.5", "0.33.0", muted = null))
        assertFalse(AppUpdate.shouldShowDot("0.33.0", "0.33.0", muted = null))
        assertFalse(AppUpdate.shouldShowDot("0.33.0", "0.32.5", muted = null))
        // 远端还没查到过（null）：红点不亮 —— 不确定的事不吓唬人
        assertFalse(AppUpdate.shouldShowDot("0.32.5", latest = null, muted = null))
    }

    @Test
    fun `红点_屏蔽只认具体版本`() {
        // 屏蔽 0.33.0 后，再拉到 0.33.0 不亮
        assertFalse(AppUpdate.shouldShowDot("0.32.5", "0.33.0", muted = "0.33.0"))
        // 但出了更新的 0.34.0，照样亮（屏蔽不等于一键关提醒）
        assertTrue(AppUpdate.shouldShowDot("0.32.5", "0.34.0", muted = "0.33.0"))
        // 屏蔽的版本比远端旧（用户从老版本直接来的）：照样亮
        val latest = "0.34.0"
        assertTrue(AppUpdate.shouldShowDot("0.32.5", latest, muted = "0.32.9"))
    }
}
