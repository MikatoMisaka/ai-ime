package com.weike.ime.ai

import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiChatClientTest {

    @Test
    fun normalizesEndpointVariants() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            AiChatClient.chatCompletionsEndpoint("https://api.deepseek.com")
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            AiChatClient.chatCompletionsEndpoint("https://api.deepseek.com/v1/")
        )
        assertEquals(
            "https://example.com/proxy/v1/chat/completions",
            AiChatClient.chatCompletionsEndpoint("https://example.com/proxy/v1")
        )
        assertEquals(
            "https://example.com/custom/chat/completions",
            AiChatClient.chatCompletionsEndpoint("https://example.com/custom/chat/completions")
        )
    }

    @Test
    fun keepsOnlyRecentImageTurns() {
        val config = AiChatConfig(
            baseUrl = "https://example.com/v1",
            apiKey = "sk-test",
            model = "vision-model",
            historyImageRounds = 2
        )
        val image = ChatImage(base64 = "AAAA")
        val messages = listOf(
            ChatMessage("user", "最早的一张", listOf(image)),
            ChatMessage("assistant", "收到"),
            ChatMessage("user", "第二张", listOf(image)),
            ChatMessage("assistant", "收到"),
            ChatMessage("user", "最近一张", listOf(image))
        )

        val wire = AiChatClient().buildMessages(config, messages)
        // system + 5 turns
        assertEquals(6, wire.length())
        assertEquals("system", wire.getJSONObject(0).getString("role"))

        val oldest = wire.getJSONObject(1)
        assertEquals("user", oldest.getString("role"))
        assertTrue(oldest.getString("content").contains("[图片已省略]"))

        val newest = wire.getJSONObject(5)
        val content = newest.get("content") as JSONArray
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertTrue(
            content.getJSONObject(1)
                .getJSONObject("image_url")
                .getString("url")
                .startsWith("data:image/jpeg;base64,")
        )
    }

    @Test
    fun skipsFailedTurns() {
        val config = AiChatConfig(
            baseUrl = "https://example.com/v1",
            apiKey = "sk-test",
            model = "model"
        )
        val wire = AiChatClient().buildMessages(
            config,
            listOf(
                ChatMessage("user", "问题"),
                ChatMessage("assistant", "网络错误", failed = true)
            )
        )
        assertEquals(2, wire.length())
        assertFalse(wire.toString().contains("网络错误"))
    }
}
