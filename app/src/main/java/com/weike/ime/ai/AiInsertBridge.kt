package com.weike.ime.ai

/**
 * Hands generated text from the chat activity back to the IME service.
 *
 * The chat page runs in the same process, so a simple synchronized slot is
 * enough; the broadcast only nudges the service to consume it right away. If
 * the input connection is gone by then (the chat page covered the host app),
 * the service keeps the text and commits it when the keyboard is shown again.
 */
object AiInsertBridge {
    const val ACTION_AI_INSERT = "com.weike.ime.action.AI_INSERT"

    @Volatile
    private var pending: String? = null

    fun offer(text: String) {
        if (text.isBlank()) return
        synchronized(this) { pending = text }
    }

    fun consume(): String? = synchronized(this) {
        val value = pending
        pending = null
        value
    }

    fun hasPending(): Boolean = synchronized(this) { pending != null }
}
