package com.zzupocket.app.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `解析标题段落列表引用与分隔线`() {
        val blocks = MarkdownParser.parse(
            """
            ## 今日速览

            第一段介绍。

            - **重点**：报名截止 11 月 30 日
            - 地点：综合管理中心

            1. 第一步
            2. 第二步

            > 以官网原文为准

            ---
            """.trimIndent(),
        )

        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertEquals(2, (blocks[0] as MarkdownBlock.Heading).level)
        assertTrue(blocks[1] is MarkdownBlock.Paragraph)
        assertEquals(2, (blocks[2] as MarkdownBlock.Bullet).items.size)
        assertEquals(2, (blocks[3] as MarkdownBlock.Numbered).items.size)
        assertTrue(blocks[4] is MarkdownBlock.Quote)
        assertEquals(MarkdownBlock.Divider, blocks[5])
    }

    @Test
    fun `围栏代码原样保留且不吞掉后续段落`() {
        val blocks = MarkdownParser.parse(
            """
            ```json
            {"a": 1}
            ```

            代码之后的说明。
            """.trimIndent(),
        )

        assertEquals(2, blocks.size)
        val code = blocks[0] as MarkdownBlock.Code
        assertEquals("json", code.language)
        assertEquals("{\"a\": 1}", code.code)
        assertTrue(blocks[1] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `普通段落合并连续行`() {
        val blocks = MarkdownParser.parse("第一行\n第二行\n\n下一段")
        assertEquals(2, blocks.size)
        assertEquals("第一行 第二行", (blocks[0] as MarkdownBlock.Paragraph).text)
    }
}
