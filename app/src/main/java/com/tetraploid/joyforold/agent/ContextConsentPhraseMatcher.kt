package com.tetraploid.joyforold.agent

object ContextConsentPhraseMatcher {
    enum class Intent {
        GRANT,
        DENY,
        UNCLEAR,
    }

    private val denyPhrases = listOf(
        "不同意", "不要", "取消", "不可以", "不行", "不用", "算了",
    )

    private val grantPhrases = listOf(
        "同意", "可以", "好的", "好", "行", "没问题",
    )

    fun classify(text: String): Intent {
        val normalized = text.trim()
        if (normalized.isBlank()) return Intent.UNCLEAR
        if (denyPhrases.any { normalized.contains(it) }) return Intent.DENY
        if (normalized.contains('不')) return Intent.UNCLEAR
        if (grantPhrases.any { normalized.contains(it) }) return Intent.GRANT
        return Intent.UNCLEAR
    }
}
