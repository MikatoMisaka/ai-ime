package com.weike.ime.text

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object AiFallbackReason {
    fun replyLabel(error: Throwable): String = "${classify(error)} · 本地建议"

    fun classify(error: Throwable): String {
        val chain = generateSequence(error as Throwable?) { it.cause }.toList()
        val message = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()

        return when {
            chain.any { it is UnknownHostException || it is ConnectException || it is SocketTimeoutException || it is SSLException } ->
                "网络异常"
            message.contains("请先配置") || message.contains("请完整填写") || message.contains("not configured") ->
                "未配置模型"
            message.contains("401") || message.contains("403") || message.contains("unauthorized") || message.contains("forbidden") || message.contains("api key") || message.contains("apikey") ->
                "模型鉴权失败"
            message.contains("429") || message.contains("rate limit") || message.contains("quota") || message.contains("too many requests") ->
                "模型限流"
            message.contains("404") || message.contains("not found") || message.contains("model") && message.contains("不存在") ->
                "模型或接口不存在"
            message.contains("500") || message.contains("502") || message.contains("503") || message.contains("504") ->
                "模型服务异常"
            message.contains("未返回") || message.contains("json") || message.contains("array") || message.contains("格式") ->
                "模型回复异常"
            chain.any { it is IOException } ->
                "网络异常"
            else ->
                "AI暂不可用"
        }
    }
}
