package com.tetraploid.joyforold.wakeword

enum class WakeWordSensitivityPreset(
    val label: String,
    val keywordScore: Float,
    val keywordThreshold: Float,
    val secondStageThreshold: Float,
    val confirmHits: Int,
    val vadGateEnabled: Boolean,
) {
    BALANCED(
        label = "平衡",
        keywordScore = 3.0f,
        keywordThreshold = 0.012f,
        secondStageThreshold = 0.020f,
        confirmHits = 1,
        vadGateEnabled = true,
    ),
    SENSITIVE(
        label = "灵敏",
        keywordScore = 3.5f,
        keywordThreshold = 0.009f,
        secondStageThreshold = 0.016f,
        confirmHits = 1,
        vadGateEnabled = true,
    ),
    STRICT(
        label = "防误触",
        keywordScore = 2.5f,
        keywordThreshold = 0.016f,
        secondStageThreshold = 0.028f,
        confirmHits = 1,
        vadGateEnabled = true,
    ),
    ;

    companion object {
        fun fromId(id: String?): WakeWordSensitivityPreset {
            return entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: BALANCED
        }
    }
}
