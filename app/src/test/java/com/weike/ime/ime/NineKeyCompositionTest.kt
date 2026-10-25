package com.weike.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NineKeyCompositionTest {
    @Test fun supplementaryCharacterCannotOverwriteCommonCharacterReading() {
        assertEquals('好', bmpPinyinCharacter("597D"))
        assertEquals(null, bmpPinyinCharacter("2597D"))
    }
    @Test fun ambiguousNineKeyCodeOffersRealSyllablesAndInitials() {
        assertEquals(listOf("ni", "mi", "n", "m"),
            NineKeyComposition.spellingOptions("64426", listOf("ni hao", "mi gao", "ni gan", "hao")))
    }

    @Test fun syllableOptionsIncludeIncompleteSyllables() {
        assertTrue("ni" in NineKeyComposition.spellingOptions("6", listOf("ni", "mi")))
        assertTrue(NineKeyComposition.spellingOptions("2", listOf("ni", "mi")).isEmpty())
    }
    @Test fun exactCandidateReadingIsDisplayed() {
        assertEquals("ni", NineKeyComposition.display("64", "64", listOf("ni", "mi")))
    }

    @Test fun partialCandidateReadingNeverDisappears() {
        assertEquals("n", NineKeyComposition.display("6", "6", listOf("ni")))
    }

    @Test fun unmatchedCodeRemainsVisible() {
        assertEquals("…", NineKeyComposition.display("111", "111", emptyList()))
        assertTrue(NineKeyComposition.display("74264543", "74264543", emptyList()).isNotBlank())
    }

    @Test fun unmatchedSuffixDoesNotReplaceReadableComposition() {
        assertEquals(
            "shijie…",
            NineKeyComposition.display(
                raw = "74454399",
                preedit = "74454399",
                candidateReadings = emptyList(),
                previousRaw = "744543",
                previousDisplay = "shijie"
            )
        )
    }

    @Test fun unmatchedSuffixNeverLeaksNumericCodes() {
        val display = NineKeyComposition.display("96426", "96426", listOf("wo"), "9642", "woga")
        assertTrue(display.isNotBlank())
        assertTrue(display.none(Char::isDigit))
    }
}
