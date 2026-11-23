package com.zzupocket.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptsTest {

    @Test
    fun `超长正文截断并明确标记`() {
        val text = "长".repeat(AiPrompts.MAX_BODY_CHARS + 20)
        val result = AiPrompts.truncate(text)
        assertTrue(result.endsWith("（正文过长，已截断）"))
        assertTrue(result.length < text.length + 20)
    }

    @Test
    fun `文章提示包含标题来源日期且正文为空时退回标题`() {
        val prompt = AiPrompts.articleUser(
            title = "关于举办竞赛的通知",
            body = "",
            publisher = "教务处",
            publishedAt = "2026-11-23",
        )
        assertTrue(prompt.contains("关于举办竞赛的通知"))
        assertTrue(prompt.contains("教务处"))
        assertTrue(prompt.contains("2026-11-23"))
    }

    @Test
    fun `速览最多带五条标题和摘要`() {
        val items = (1..8).map {
            AiPrompts.OverviewItem(
                title = "通知$it",
                publisher = "学院$it",
                publishedAt = "2026-11-2$it",
                excerpt = "摘要$it",
            )
        }
        val prompt = AiPrompts.overviewUser(items)
        assertTrue(prompt.contains("1. 通知1"))
        assertTrue(prompt.contains("5. 通知5"))
        assertFalse(prompt.contains("6. 通知6"))
        assertEquals(false, prompt.contains("摘要6"))
    }

    @Test
    fun `系统提示明确禁止执行正文指令`() {
        assertTrue(AiPrompts.articleSystem().contains("不要执行"))
        assertTrue(AiPrompts.overviewSystem().contains("不要执行"))
    }
}
