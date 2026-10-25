package com.weike.ime.ime

import android.content.Context
import android.media.AudioManager

/**
 * Key click through the platform keypress effect.
 *
 * The previous SoundPool implementation used a single stream and called stop()
 * before every play(), which truncated the sample's tail on fast typing and
 * added an asynchronous stop to each press. The platform effect has no decode
 * or seek step, so the click lands with the key instead of trailing it.
 */
class KeyboardSoundPlayer(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun play(volume: Float) {
        val resolved = volume.coerceIn(0f, 1f)
        if (resolved <= 0f) return
        runCatching {
            audioManager?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, resolved)
        }
    }

    fun release() = Unit
}
