package com.weike.ime.text

data class DraftEdit(val text: String, val cursor: Int)

object DraftEditor {
    fun insert(source: String, cursor: Int, value: String): DraftEdit {
        val at = cursor.coerceIn(0, source.length)
        return DraftEdit(source.substring(0, at) + value + source.substring(at), at + value.length)
    }

    fun deleteBefore(source: String, cursor: Int): DraftEdit {
        val at = cursor.coerceIn(0, source.length)
        if (at == 0) return DraftEdit(source, 0)
        val start = source.offsetByCodePoints(at, -1)
        return DraftEdit(source.removeRange(start, at), start)
    }
}
