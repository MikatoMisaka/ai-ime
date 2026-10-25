package com.weike.ime.speech

import kotlin.test.Test
import kotlin.test.assertEquals

class MimoAsrClientTest {
    @Test
    fun `strips language markup tags around transcript`() {
        assertEquals("今天天气怎么样", MimoAsrClient.cleanAsrTranscript("<chinese>今天天气怎么样</chinese>"))
        assertEquals("hello world", MimoAsrClient.cleanAsrTranscript("<english>hello world</english>"))
        assertEquals("今天天气怎么样", MimoAsrClient.cleanAsrTranscript("  <chinese> 今天天气怎么样 </chinese> "))
    }

    @Test
    fun `strips stray tags inside transcript`() {
        assertEquals("好的没问题", MimoAsrClient.cleanAsrTranscript("<chinese>好的</chinese><chinese>没问题</chinese>"))
        assertEquals("混合中文和english", MimoAsrClient.cleanAsrTranscript("<zh>混合中文和english</zh>"))
    }

    @Test
    fun `keeps normal transcript untouched`() {
        assertEquals("正常文字", MimoAsrTranscript("正常文字"))
        assertEquals("3 小于 5", MimoAsrClient.cleanAsrTranscript("3 小于 5"))
    }

    @Test
    fun `still removes trailing null after chinese`() {
        assertEquals("内容", MimoAsrClient.cleanAsrTranscript("内容null"))
        // 英文口述结尾的合法 null 单词不能被误删
        assertEquals("the value is null", MimoAsrClient.cleanAsrTranscript("the value is null"))
    }

    private fun MimoAsrTranscript(value: String) = MimoAsrClient.cleanAsrTranscript(value)
}
