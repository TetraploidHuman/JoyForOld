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
        keywordScore = 3.0f,
        keywordThreshold = 0.016f,
        confirmHits = 2,
        vadGateEnabled = false,
    ),
    SENSITIVE(
        label = "灵敏",
        keywordScore = 3.5f,
        keywordThreshold = 0.010f,
        confirmHits = 1,
        vadGateEnabled = false,
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
