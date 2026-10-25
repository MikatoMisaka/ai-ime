package com.weike.ime.network

import com.weike.ime.data.ModelEndpointConfig
import org.json.JSONObject
import org.junit.Test
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.*

class ModelRoutingPolicyTest {
    private val now = 1000L
    private val local = ModelEndpointConfig("https://local.example/v1", "user-key", "user-model")
    private fun config(vip: Boolean = true, expires: Long = 2000L) = AppRuntimeConfig(
        visionReply = AppFeatureConfig(true, "vision", model = "vision-model"),
        replySuggestions = AppFeatureConfig(true, "reply", model = "reply-model"),
        superSpeak = AppFeatureConfig(true, "polish", model = "polish-model"),
        user = RuntimeUser("user", "", vip, expires, "VIP", 100))

    @Test fun activeVipUsesEachBusinessBindingEvenWhenLocalModelExists() {
        ModelFeature.entries.forEach { feature ->
            val route = ModelRoutingPolicy.select(config(), feature, local, "app-token", now)
            assertTrue(route.official)
            assertEquals("app-token", route.endpoint.apiKey)
            assertFalse(route.endpoint.url.contains("local.example"))
        }
        assertTrue(ModelRoutingPolicy.select(config(), ModelFeature.REPLY, local, "app-token", now).endpoint.url.endsWith("/text/reply"))
        assertTrue(ModelRoutingPolicy.select(config(), ModelFeature.SUPER_SPEAK, local, "app-token", now).endpoint.url.endsWith("/text/polish"))
    }
    @Test fun nonVipExpiredAndAnonymousAlwaysUseLocalConfiguration() {
        for (config in listOf(config(false), config(expires = now), config().copy(user = null), null)) {
            ModelFeature.entries.forEach { feature ->
                assertEquals(ModelRoute(false, local), ModelRoutingPolicy.select(config, feature, local, "token", now))
            }
        }
        assertFalse(ModelRoutingPolicy.select(config(), ModelFeature.REPLY, local, null, now).official)
    }
    @Test fun missingBusinessUsesLocalAndMissingLocalPromptsForSetup() {
        val config = config().copy(replySuggestions = AppFeatureConfig())
        assertFalse(ModelRoutingPolicy.select(config, ModelFeature.REPLY, local, "token", now).official)
        assertTrue(ModelRoutingPolicy.select(config, ModelFeature.SUPER_SPEAK, local, "token", now).official)
        val error = assertFailsWith<ModelSetupRequiredException> {
            ModelRoutingPolicy.select(config(false), ModelFeature.REPLY, ModelEndpointConfig(), "token", now)
        }
        assertTrue(error.message.orEmpty().contains("开通 VIP"))
        assertTrue(error.message.orEmpty().contains("模型配置"))
    }
    @Test fun configParsesCurrentUserVipAndIndependentFeatures() {
        val value = AppRuntimeConfig.parse(config().toJson())
        assertEquals(config(), value)
        assertTrue(value.user!!.isVip(now))
        assertFalse(value.user.isVip(2000L))
        assertNull(AppRuntimeConfig.parse(JSONObject("""{"schemaVersion":1,"features":{}}""")).user)
    }
    @Test fun serverDenialKeepsActionableSetupMessageButVendorErrorsRemainNetworkErrors() {
        fun response(host: String) = Response.Builder().request(Request.Builder().url("https://$host/api/app/text/reply/chat/completions").build())
            .protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
            .body("""{"code":"VIP_REQUIRED","message":"请开通 VIP 或配置模型"}""".toResponseBody()).build()
        response("lianai.bahuang.tech").use {
            val error = MimoApiConfig.responseException(it, "请求失败")
            assertTrue(error is ModelSetupRequiredException)
            assertEquals("请开通 VIP 或配置模型", error.message)
        }
        response("user-provider.example").use {
            assertFalse(MimoApiConfig.responseException(it, "请求失败") is ModelSetupRequiredException)
        }
    }
}
