package com.weike.ime.ai

import android.content.Context
import com.weike.ime.data.SecureSecretStore

/**
 * Local-only configuration for the built-in AI chat.
 *
 * The endpoint follows the OpenAI chat-completions shape, so any compatible
 * provider (DeepSeek, Qwen, Kimi, SiliconFlow, Ollama, one-api, ...) works.
 * The API key never leaves the device except in the request to that endpoint,
 * and is stored through [SecureSecretStore] (Android Keystore + AES/GCM).
 */
data class AiChatConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val systemPrompt: String = AiChatSettings.DEFAULT_SYSTEM_PROMPT,
    val defaultImagePrompt: String = AiChatSettings.DEFAULT_IMAGE_PROMPT,
    val maxImages: Int = AiChatSettings.DEFAULT_MAX_IMAGES,
    val historyImageRounds: Int = AiChatSettings.DEFAULT_HISTORY_IMAGE_ROUNDS
) {
    fun isComplete(): Boolean =
        baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

class AiChatSettings(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val secrets = SecureSecretStore(context.applicationContext)

    fun config(): AiChatConfig = AiChatConfig(
        baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty().trim(),
        apiKey = secrets.read(SECRET_API_KEY).orEmpty(),
        model = preferences.getString(KEY_MODEL, "").orEmpty().trim(),
        systemPrompt = preferences.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT)
            .orEmpty().ifBlank { DEFAULT_SYSTEM_PROMPT },
        defaultImagePrompt = preferences.getString(KEY_IMAGE_PROMPT, DEFAULT_IMAGE_PROMPT)
            .orEmpty().ifBlank { DEFAULT_IMAGE_PROMPT },
        maxImages = preferences.getInt(KEY_MAX_IMAGES, DEFAULT_MAX_IMAGES),
        historyImageRounds = preferences.getInt(KEY_HISTORY_ROUNDS, DEFAULT_HISTORY_IMAGE_ROUNDS)
    )

    fun save(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        defaultImagePrompt: String
    ) {
        preferences.edit()
            .putString(KEY_BASE_URL, baseUrl.trim())
            .putString(KEY_MODEL, model.trim())
            .putString(
                KEY_SYSTEM_PROMPT,
                systemPrompt.trim().ifBlank { DEFAULT_SYSTEM_PROMPT }
            )
            .putString(
                KEY_IMAGE_PROMPT,
                defaultImagePrompt.trim().ifBlank { DEFAULT_IMAGE_PROMPT }
            )
            .apply()
        secrets.write(SECRET_API_KEY, apiKey.trim())
    }

    companion object {
        const val DEFAULT_MAX_IMAGES = 4
        const val DEFAULT_HISTORY_IMAGE_ROUNDS = 2

        const val DEFAULT_SYSTEM_PROMPT =
            "你是一个内置在手机输入法里的 AI 助手。回答要简洁、口语化，能够直接发送给对方；" +
                "除非用户明确要求，不要输出 Markdown 标题、表格或整段代码块。"

        const val DEFAULT_IMAGE_PROMPT =
            "请结合这张图片回答我的问题；如果图片里有文字，请完整、准确地提取出来。"

        private const val FILE_NAME = "ai_chat_settings"
        private const val SECRET_API_KEY = "ai_chat_api_key"
        private const val KEY_BASE_URL = "ai_chat_base_url"
        private const val KEY_MODEL = "ai_chat_model"
        private const val KEY_SYSTEM_PROMPT = "ai_chat_system_prompt"
        private const val KEY_IMAGE_PROMPT = "ai_chat_image_prompt"
        private const val KEY_MAX_IMAGES = "ai_chat_max_images"
        private const val KEY_HISTORY_ROUNDS = "ai_chat_history_rounds"
    }
}
