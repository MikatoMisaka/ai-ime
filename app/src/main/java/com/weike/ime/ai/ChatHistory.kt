package com.weike.ime.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One stored turn. Images are kept as a count: base64 would bloat the file. */
data class HistoryMessage(
    val role: String,
    val text: String,
    val imageCount: Int = 0,
    val failed: Boolean = false
)

data class HistoryConversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<HistoryMessage>
)

/**
 * Conversation history in a small JSON file under app-private storage.
 *
 * Deliberately simple: the panel writes at most a couple of times per exchange
 * and the list stays capped, so a database would be overkill here.
 */
class ChatHistoryStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = Any()

    fun load(): List<HistoryConversation> = synchronized(lock) {
        readUnlocked()
    }

    fun upsert(conversation: HistoryConversation) = synchronized(lock) {
        val list = readUnlocked()
            .filterNot { it.id == conversation.id }
            .toMutableList()
            .apply { add(conversation) }
        writeUnlocked(
            list.sortedByDescending { it.updatedAt }.take(MAX_CONVERSATIONS)
        )
    }

    fun delete(id: String) = synchronized(lock) {
        writeUnlocked(readUnlocked().filterNot { it.id == id })
    }

    fun clear() = synchronized(lock) {
        runCatching { file.delete() }
        Unit
    }

    private fun readUnlocked(): List<HistoryConversation> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val messages = json.optJSONArray("messages") ?: JSONArray()
                    add(
                        HistoryConversation(
                            id = json.optString("id"),
                            title = json.optString("title"),
                            updatedAt = json.optLong("updatedAt"),
                            messages = buildList {
                                for (m in 0 until messages.length()) {
                                    val item = messages.optJSONObject(m) ?: continue
                                    add(
                                        HistoryMessage(
                                            role = item.optString("role"),
                                            text = item.optString("text").take(MAX_TEXT),
                                            imageCount = item.optInt("images"),
                                            failed = item.optBoolean("failed")
                                        )
                                    )
                                }
                            }
                        )
                    )
                }
            }.filter { it.id.isNotBlank() && it.messages.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    private fun writeUnlocked(list: List<HistoryConversation>) {
        runCatching {
            val array = JSONArray()
            list.forEach { conversation ->
                val messages = JSONArray()
                conversation.messages.take(MAX_MESSAGES).forEach { message ->
                    messages.put(
                        JSONObject()
                            .put("role", message.role)
                            .put("text", message.text.take(MAX_TEXT))
                            .put("images", message.imageCount)
                            .put("failed", message.failed)
                    )
                }
                array.put(
                    JSONObject()
                        .put("id", conversation.id)
                        .put("title", conversation.title)
                        .put("updatedAt", conversation.updatedAt)
                        .put("messages", messages)
                )
            }
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(file)) {
                file.writeText(array.toString())
                temp.delete()
            }
        }
    }

    companion object {
        private const val FILE_NAME = "ai_chat_history.json"
        private const val MAX_CONVERSATIONS = 30
        private const val MAX_MESSAGES = 40
        private const val MAX_TEXT = 2000
    }
}
