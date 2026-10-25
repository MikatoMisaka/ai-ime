package com.weike.ime.ai

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

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

        fun field(
            label: String,
            value: String,
            hint: String,
            password: Boolean = false,
            multiline: Boolean = false
        ): EditText {
            container.addView(TextView(activity).apply {
                text = label
                textSize = 13f
                setTextColor(C_MUTED)
                setPadding(0, dp(10), 0, dp(4))
            })
            return EditText(activity).apply {
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
            }.also { edit ->
                container.addView(edit, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
        }

        val baseUrl = field("接口地址", config.baseUrl, "https://api.deepseek.com/v1")
        val apiKey = field("API Key", config.apiKey, "sk-...", password = true)
        val model = field("模型名", config.model, "deepseek-chat / qwen-vl-max")
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

    private val C_TEXT = 0xFF2D3436.toInt()
    private val C_MUTED = 0xFF8D7E84.toInt()
}
