package com.weike.ime.text

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

class AiFallbackReasonTest {
    @Test
    fun `classifies missing model configuration`() {
        assertEquals("未配置模型", AiFallbackReason.classify(IllegalStateException("请先配置文本模型接口")))
    }

    @Test
    fun `classifies authorization failures`() {
        assertEquals("模型鉴权失败", AiFallbackReason.classify(IllegalStateException("生成推荐回复失败: 401")))
        assertEquals("模型鉴权失败", AiFallbackReason.classify(IllegalStateException("forbidden api key")))
    }

    @Test
    fun `classifies rate limit and server failures`() {
        assertEquals("模型限流", AiFallbackReason.classify(IllegalStateException("HTTP 429 rate limit")))
        assertEquals("模型服务异常", AiFallbackReason.classify(IllegalStateException("生成推荐回复失败: 503")))
    }

    @Test
    fun `classifies network failures`() {
        assertEquals("网络异常", AiFallbackReason.classify(UnknownHostException("api.example.com")))
        assertEquals("网络异常", AiFallbackReason.classify(SocketTimeoutException("timeout")))
        assertEquals("网络异常", AiFallbackReason.classify(IOException("connection reset")))
    }

    @Test
    fun `classifies invalid model response`() {
        assertEquals("模型回复异常", AiFallbackReason.classify(IllegalStateException("模型未返回三条有效回复")))
        assertEquals("模型回复异常", AiFallbackReason.classify(IllegalStateException("JSONArray parse failed")))
    }

    @Test
    fun `formats local fallback label`() {
        assertEquals("未配置模型 · 本地建议", AiFallbackReason.replyLabel(IllegalStateException("请先配置文本模型接口")))
    }
}
