package com.tetraploid.joyforold.speech

/**
 * Strips TTS prompt echo from ASR results (speaker picked up by mic).
 */
object SpeechEchoFilter {
    private val punctuation = Regex("""[\s，,。．.！!？?；;：:、\-—~"'「」『』（）()【】\[\]]+""")

    fun stripEcho(recognized: String, recentPrompts: List<String>): String {
        var text = recognized.trim()
        if (text.isBlank()) return text

        for (prompt in recentPrompts) {
            val cleaned = prompt.trim()
            if (cleaned.isBlank()) continue
            text = stripSingleEcho(text, cleaned)
        }
        return text.trim()
    }

    private fun stripSingleEcho(recognized: String, prompt: String): String {
        val normRecognized = normalize(recognized)
        val normPrompt = normalize(prompt)
        if (normPrompt.isBlank()) return recognized

        if (normRecognized == normPrompt) return ""
        if (normRecognized.startsWith(normPrompt)) {
            return dropNormalizedPrefix(recognized, normPrompt.length).trim()
        }
        if (normRecognized.contains(normPrompt)) {
            return recognized.replace(prompt, "", ignoreCase = true).trim()
        }
        return recognized
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

    private fun normalize(text: String): String {
        return buildString {
            text.lowercase().forEach { ch ->
                if (!isSkippable(ch)) append(ch)
            }
        }
    }

    private fun isSkippable(ch: Char): Boolean = punctuation.matches(ch.toString())
}
