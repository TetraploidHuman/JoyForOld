package com.tetraploid.joyforold.preset

object PresetTextNormalizer {
    fun normalize(text: String): String {
        return text.trim()
            .lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[，,。；;：:！!？?、]"), "")
    }

    fun splitAliases(raw: String): List<String> {
        return raw.split('\n', '，', ',', '、', ';', '；')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }
}
