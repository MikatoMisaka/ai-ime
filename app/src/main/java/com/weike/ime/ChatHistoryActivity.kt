package com.weike.ime

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.weike.ime.ai.ChatHistoryStore
import com.weike.ime.ai.HistoryConversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Read-only list of saved AI conversations, newest first. */
class ChatHistoryActivity : AppCompatActivity() {

    private val store by lazy { ChatHistoryStore(this) }
    private lateinit var listColumn: LinearLayout
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
            setBackgroundColor(C_BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "历史对话"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_TEXT)
        })
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(action("清空") { confirmClearAll() })
        header.addView(action("关闭") { finish() })
        column.addView(header)

        summary = TextView(this).apply {
            textSize = 13f
            setTextColor(C_MUTED)
            setPadding(0, dp(6), 0, dp(14))
        }
        column.addView(summary)

        listColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(listColumn)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(C_BG)
            addView(column)
        })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val items = runCatching { store.load() }.getOrDefault(emptyList())
        summary.text = if (items.isEmpty()) {
            "还没有记录。AI 面板里每次对话在退出后会自动保存在这里。"
        } else {
            "共 ${items.size} 段，只保留最近 30 段，图片以数量记录。"
        }
        listColumn.removeAllViews()
        if (items.isEmpty()) return
        items.forEach { conversation ->
            listColumn.addView(conversationCard(conversation))
        }
    }

    private fun conversationCard(conversation: HistoryConversation): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.WHITE, 16)
            isClickable = true
            setOnClickListener { showTranscript(conversation) }
            setOnLongClickListener {
                confirmDelete(conversation)
                true
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        card.addView(TextView(this).apply {
            text = conversation.title.ifBlank { "未命名对话" }
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_TEXT)
        })
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            .format(Date(conversation.updatedAt))
        card.addView(TextView(this).apply {
            text = "$time · ${conversation.messages.size} 条消息"
            textSize = 12f
            setTextColor(C_MUTED)
            setPadding(0, dp(6), 0, 0)
        })
        return card
    }

    private fun showTranscript(conversation: HistoryConversation) {
        val transcript = conversation.messages.joinToString("\n\n") { message ->
            val who = if (message.role == "user") "我" else "AI"
            val images = if (message.imageCount > 0) "［${message.imageCount} 张图片］\n" else ""
            "$who：$images${message.text}"
        }
        AlertDialog.Builder(this)
            .setTitle(conversation.title.ifBlank { "未命名对话" })
            .setMessage(transcript)
            .setPositiveButton("关闭", null)
            .setNeutralButton("删除") { _, _ -> confirmDelete(conversation) }
            .show()
    }

    private fun confirmDelete(conversation: HistoryConversation) {
        AlertDialog.Builder(this)
            .setTitle("删除这段对话？")
            .setMessage(conversation.title.ifBlank { "未命名对话" })
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { store.delete(conversation.id) }
                    refresh()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearAll() {
        if (runCatching { store.load() }.getOrDefault(emptyList()).isEmpty()) {
            toast("没有可清空的记录")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清空全部历史？")
            .setMessage("此操作不可撤销。")
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { store.clear() }
                    refresh()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun action(label: String, click: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(C_ACCENT)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        isClickable = true
        setOnClickListener { click() }
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val C_BG = 0xFFF5F5F7.toInt()
        private val C_TEXT = 0xFF2D3436.toInt()
        private val C_MUTED = 0xFF8D7E84.toInt()
        private val C_ACCENT = 0xFFFF6B9D.toInt()
    }
}
