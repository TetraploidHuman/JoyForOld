package com.tetraploid.joyforold.wakeword

import java.io.File

/**
 * CMU-style English phone lexicon used by sherpa-onnx phone+ppinyin KWS models.
 * Loads [en.phone] from the model directory and merges OOV overrides for wake phrases.
 */
class EnglishPhoneLexicon(private val lexiconFile: File?) {
    private val entries = mutableMapOf<String, List<String>>()

    init {
        lexiconFile?.takeIf { it.exists() }?.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEachLine
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < 2) return@forEachLine
            val word = parts.first().trim('\'', '"').uppercase()
            val phones = parts.drop(1).map { it.uppercase() }
            if (word.isNotBlank() && phones.isNotEmpty()) {
                entries.putIfAbsent(word, phones)
            }
        }
        OOV_OVERRIDES.forEach { (word, phones) ->
            entries[word.uppercase()] = phones
        }
    }

    fun lookup(word: String): List<String>? = entries[word.trim().uppercase()]

    companion object {
        /** Words absent from the bundled CMU lexicon but required for custom wake phrases. */
        val OOV_OVERRIDES: Map<String, List<String>> = mapOf(
            "HEY" to listOf("HH", "EY1"),
            "CORTANA" to listOf("K", "AO1", "R", "T", "AE1", "N", "AH0"),
        )
    }
}
