package com.weike.ime.ai

import java.util.UUID

/** One image attached to a chat message, already downscaled and JPEG-encoded. */
data class ChatImage(
    val base64: String,
    val mime: String = "image/jpeg",
    val previewUri: String? = null
)

/** A chat turn. Images count as part of the prompt, not as an OCR shortcut. */
data class ChatMessage(
    val role: String,
    val text: String,
    val images: List<ChatImage> = emptyList(),
    val failed: Boolean = false,
    /** Model reasoning, when the provider returns `reasoning_content`. */
    val reasoning: String = "",
    val id: String = UUID.randomUUID().toString()
) {
    val hasImages: Boolean get() = images.isNotEmpty()
}

/** Parsed assistant response: the answer plus an optional thinking block. */
data class ChatCompletion(
    val content: String,
    val reasoning: String = ""
)
