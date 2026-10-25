package com.weike.ime.network

import com.weike.ime.data.CloudApiSettings
import com.weike.ime.data.ModelEndpointConfig

enum class ModelFeature { REPLY, SUPER_SPEAK, VISION }

class ModelSetupRequiredException(message: String) : IllegalStateException(message)

data class ModelRoute(val official: Boolean, val endpoint: ModelEndpointConfig)

/**
 * Resolves the endpoint for the legacy reply/polish/vision helpers.
 *
 * The account/VIP backend is gone, so there is exactly one source: the local
 * endpoint the user configured. Missing configuration is reported instead of
 * silently falling back to a subscription service.
 */
class ModelAccessRouter(
    private val settingsProvider: () -> CloudApiSettings
) {
    suspend fun route(feature: ModelFeature): ModelRoute {
        val settings = settingsProvider()
        val local = when (feature) {
            ModelFeature.VISION -> settings.vision
            ModelFeature.REPLY -> settings.reply
            ModelFeature.SUPER_SPEAK -> settings.superSpeak
        }
        if (local.isComplete()) return ModelRoute(false, local)
        val name = if (feature == ModelFeature.VISION) "视觉" else "文本"
        throw ModelSetupRequiredException("请先在「AI 模型设置」里配置${name}模型")
    }
}
