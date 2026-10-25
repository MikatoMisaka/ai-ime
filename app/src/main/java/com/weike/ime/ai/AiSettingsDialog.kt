package com.weike.ime.ai

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Shared "AI model" dialog used by the chat panel and the settings app. */
object AiSettingsDialog {

    fun show(activity: AppCompatActivity, onSaved: (AiChatConfig) -> Unit = {}) {
        val settings = AiChatSettings(activity)
        val config = settings.config()
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        fun createEdit(
            value: String,
            hint: String,
            password: Boolean = false,
            multiline: Boolean = false
        ): EditText = EditText(activity).apply {
            setText(value)
            this.hint = hint
            textSize = 14f
            setTextColor(C_TEXT)
            setHintTextColor(Color.rgb(0xBB, 0xB0, 0xB4))
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            maxLines = if (multiline) 5 else 1
            background = GradientDrawable().apply {
                setColor(Color.rgb(0xF4, 0xF1, 0xF2))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        fun label(text: String) {
            container.addView(TextView(activity).apply {
                this.text = text
                textSize = 13f
                setTextColor(C_MUTED)
                setPadding(0, dp(10), 0, dp(4))
            })
        }

        fun field(
            title: String,
            value: String,
            hint: String,
            password: Boolean = false,
            multiline: Boolean = false
        ): EditText {
            label(title)
            return createEdit(value, hint, password, multiline).also { edit ->
                container.addView(edit, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
        }

        val baseUrl = field("接口地址", config.baseUrl, "https://api.deepseek.com/v1")
        val apiKey = field("API Key", config.apiKey, "sk-...", password = true)

        // Model row: free text plus a remote model list fetched from the endpoint.
        label("模型名")
        val modelRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val model = createEdit(config.model, "deepseek-chat / qwen-vl-max")
        modelRow.addView(model, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val fetch = TextView(activity).apply {
            text = "获取模型"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(C_ACCENT)
            background = GradientDrawable().apply {
                setColor(Color.rgb(0xFF, 0xF0, 0xF5))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), dp(9), dp(12), dp(9))
            isClickable = true
        }
        modelRow.addView(fetch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(8) })
        container.addView(modelRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        fetch.setOnClickListener {
            val url = baseUrl.text.toString().trim()
            val key = apiKey.text.toString().trim()
            if (url.isBlank() || key.isBlank()) {
                toast(activity, "先填接口地址和 API Key")
                return@setOnClickListener
            }
            fetch.isEnabled = false
            fetch.text = "获取中…"
            activity.lifecycleScope.launch {
                val result = AiChatClient().listModels(url, key)
                fetch.isEnabled = true
                fetch.text = "获取模型"
                result.onSuccess { models ->
                    if (models.isEmpty()) {
                        toast(activity, "接口没有返回模型")
                        return@launch
                    }
                    AlertDialog.Builder(activity)
                        .setTitle("选择模型（${models.size}）")
                        .setItems(models.toTypedArray()) { _, which -> model.setText(models[which]) }
                        .setNegativeButton("取消", null)
                        .show()
                }.onFailure { toast(activity, it.message ?: "获取模型失败") }
            }
        }

        val systemPrompt = field("系统提示词", config.systemPrompt, "", multiline = true)
        val imagePrompt = field(
            "只发图片时的默认提示词",
            config.defaultImagePrompt,
            "",
            multiline = true
        )

        AlertDialog.Builder(activity)
            .setTitle("AI 模型设置")
            .setView(ScrollView(activity).apply { addView(container) })
            .setPositiveButton("保存") { _, _ ->
                settings.save(
                    baseUrl = baseUrl.text.toString(),
                    apiKey = apiKey.text.toString(),
                    model = model.text.toString(),
                    systemPrompt = systemPrompt.text.toString(),
                    defaultImagePrompt = imagePrompt.text.toString()
                )
                onSaved(settings.config())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(activity: AppCompatActivity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private val C_TEXT = 0xFF2D3436.toInt()
    private val C_MUTED = 0xFF8D7E84.toInt()
    private val C_ACCENT = 0xFFFF6B9D.toInt()
}
