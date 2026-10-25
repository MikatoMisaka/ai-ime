package com.weike.ime

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * GPL-3.0 requires the license and third-party notices to travel with the
 * binary; this screen shows both, plus what this fork changed.
 */
class LicenseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = buildString {
            appendLine("AI 输入法（AI IME）")
            appendLine("Copyright (C) 2026 AI IME contributors")
            appendLine()
            appendLine("本项目是 LianAi Keyboard（恋爱键盘）的修改版，后者基于 Vertick IME。")
            appendLine("按 GNU General Public License v3.0 or later 分发。")
            appendLine()
            appendLine("本修改版的主要变更：")
            appendLine("· 移除订阅、会员、账号与内置“帮你回/超会说”入口")
            appendLine("· 新增可配置的 OpenAI 兼容 AI 对话面板（含图片上下文）")
            appendLine("· 中文键盘改为 26 键、隐藏数字行、调整键面比例")
            appendLine("· 包名与界面文案调整")
            appendLine()
            appendLine("完整对应源码：https://github.com/MikatoMisaka/ai-ime")
            appendLine("修改日期：2026-10。")
            appendLine()
            appendLine("────────────────────────────")
            appendLine()
            appendLine(readAsset("licenses/THIRD_PARTY_NOTICES.txt"))
            appendLine()
            appendLine("────────────────────────────")
            appendLine()
            appendLine(readAsset("licenses/GPL-3.0.txt"))
        }

        val text = TextView(this).apply {
            this.text = body
            textSize = 12f
            setTextColor(0xFF2D3436.toInt())
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(text)
        })
    }

    private fun readAsset(path: String): String = runCatching {
        assets.open(path).bufferedReader().use { it.readText() }
    }.getOrDefault("（无法读取 $path）")

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
