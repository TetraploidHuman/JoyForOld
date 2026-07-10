package com.tetraploid.joyforold.wakeword

/**
 * Encodes wake phrases for sherpa-onnx zh-en KWS (phone+ppinyin).
 * Chinese segments use [PinyinKeywordEncoder]; English words use CMU phones from [EnglishPhoneLexicon].
 */
object SherpaKeywordEncoder {
    fun encodeKeywordVariants(
        keyword: String,
        lexicon: EnglishPhoneLexicon,
        keywordScore: Float = 3.0f,
        keywordThreshold: Float = 0.02f,
    ): List<String> {
        val normalized = keyword.trim()
        require(normalized.isNotBlank()) { "唤醒词不能为空" }

        return when {
            containsChinese(normalized) && !containsLatinLetters(normalized) -> {
                PinyinKeywordEncoder.encodeKeywordVariants(normalized, keywordScore, keywordThreshold)
            }
            isEnglishPhrase(normalized) -> {
                encodeEnglishVariants(normalized, lexicon, keywordScore, keywordThreshold)
            }
            else -> {
                encodeMixedVariants(normalized, lexicon, keywordScore, keywordThreshold)
            }
        }
    }

    fun validateTokens(line: String, modelTokens: Set<String>): Boolean =
        KeywordTokenValidator.validateLine(line, modelTokens)

    private fun encodeEnglishVariants(
        keyword: String,
        lexicon: EnglishPhoneLexicon,
        keywordScore: Float,
        keywordThreshold: Float,
    ): List<String> {
        val label = keyword.trim()
        val words = splitEnglishWords(label)
        require(words.isNotEmpty()) { "英文唤醒词不能为空" }

        val lines = linkedSetOf<String>()
        val primaryPhones = words.flatMap { word ->
            lexicon.lookup(word) ?: error("英文词「$word」不在发音词典中")
        }
        lines += buildLine(label, primaryPhones, keywordScore, keywordThreshold)

        if (words.size == 1) return lines.toList()

        val compactLabel = words.joinToString("_")
        if (compactLabel != label) {
            lines += buildLine(compactLabel, primaryPhones, keywordScore + 0.25f, keywordThreshold)
        }

        val relaxedThreshold = (keywordThreshold * 0.8f).coerceAtLeast(0.008f)
        lines += buildLine(label, primaryPhones, keywordScore + 0.5f, relaxedThreshold)

        return lines.toList()
    }

    private fun encodeMixedVariants(
        keyword: String,
        lexicon: EnglishPhoneLexicon,
        keywordScore: Float,
        keywordThreshold: Float,
    ): List<String> {
        val tokens = mutableListOf<String>()
        val segments = splitMixedSegments(keyword)
        require(segments.isNotEmpty()) { "唤醒词编码失败" }
        for (segment in segments) {
            if (containsChinese(segment)) {
                val pinyinTokens = PinyinKeywordEncoder.encodeKeywordVariants(
                    segment,
                    keywordScore,
                    keywordThreshold,
                ).first().substringBefore(" @").split(" ").filter { it.isNotBlank() }
                tokens += pinyinTokens
            } else {
                splitEnglishWords(segment).forEach { word ->
                    tokens += lexicon.lookup(word)
                        ?: error("英文词「$word」不在发音词典中")
                }
            }
        }
        return listOf(buildLine(keyword.trim(), tokens, keywordScore, keywordThreshold))
    }

    private fun buildLine(
        label: String,
        tokens: List<String>,
        keywordScore: Float,
        keywordThreshold: Float,
    ): String {
        return tokens.joinToString(
            separator = " ",
            postfix = " :$keywordScore #$keywordThreshold @$label",
        )
    }

    private fun splitEnglishWords(keyword: String): List<String> {
        return keyword
            .replace(",", " ")
            .replace("_", " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { it.trim('\'', '"') }
    }

    private fun splitMixedSegments(keyword: String): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        keyword.forEach { ch ->
            when {
                isChinese(ch) -> {
                    if (current.isNotEmpty() && !isChinese(current.last())) {
                        segments += current.toString()
                        current.clear()
                    }
                    current.append(ch)
                }
                ch.isLetter() -> {
                    if (current.isNotEmpty() && isChinese(current.last())) {
                        segments += current.toString()
                        current.clear()
                    }
                    current.append(ch)
                }
                ch == ',' || ch == ' ' || ch == '_' -> {
                    if (current.isNotEmpty()) {
                        segments += current.toString()
                        current.clear()
                    }
                }
            }
        }
        if (current.isNotEmpty()) segments += current.toString()
        return segments.filter { it.isNotBlank() }
    }

    private fun isEnglishPhrase(keyword: String): Boolean {
        val letters = keyword.count { it.isLetter() }
        val chinese = keyword.count { isChinese(it) }
        return letters > 0 && chinese == 0
    }

    private fun containsChinese(text: String): Boolean = text.any { isChinese(it) }

    private fun containsLatinLetters(text: String): Boolean = text.any { it.isLetter() && !isChinese(it) }

    private fun isChinese(ch: Char): Boolean =
        Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN
}
