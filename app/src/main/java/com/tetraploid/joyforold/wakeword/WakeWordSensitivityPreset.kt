package com.tetraploid.joyforold.wakeword

enum class WakeWordSensitivityPreset(
    val label: String,
    val keywordScore: Float,
    val keywordThreshold: Float,
    val confirmHits: Int,
    val vadGateEnabled: Boolean,
) {
    BALANCED(
        label = "平衡",
        keywordScore = 2.8f,
        keywordThreshold = 0.019f,
        confirmHits = 2,
        vadGateEnabled = true,
    ),
    SENSITIVE(
        label = "灵敏",
        keywordScore = 3.2f,
        keywordThreshold = 0.014f,
        confirmHits = 2,
        vadGateEnabled = true,
    ),
    STRICT(
        label = "防误触",
        keywordScore = 2.5f,
        keywordThreshold = 0.022f,
        confirmHits = 2,
        vadGateEnabled = true,
    ),
    ;

    companion object {
        fun fromId(id: String?): WakeWordSensitivityPreset {
            return entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: BALANCED
        }
    }
}

