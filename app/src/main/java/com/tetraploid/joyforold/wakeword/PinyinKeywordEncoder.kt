package com.tetraploid.joyforold.wakeword

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

object PinyinKeywordEncoder {
    private val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        // 该 KWS 模型 tokens.txt 使用带声调符号的拼音（如 ǎo / óu / è）。
        // 必须与 tokens.txt 对齐，否则 sherpa-onnx 会报 Encode keywords failed 并 abort。
        toneType = HanyuPinyinToneType.WITH_TONE_MARK
        vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
    }

    private val initials = listOf(
        "zh", "ch", "sh",
        "b", "p", "m", "f", "d", "t", "n", "l",
        "g", "k", "h", "j", "q", "x",
        "r", "z", "c", "s", "y", "w",
    )

    fun encodeKeywordLine(
        keyword: String,
        keywordScore: Float = 3.0f,
        keywordThreshold: Float = 0.02f,
    ): String = encodeKeywordVariants(keyword, keywordScore, keywordThreshold).first()

    /**
     * 生成多条关键词变体，提高口音/轻声/连读时的召回率。
     * 同一唤醒词可写多行，sherpa-onnx 会取最优匹配。
     */
    fun encodeKeywordVariants(
        keyword: String,
        keywordScore: Float = 3.0f,
        keywordThreshold: Float = 0.02f,
    ): List<String> {
        val normalized = keyword.trim().replace("\\s+".toRegex(), "")
        require(normalized.isNotBlank()) { "唤醒词不能为空" }

        val lines = linkedSetOf<String>()
        val primary = encodeTokens(normalized, relaxedFinals = false)
        lines += buildLine(normalized, primary, keywordScore, keywordThreshold)

        val relaxed = encodeTokens(normalized, relaxedFinals = true)
        if (relaxed != primary) {
            lines += buildLine(
                normalized,
                relaxed,
                keywordScore + 0.5f,
                (keywordThreshold * 0.75f).coerceAtLeast(0.01f),
            )
        }

        polyphoneCombinations(normalized).take(3).forEach { tokens ->
            if (tokens != primary && tokens != relaxed) {
                lines += buildLine(
                    normalized,
                    tokens,
                    keywordScore + 0.25f,
                    (keywordThreshold * 0.85f).coerceAtLeast(0.01f),
                )
            }
        }

        return lines.toList()
    }

    fun validateTokens(line: String, modelTokens: Set<String>): Boolean {
        if (modelTokens.isEmpty()) return true
        val tokens = line.substringBefore(" @").split(" ").filter { it.isNotBlank() }
        return tokens.isNotEmpty() && tokens.all { it in modelTokens }
    }

    private fun buildLine(
        keyword: String,
        tokens: List<String>,
        keywordScore: Float,
        keywordThreshold: Float,
    ): String {
        return tokens.joinToString(" ") +
            " :$keywordScore #$keywordThreshold @${keyword.trim().replace(' ', '_')}"
    }

    private fun encodeTokens(keyword: String, relaxedFinals: Boolean): List<String> {
        return buildList {
            keyword.forEach { ch ->
                if (isChinese(ch)) {
                    val pinyin = PinyinHelper.toHanyuPinyinStringArray(ch, format)
                        ?.firstOrNull()
                        ?.trim()
                        ?.lowercase()
                    if (pinyin.isNullOrBlank()) {
                        add(ch.toString())
                    } else {
                        addAll(splitInitialFinal(pinyin, relaxedFinals))
                    }
                } else {
                    add(ch.lowercaseChar().toString())
                }
            }
        }
    }

    private fun polyphoneCombinations(keyword: String): List<List<String>> {
        val perCharOptions = keyword.map { ch ->
            if (!isChinese(ch)) {
                listOf(listOf(ch.lowercaseChar().toString()))
            } else {
                val readings = PinyinHelper.toHanyuPinyinStringArray(ch, format).orEmpty()
                    .map { it.trim().lowercase() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .map { splitInitialFinal(it, relaxedFinals = false) }
                    .ifEmpty { listOf(listOf(ch.toString())) }
                readings
            }
        }

        val results = mutableListOf<List<String>>()
        fun dfs(index: Int, current: MutableList<String>) {
            if (index >= perCharOptions.size) {
                results += current.toList()
                return
            }
            for (option in perCharOptions[index]) {
                current.addAll(option)
                dfs(index + 1, current)
                repeat(option.size) { current.removeAt(current.lastIndex) }
            }
        }
        dfs(0, mutableListOf())
        val primary = encodeTokens(keyword, relaxedFinals = false)
        return results.filter { it != primary }.distinct()
    }

    private fun splitInitialFinal(pinyin: String, relaxedFinals: Boolean): List<String> {
        val clean = normalizeToneMarks(
            pinyin.replace("ü", "u").replace("v", "u"),
        )
        val initial = initials.firstOrNull { clean.startsWith(it) }
        val final = if (initial == null || initial == clean) {
            clean
        } else {
            clean.removePrefix(initial)
        }
        val normalizedFinal = if (relaxedFinals) relaxFinalToken(final) else final
        return if (initial == null || initial == clean) {
            listOf(normalizedFinal)
        } else {
            listOf(initial, normalizedFinal)
        }
    }

    /** 仅将 tokens.txt 中确实存在的韵母做轻声降级（如 óu→ou, è→e）。 */
    private fun relaxFinalToken(final: String): String {
        return RELAXED_FINAL_MAP[final] ?: final
    }

    /**
     * sherpa-onnx 的部分中文 KWS tokens.txt 采用 caron（ǎěǐǒǔǚ）表示三声。
     * 但部分拼音库会输出 breve（ăĕĭŏŭ），需要做一次归一化，否则会出现
     * "Cannot find ID for token ăo" 并导致 KeywordSpotter 初始化失败。
     */
    private fun normalizeToneMarks(input: String): String {
        return input
            .replace('ă', 'ǎ')
            .replace('ĕ', 'ě')
            .replace('ĭ', 'ǐ')
            .replace('ŏ', 'ǒ')
            .replace('ŭ', 'ǔ')
            .replace('Ă', 'Ǎ')
            .replace('Ĕ', 'Ě')
            .replace('Ĭ', 'Ǐ')
            .replace('Ŏ', 'Ǒ')
            .replace('Ŭ', 'Ǔ')
    }

    private fun isChinese(ch: Char): Boolean {
        return Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN
    }

    private val RELAXED_FINAL_MAP = mapOf(
        "óu" to "ou",
        "ǒu" to "ou",
        "òu" to "ou",
        "ōu" to "ou",
        "è" to "e",
        "é" to "e",
        "ě" to "e",
        "ē" to "e",
    )
}

