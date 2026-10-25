package com.weike.ime.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuggestionFallbackTest {
    @Test
    fun `reply fallback always returns three distinct choices`() {
        val choices = SuggestionFallback.replies("今晚有空吗？")
        assertEquals(3, choices.size)
        assertEquals(3, choices.distinct().size)
        assertTrue(choices.all(String::isNotBlank))
    }

    @Test
    fun `polish fallback supports short and formal input`() {
        assertEquals(3, SuggestionFallback.polishes("你", "通用").distinct().size)
        assertTrue(SuggestionFallback.polishes("请把资料发给我", "客户").last().startsWith("你好"))
    }
}
