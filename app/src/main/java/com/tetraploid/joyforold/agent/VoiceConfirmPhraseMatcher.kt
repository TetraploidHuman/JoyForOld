package com.tetraploid.joyforold.agent

/**
 * 语音确认/取消意图识别（参考 SightSync ConfirmationManager，适配中文口语）。
 */
object VoiceConfirmPhraseMatcher {
    enum class Intent {
        CONFIRM,
        CANCEL,
        UNCLEAR,
    }

    private val confirmPhrases = listOf(
        "确认", "确定", "好的", "好", "行", "可以", "没问题", "对", "嗯", "是",
        "发送", "发吧", "打吧", "拨吧", "执行", "继续", "弄吧", "去吧",
    )

    private val cancelPhrases = listOf(
        "取消", "不用", "不要", "停止", "算了", "别", "不了", "不做了", "不要了",
        "别弄", "别发", "别打", "不发送", "不打了",
    )

    fun classify(utterance: String): Intent {
        val text = utterance.trim()
        if (text.isBlank()) return Intent.UNCLEAR
        if (cancelPhrases.any { text.contains(it) }) return Intent.CANCEL
        if (text.contains('不')) return Intent.UNCLEAR
        if (confirmPhrases.any { text.contains(it) }) return Intent.CONFIRM
        return Intent.UNCLEAR
    }
}
