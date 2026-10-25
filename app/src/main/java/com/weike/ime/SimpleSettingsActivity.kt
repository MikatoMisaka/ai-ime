package com.weike.ime

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.weike.ime.ai.AiChatSettings
import com.weike.ime.ai.AiSettingsDialog
import com.weike.ime.data.AppSettingsRepository
import kotlinx.coroutines.launch

/**
 * Minimal settings app: enable/switch the keyboard and configure the AI model.
 * The old romance/reply/subscription screens are no longer linked from here.
 */
class SimpleSettingsActivity : AppCompatActivity() {

    private lateinit var modelSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
            setBackgroundColor(C_BG)
        }

        column.addView(TextView(this).apply {
            text = "AI 输入法"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_TEXT)
        })
        column.addView(TextView(this).apply {
            text = "26 键中文输入 + 内置 AI 助手"
            textSize = 14f
            setTextColor(C_MUTED)
            setPadding(0, dp(6), 0, dp(20))
        })

        column.addView(card(
            "输入法",
            "如果键盘列表里还没有它，先点「启用」，再点「切换」。"
        ) {
            row(
                action("启用输入法") {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    }.onFailure { toast("请到系统设置 → 语言与输入法里手动启用") }
                },
                action("切换输入法") {
                    runCatching {
                        getSystemService(InputMethodManager::class.java)
                            ?.showInputMethodPicker()
                    }.onFailure { toast("请长按空格键或点键盘左下角地球切换") }
                }
            )
        })

        val settingsRepository = AppSettingsRepository(this)
        val soundSwitch = SwitchCompat(this).apply {
            text = "按键音效"
            textSize = 15f
            setTextColor(C_TEXT)
        }
        column.addView(card("按键反馈", "默认关闭。开启后使用系统按键音，不拖慢手感。") {
            addView(soundSwitch)
        })
        lifecycleScope.launch {
            soundSwitch.isChecked = runCatching {
                settingsRepository.keyboardSoundVolume() > 0f
            }.getOrDefault(false)
            soundSwitch.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch {
                    runCatching {
                        settingsRepository.saveKeyboardSoundVolume(if (checked) 0.5f else 0f)
                    }
                }
            }
        }

        modelSummary = TextView(this).apply {
            textSize = 13f
            setTextColor(C_MUTED)
            setPadding(0, dp(4), 0, dp(12))
        }
        column.addView(card("AI 模型", "") {
            addView(modelSummary)
            addView(action("配置模型") {
                AiSettingsDialog.show(this@SimpleSettingsActivity) { refreshModelSummary() }
            })
        })

        column.addView(card("怎么用", "") {
            addView(bodyText(
                "1. 在微信等任意输入框里切换到本输入法\n" +
                    "2. 点键盘顶部右侧的粉色 AI\n" +
                    "3. 半屏面板里输入问题，或选图片一起发\n" +
                    "4. 回答出来后点「插入」，文字回到输入框"
            ))
        })

        column.addView(card("开源许可", "本项目按 GPL-3.0-or-later 分发，完整源码随仓库提供。") {
            addView(action("查看许可与第三方声明") {
                startActivity(Intent(this@SimpleSettingsActivity, LicenseActivity::class.java))
            })
        })

        column.addView(TextView(this).apply {
            val version = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull().orEmpty()
            text = "版本 $version"
            textSize = 12f
            setTextColor(C_MUTED)
            setPadding(0, dp(10), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(column) })
        refreshModelSummary()
    }

    override fun onResume() {
        super.onResume()
        refreshModelSummary()
    }

    private fun refreshModelSummary() {
        val config = AiChatSettings(this).config()
        modelSummary.text = if (config.isComplete()) {
            "当前：${config.model}\n${config.baseUrl}"
        } else {
            "还没配置模型，点下面「配置模型」填写接口地址、Key 和模型名。"
        }
    }

    private fun card(title: String, subtitle: String, content: LinearLayout.() -> Unit): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(C_TEXT)
        })
        if (subtitle.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = subtitle
                textSize = 13f
                setTextColor(C_MUTED)
                setPadding(0, dp(6), 0, dp(10))
            })
        }
        card.content()
        return card
    }

    private fun row(vararg views: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach {
                addView(it)
                (it.layoutParams as LinearLayout.LayoutParams).rightMargin = dp(10)
            }
        }

    private fun action(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(C_ACCENT)
            cornerRadius = dp(20).toFloat()
        }
        setPadding(dp(18), dp(11), dp(18), dp(11))
        isClickable = true
    }

    private fun action(label: String, click: () -> Unit): TextView =
        action(label).apply { setOnClickListener { click() } }

    private fun bodyText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(C_TEXT)
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(0, dp(8), 0, 0)
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
