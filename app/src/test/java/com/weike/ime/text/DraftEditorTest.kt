package com.weike.ime.text

import org.junit.Assert.assertEquals
import org.junit.Test

class DraftEditorTest {
    @Test fun insertsAtSelectedChineseCharacter() {
        assertEquals(DraftEdit("很喜欢你", 1), DraftEditor.insert("喜欢你", 0, "很"))
    }

    @Test fun deletesOnlyCharacterBeforeCursor() {
        assertEquals(DraftEdit("喜欢你", 0), DraftEditor.deleteBefore("很喜欢你", 1))
    }

    @Test fun deletesEmojiAsOneCodePoint() {
        assertEquals(DraftEdit("喜欢你", 0), DraftEditor.deleteBefore("😊喜欢你", 2))
    }
}
