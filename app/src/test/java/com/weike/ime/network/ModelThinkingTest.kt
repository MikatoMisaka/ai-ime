package com.weike.ime.network

import com.weike.ime.data.CloudProvider
import com.weike.ime.data.ModelEndpointConfig
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ModelThinkingTest {
    private val deepseek = ModelEndpointConfig("https://api.deepseek.com/v1", "dummy-only", "deepseek-flash", CloudProvider.DEEPSEEK)

    @Test fun `all text requests explicitly switch thinking in sync and streaming mode`() {
        val polisher = MimoTextPolisher(endpointProvider = { deepseek })
        for (stream in listOf(false, true)) for (enabled in listOf(false, true)) {
            val request = polisher.buildRequest(deepseek.copy(thinkingEnabled = enabled), "system", "hello", .2, stream)
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            val json = JSONObject(buffer.readUtf8())
            assertEquals(if (enabled) "enabled" else "disabled", json.getJSONObject("thinking").getString("type"))
            assertEquals(stream, json.optBoolean("stream"))
            assertEquals(!enabled, json.has("temperature"))
        }
    }

    @Test fun `vision parameters preserve multimodal input and default to disabled`() {
        val messages = JSONArray().put(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "image_url"))))
        val payload = ModelThinking.applyTo(JSONObject().put("messages", messages), deepseek)
        assertEquals("disabled", payload.getJSONObject("thinking").getString("type"))
        assertEquals("image_url", payload.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("type"))
    }

    @Test fun `known models and official custom endpoints use safe capabilities`() {
        for ((provider, name, control) in listOf(
            Triple(CloudProvider.XIAOMI_MIMO, "mimo-v2.5", ModelThinking.Control.THINKING),
            Triple(CloudProvider.XIAOMI_MIMO, "mimo-v2.5-asr", ModelThinking.Control.UNSUPPORTED),
            Triple(CloudProvider.ZHIPU, "glm-4.7-flash", ModelThinking.Control.THINKING),
            Triple(CloudProvider.ZHIPU, "glm-5.3-flash", ModelThinking.Control.REQUIRED),
            Triple(CloudProvider.CUSTOM, "kimi-k2.6", ModelThinking.Control.THINKING),
            Triple(CloudProvider.CUSTOM, "kimi-k3", ModelThinking.Control.REQUIRED),
            Triple(CloudProvider.OPENAI, "gpt-4o", ModelThinking.Control.UNSUPPORTED)
        )) {
            val endpoint = ModelEndpointConfig(if (name.startsWith("kimi")) "https://api.moonshot.cn/v1" else "https://example.invalid/v1", model = name, provider = provider)
            assertEquals(control, ModelThinking.control(endpoint))
            if (control != ModelThinking.Control.THINKING) assertFalse(ModelThinking.applyTo(JSONObject(), endpoint).has("thinking"))
        }
        assertEquals(ModelThinking.Control.THINKING, ModelThinking.control(deepseek.copy(provider = CloudProvider.CUSTOM)))
        assertEquals(ModelThinking.Control.UNSUPPORTED, ModelThinking.control(deepseek.copy(provider = CloudProvider.CUSTOM, url = "https://api.deepseek.com.evil.example/v1")))
    }
}
