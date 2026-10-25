package com.weike.ime.network

import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppRuntimeConfigTest {
    @Test fun serverSelectionAndFlagsAreParsedWithoutCachingCredentials() {
        val config = AppRuntimeConfig.parse(JSONObject("""{"schemaVersion":1,"features":{"speechRecognition":{"enabled":true,"modelId":"asr1","displayName":"MiMo ASR","provider":"xiaomi_mimo","model":"mimo-v2.5-asr","apiKey":"must-not-cache"}}}"""))
        assertTrue(config.speechRecognition.enabled)
        assertEquals("asr1", config.speechRecognition.modelId)
        assertEquals("mimo-v2.5-asr", config.speechRecognition.model)
        assertFalse(config.toJson().toString().contains("must-not-cache"))
        assertEquals(config, AppRuntimeConfig.parse(config.toJson()))
    }
    @Test fun missingFeatureOrModelFailsClosed() {
        assertFalse(AppRuntimeConfig.parse(JSONObject("""{"schemaVersion":1,"features":{}}""")).speechRecognition.enabled)
        assertFalse(AppRuntimeConfig.parse(JSONObject("""{"schemaVersion":1,"features":{"speechRecognition":{"enabled":true}}}""")).speechRecognition.enabled)
    }
    @Test fun unsupportedSchemaDoesNotSilentlyEnableFeatures() {
        assertFailsWith<IllegalArgumentException> { AppRuntimeConfig.parse(JSONObject("""{"schemaVersion":2,"features":{}}""")) }
    }
}
