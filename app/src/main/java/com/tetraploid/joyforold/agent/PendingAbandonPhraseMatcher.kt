package com.tetraploid.joyforold.agent

/**
 * 未完成任务放弃/继续 口语识别。
 */
object PendingAbandonPhraseMatcher {
    enum class Intent {
        ABANDON,
        CONTINUE,
        UNCLEAR,
    }

    private val abandonPhrases = listOf(
        "放弃", "不要了", "算了", "新的", "重新开始", "不用了", "取消旧的", "不管了",
    )

    private val continuePhrases = listOf(
        "继续", "接着", "先完成", "完成旧的", "不要放弃", "还要", "原来的",
    )

    fun classify(utterance: String): Intent {
        val text = utterance.trim()
        if (text.isBlank()) return Intent.UNCLEAR
        if (abandonPhrases.any { text.contains(it) }) return Intent.ABANDON
        if (continuePhrases.any { text.contains(it) }) return Intent.CONTINUE
        if (VoiceConfirmPhraseMatcher.classify(text) == VoiceConfirmPhraseMatcher.Intent.CANCEL) {
            return Intent.ABANDON
        }
        if (VoiceConfirmPhraseMatcher.classify(text) == VoiceConfirmPhraseMatcher.Intent.CONFIRM) {
            return Intent.CONTINUE
        }
        return Intent.UNCLEAR
    }
}
