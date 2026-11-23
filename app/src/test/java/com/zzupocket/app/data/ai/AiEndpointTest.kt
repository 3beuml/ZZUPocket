package com.zzupocket.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AiEndpointTest {

    @Test
    fun `留空使用 DeepSeek 默认地址`() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            AiEndpoint.chatUrl(""),
        )
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            AiEndpoint.chatUrl(null),
        )
    }

    @Test
    fun `OpenAI 兼容根地址补全接口路径`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            AiEndpoint.chatUrl("https://api.openai.com/v1/"),
        )
        assertEquals(
            "https://example.com/v1/chat/completions",
            AiEndpoint.chatUrl("https://example.com/v1"),
        )
    }

    @Test
    fun `已经粘贴完整端点时原样使用`() {
        assertEquals(
            "https://example.com/v1/chat/completions",
            AiEndpoint.chatUrl("https://example.com/v1/chat/completions"),
        )
    }

    @Test
    fun `只接受 https 且拒绝查询参数`() {
        assertNull(AiEndpoint.validate("https://api.deepseek.com"))
        assertNull(AiEndpoint.validate(""))
        assertNotNull(AiEndpoint.validate("http://api.deepseek.com"))
        assertNotNull(AiEndpoint.validate("https://api.deepseek.com?x=1"))
        assertNotNull(AiEndpoint.validate("not-a-url"))
    }
}
