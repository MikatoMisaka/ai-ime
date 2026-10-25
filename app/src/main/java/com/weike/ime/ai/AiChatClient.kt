package com.weike.ime.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal OpenAI-compatible chat-completions client.
 *
 * Only `/v1/chat/completions` (or a user-supplied full URL) is supported, which
 * covers DeepSeek, Qwen, Kimi, SiliconFlow, Ollama, one-api and friends. Images
 * are sent as `image_url` data URLs so a vision-capable model sees them as part
 * of the conversation context.
 */
class AiChatClient {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    suspend fun complete(
        config: AiChatConfig,
        messages: List<ChatMessage>
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("model", config.model)
                put("stream", false)
                put("max_tokens", 2048)
                put("messages", buildMessages(config, messages))
            }
            val request = Request.Builder()
                .url(chatCompletionsEndpoint(config.baseUrl))
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "请求失败（HTTP ${response.code}）：${errorMessage(body)}"
                    )
                }
                val content = extractContent(JSONObject(body))
                if (content.isBlank()) throw IllegalStateException("模型返回了空内容")
                content
            }
        }
    }

    /**
     * Builds the wire messages. Only the newest [AiChatConfig.historyImageRounds]
     * image-bearing user turns keep their pixels; older images become a text
     * placeholder so the request does not blow up in size and cost.
     */
    internal fun buildMessages(
        config: AiChatConfig,
        messages: List<ChatMessage>
    ): JSONArray {
        val keep = mutableSetOf<String>()
        messages.asReversed()
            .filter { it.role == "user" && it.hasImages }
            .take(config.historyImageRounds.coerceIn(1, 10))
            .forEach { keep += it.id }

        val array = JSONArray()
        if (config.systemPrompt.isNotBlank()) {
            array.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", config.systemPrompt)
            )
        }
        messages.forEach { message ->
            if (message.failed) return@forEach
            val withImages = message.hasImages && message.id in keep
            val text = if (message.hasImages && !withImages) {
                val note = "[图片已省略]"
                if (message.text.isBlank()) note else "${message.text}\n$note"
            } else {
                message.text
            }
            val content = when {
                withImages -> JSONArray().apply {
                    if (text.isNotBlank()) {
                        put(JSONObject().put("type", "text").put("text", text))
                    }
                    message.images.forEach { image ->
                        put(
                            JSONObject()
                                .put("type", "image_url")
                                .put(
                                    "image_url",
                                    JSONObject().put(
                                        "url",
                                        "data:${image.mime};base64,${image.base64}"
                                    )
                                )
                        )
                    }
                }
                else -> text
            }
            array.put(JSONObject().put("role", message.role).put("content", content))
        }
        return array
    }

    private fun extractContent(json: JSONObject): String {
        val choices = json.optJSONArray("choices") ?: return ""
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
        val content = message.opt("content")
        return when (content) {
            is String -> content.trim()
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    val part = content.optJSONObject(index) ?: continue
                    val value = part.optString("text")
                    if (value.isNotBlank()) append(value)
                }
            }.trim()
            else -> ""
        }
    }

    private fun errorMessage(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return "无响应内容"
        return runCatching {
            val json = JSONObject(trimmed)
            val error = json.opt("error")
            when (error) {
                is JSONObject -> error.optString("message").ifBlank { trimmed }
                is String -> error.ifBlank { trimmed }
                else -> trimmed
            }
        }.getOrDefault(trimmed).take(300)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Accepts `https://host`, `https://host/v1`, `https://host/v1/chat/completions`
         * or any custom path, and always yields a full chat-completions URL.
         */
        fun chatCompletionsEndpoint(raw: String): String {
            val value = raw.trim().trimEnd('/')
            if (value.isEmpty()) return value
            if (value.endsWith("/chat/completions", ignoreCase = true)) return value
            val path = value.substringAfter("://", "").substringAfter('/', "")
            return if (path.isEmpty()) "$value/v1/chat/completions" else "$value/chat/completions"
        }
    }
}
