package com.weike.ime.network

import kotlinx.coroutines.suspendCancellableCoroutine
import com.weike.ime.data.ModelEndpointConfig
import com.weike.ime.data.TextProviderProtocol
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class VisionReplyResult(
    val scenario: String,
    val analysis: String,
    val replies: List<String>
)

/** 截图识别回复：走后台视觉模型（登录态），后台自动按模型价格记账。 */
class VisionReplyClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
) {
    /** 自配视觉参数直接请求用户选择的服务商。 */
    suspend fun replyWithModel(config: ModelEndpointConfig, imageBase64: String, mime: String): Result<VisionReplyResult> = runCatching {
        val prompt = "识别聊天截图中对方最新的话，以用户本人的第一人称生成3条自然得体的回复。只输出JSON：{\"scenario\":\"恋爱|朋友|商务|家人|其他\",\"analysis\":\"不超过20字\",\"replies\":[\"稳妥回复\",\"进阶回复\",\"巧思回复\"]}"
        val (url, payload) = when (config.provider.textProtocol) {
            TextProviderProtocol.OPENAI_CHAT -> MimoApiConfig.chatCompletionsEndpoint(config.url) to JSONObject()
                .put("model", config.model).put("max_tokens", 4096).put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", prompt))
                    .put(JSONObject().put("role", "user").put("content", JSONArray()
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$mime;base64,$imageBase64")))
                        .put(JSONObject().put("type", "text").put("text", "分析这张聊天截图并生成回复")))))
            TextProviderProtocol.ANTHROPIC_MESSAGES -> MimoApiConfig.anthropicMessagesEndpoint(config.url) to JSONObject()
                .put("model", config.model).put("max_tokens", 4096).put("system", prompt)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", mime).put("data", imageBase64)))
                    .put(JSONObject().put("type", "text").put("text", "分析这张聊天截图并生成回复")))))
            TextProviderProtocol.GEMINI_GENERATE_CONTENT -> MimoApiConfig.geminiGenerateContentEndpoint(config.url, config.model, false) to JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                    .put(JSONObject().put("inlineData", JSONObject().put("mimeType", mime).put("data", imageBase64)))
                    .put(JSONObject().put("text", "分析这张聊天截图并生成回复")))))
        }
        ModelThinking.applyTo(payload, config)
        val request = MimoApiConfig.applyAuthorization(Request.Builder().url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())), config.provider, config.apiKey).build()
        client.newCall(request).await { response ->
            check(response.isSuccessful) { MimoApiConfig.responseError(response, "视觉模型请求失败") }
            val json = JSONObject(response.body?.string().orEmpty())
            fun partsText(parts: JSONArray?) = parts?.let { (0 until it.length()).joinToString("") { index -> it.optJSONObject(index)?.optString("text").orEmpty() } }.orEmpty()
            val content = when (config.provider.textProtocol) {
                TextProviderProtocol.OPENAI_CHAT -> json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                TextProviderProtocol.ANTHROPIC_MESSAGES -> partsText(json.optJSONArray("content"))
                TextProviderProtocol.GEMINI_GENERATE_CONTENT -> partsText(json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts"))
            }
            val start = content.indexOf('{'); val end = content.lastIndexOf('}')
            check(start >= 0 && end > start) { "视觉模型未返回有效回复，请确认模型支持图片输入" }
            val result = JSONObject(content.substring(start, end + 1))
            val list = result.optJSONArray("replies") ?: JSONArray()
            val replies = (0 until list.length()).map { list.optString(it).trim() }.filter { it.isNotBlank() }.take(3)
            check(replies.isNotEmpty()) { "未识别到可回复的内容，请换一张截图" }
            VisionReplyResult(result.optString("scenario"), result.optString("analysis"), replies)
        }
    }

    /** 功能开关（公开接口）。 */
    suspend fun enabled(): Result<Boolean> = runCatching {
        val request = Request.Builder().url("$BASE/business").get().build()
        client.newCall(request).await { response ->
            check(response.isSuccessful) { responseError(response) }
            JSONObject(response.body?.string().orEmpty()).optBoolean("visionReplyEnabled")
        }
    }

    suspend fun reply(token: String, imageBase64: String, mime: String): Result<VisionReplyResult> = runCatching {
        val body = JSONObject()
            .put("imageBase64", imageBase64)
            .put("mime", mime)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$BASE/vision/reply")
            .header(AUTH_HEADER, "Bearer $token")
            .post(body)
            .build()
        client.newCall(request).await { response ->
            check(response.isSuccessful) { responseError(response) }
            val json = JSONObject(response.body?.string().orEmpty())
            val replies = JSONArray().let { array ->
                buildList {
                    val source = json.optJSONArray("replies") ?: JSONArray()
                    for (index in 0 until source.length()) add(source.optString(index))
                }
            }
            VisionReplyResult(
                scenario = json.optString("scenario"),
                analysis = json.optString("analysis"),
                replies = replies
            )
        }
    }

    private fun responseError(response: Response): String = runCatching {
        JSONObject(response.body?.string().orEmpty()).optString("message").ifBlank { "请求失败（HTTP ${response.code}）" }
    }.getOrDefault("请求失败（HTTP ${response.code}）")

    private suspend fun <T> Call.await(transform: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { runCatching { transform(it) } }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }

    companion object {
        const val BASE = "https://lianai.bahuang.tech/api/app"
        const val AUTH_HEADER = "Authorization"
    }
}
