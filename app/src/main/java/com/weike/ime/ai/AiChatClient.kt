package com.weike.ime.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
    ): Result<ChatCompletion> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("model", config.model)
                put("stream", false)
                put("max_tokens", config.maxTokens)
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
                val completion = parseAssistantMessage(JSONObject(body))
                if (completion.content.isBlank() && completion.reasoning.isBlank()) {
                    throw IllegalStateException("模型返回了空内容")
                }
                completion
            }
        }
    }

    /**
     * Streams the reply as SSE deltas. Reasoning and answer arrive in the same
     * delta object on thinking models, so both are surfaced as they land.
     */
    fun stream(config: AiChatConfig, messages: List<ChatMessage>): Flow<ChatStreamEvent> = flow {
        val payload = JSONObject().apply {
            put("model", config.model)
            put("stream", true)
            put("max_tokens", config.maxTokens)
            put("messages", buildMessages(config, messages))
        }
        val request = Request.Builder()
            .url(chatCompletionsEndpoint(config.baseUrl))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = client.newCall(request)
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw IllegalStateException(
                    "请求失败（HTTP ${response.code}）：${errorMessage(body)}"
                )
            }
            val source = response.body?.source()
                ?: throw IllegalStateException("模型没有返回响应体")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break
                val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                val delta = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("delta")
                    ?: continue
                val content = textOf(delta.opt("content"))
                val reasoning = textOf(delta.opt("reasoning_content"))
                    .ifBlank { textOf(delta.opt("reasoning")) }
                if (content.isNotEmpty() || reasoning.isNotEmpty()) {
                    emit(ChatStreamEvent(content = content, reasoning = reasoning))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Lists the endpoint's models (`GET /v1/models`), newest servers first. */
    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(modelsEndpoint(baseUrl))
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IllegalStateException(
                            "获取模型失败（HTTP ${response.code}）：${errorMessage(body)}"
                        )
                    }
                    val json = JSONObject(body)
                    val array = json.optJSONArray("data") ?: json.optJSONArray("models")
                        ?: JSONArray()
                    buildList {
                        for (index in 0 until array.length()) {
                            val id = when (val item = array.opt(index)) {
                                is JSONObject -> item.optString("id").ifBlank { item.optString("name") }
                                is String -> item
                                else -> ""
                            }
                            if (id.isNotBlank()) add(id)
                        }
                    }.distinct().sorted()
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

    /**
     * Reads both the answer and the reasoning block. Providers disagree on the
     * field name (`reasoning_content` for DeepSeek/Qwen/GLM, `reasoning` for a
     * few gateways) and on the shape (plain string or array of parts).
     */
    internal fun parseAssistantMessage(json: JSONObject): ChatCompletion {
        val choices = json.optJSONArray("choices") ?: return ChatCompletion("", "")
        val message = choices.optJSONObject(0)?.optJSONObject("message")
            ?: return ChatCompletion("", "")
        val content = textOf(message.opt("content"))
        val reasoning = textOf(message.opt("reasoning_content"))
            .ifBlank { textOf(message.opt("reasoning")) }
        return ChatCompletion(content = content, reasoning = reasoning)
    }

    private fun textOf(value: Any?): String = when (value) {
        is String -> value.trim()
        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                val part = value.optJSONObject(index) ?: continue
                val text = part.optString("text").ifBlank { part.optString("content") }
                if (text.isNotBlank()) append(text)
            }
        }.trim()
        else -> ""
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

        /** `GET /models` next to the chat-completions endpoint, e.g. `/v1/models`. */
        fun modelsEndpoint(raw: String): String {
            val chat = chatCompletionsEndpoint(raw)
            if (chat.isBlank()) return chat
            return chat.removeSuffix("/chat/completions") + "/models"
        }
    }
}
