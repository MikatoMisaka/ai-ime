package com.weike.ime.text

/** Keeps the core suggestion flow usable when the configured model is offline. */
object SuggestionFallback {
    fun replies(message: String): List<String> {
        val asksQuestion = message.trim().endsWith("?") || message.contains('？') ||
            Regex("吗|呢|要不要|可不可以|有空").containsMatchIn(message)
        return if (asksQuestion) {
            listOf(
                "可以呀，你具体想怎么安排？",
                "好呀，听起来不错～",
                "我看到啦，让我想一下再认真回复你。"
            )
        } else {
            listOf(
                "收到，我明白你的意思了。",
                "好呀，我们可以再聊聊～",
                "我看到啦，晚点认真回复你。"
            )
        }
    }

    fun polishes(text: String, relation: String): List<String> {
        val source = text.trim().trimEnd('。', '！', '？', '!', '?', '～', '~')
        if (source.length <= 2) {
            return listOf("想和你聊聊～", "刚刚想到你了。", "有件事想认真和你说。")
        }
        val formal = relation in setOf("客户", "上司", "同事")
        return listOf(
            "$source。",
            "$source～",
            if (formal) "你好，$source。" else "其实我想说，$source。"
        ).distinct().take(3)
    }
}
