package com.tetraploid.joyforold.speech

/**
 * 将 ASR 结果按说话人档案做轻量纠正（唤醒词剥离 + 同音替换）。
 */
object AsrSpeakerAdaptation {
    fun adapt(
        recognized: String,
        wakePhrase: String?,
        corrections: Map<String, String>,
    ): String {
        var text = recognized.trim()
        if (text.isBlank()) return text

        wakePhrase?.trim()?.takeIf { it.isNotBlank() }?.let { phrase ->
            text = SpeechEchoFilter.stripEcho(text, listOf(phrase))
            text = stripLeadingWakeFragment(text, phrase)
        }

        if (corrections.isEmpty()) return text.trim()

        var changed = true
        var guard = 0
        while (changed && guard < 4) {
            changed = false
            guard++
            for ((wrong, right) in corrections) {
                if (wrong.isBlank() || right.isBlank()) continue
                if (text.contains(wrong)) {
                    text = text.replace(wrong, right)
                    changed = true
                }
            }
        }
        return text.trim()
    }

    private fun stripLeadingWakeFragment(text: String, wakePhrase: String): String {
        val normalized = normalize(text)
        val wakeNorm = normalize(wakePhrase)
        if (wakeNorm.isBlank()) return text
        if (normalized == wakeNorm) return ""
        if (normalized.startsWith(wakeNorm)) {
            return dropNormalizedPrefix(text, wakeNorm.length).trim()
        }
        return text
    }

    private fun dropNormalizedPrefix(original: String, normalizedPrefixLength: Int): String {
        val builder = StringBuilder()
        var normCount = 0
        for (ch in original) {
            if (normCount >= normalizedPrefixLength) {
                builder.append(ch)
            } else if (!isSkippable(ch)) {
                normCount++
            }
        }
        return builder.toString()
    }

    private fun normalize(text: String): String = buildString {
        text.lowercase().forEach { ch ->
            if (!isSkippable(ch)) append(ch)
        }
    }

    private val punctuation = Regex("""[\s，,。．.！!？?；;：:、\-—~"'「」『』（）()【】\[\]]+""")

    private fun isSkippable(ch: Char): Boolean = punctuation.matches(ch.toString())
}
