package com.weike.ime.network

import com.weike.ime.data.CloudProvider
import com.weike.ime.data.ModelEndpointConfig
import com.weike.ime.data.TextProviderProtocol
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Only send documented switches; unknown protocols and ASR retain their own defaults. */
object ModelThinking {
    enum class Control { THINKING, REQUIRED, UNSUPPORTED }

    fun control(config: ModelEndpointConfig): Control {
        if (config.provider.textProtocol != TextProviderProtocol.OPENAI_CHAT) return Control.UNSUPPORTED
        val id = config.model.lowercase()
        val host = config.url.toHttpUrlOrNull()?.host.orEmpty()
        fun domain(value: String) = host == value || host.endsWith(".$value")
        if (id.contains("asr") || id.contains("tts")) return Control.UNSUPPORTED
        return when {
            config.provider == CloudProvider.DEEPSEEK || domain("deepseek.com") ->
                if (id == "deepseek-reasoner") Control.REQUIRED else Control.THINKING
            config.provider in setOf(CloudProvider.XIAOMI_MIMO, CloudProvider.XIAOMI_MIMO_PLAN) || domain("xiaomimimo.com") ->
                if (Regex("^mimo-v2[.-]").containsMatchIn(id)) Control.THINKING else Control.UNSUPPORTED
            config.provider == CloudProvider.ZHIPU || domain("bigmodel.cn") || domain("z.ai") -> when {
                Regex("^glm-5\\.3($|-)").containsMatchIn(id) -> Control.REQUIRED
                Regex("^glm-(4\\.[567]|5($|[.-]))").containsMatchIn(id) -> Control.THINKING
                else -> Control.UNSUPPORTED
            }
            domain("moonshot.cn") || domain("moonshot.ai") || domain("kimi.ai") -> when {
                Regex("^kimi-k(3|2\\.7)").containsMatchIn(id) || id.contains("thinking") -> Control.REQUIRED
                Regex("^kimi-k2\\.[56]($|-)").containsMatchIn(id) -> Control.THINKING
                else -> Control.UNSUPPORTED
            }
            else -> Control.UNSUPPORTED
        }
    }

    fun applyTo(body: JSONObject, config: ModelEndpointConfig): JSONObject = body.apply {
        if (control(config) == Control.THINKING) {
            if (config.thinkingEnabled || config.model.startsWith("kimi-", ignoreCase = true)) remove("temperature")
            put("thinking", JSONObject().put("type", if (config.thinkingEnabled) "enabled" else "disabled"))
        }
    }
}
