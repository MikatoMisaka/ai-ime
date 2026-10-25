package com.weike.ime.ime

/** A Char-keyed dictionary must not truncate supplementary Unicode code points. */
internal fun bmpPinyinCharacter(hex: String): Char? =
    hex.toIntOrNull(16)?.takeIf { it in 0..Char.MAX_VALUE.code }?.toChar()
