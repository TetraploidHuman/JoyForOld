package com.tetraploid.joyforold.wakeword

import android.content.Context

class WakeWordConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun saveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getPhrase(): String = prefs.getString(KEY_PHRASE, DEFAULT_PHRASE).orEmpty().ifBlank { DEFAULT_PHRASE }

    fun savePhrase(phrase: String) {
        prefs.edit().putString(KEY_PHRASE, phrase.trim()).apply()
    }

    fun getKeywordScore(): Float = prefs.getFloat(KEY_KEYWORD_SCORE, DEFAULT_KEYWORD_SCORE)

    fun saveKeywordScore(value: Float) {
        prefs.edit().putFloat(KEY_KEYWORD_SCORE, value.coerceIn(0.1f, 10f)).apply()
    }

    fun getKeywordThreshold(): Float = prefs.getFloat(KEY_KEYWORD_THRESHOLD, DEFAULT_KEYWORD_THRESHOLD)

    fun saveKeywordThreshold(value: Float) {
        prefs.edit().putFloat(KEY_KEYWORD_THRESHOLD, value.coerceIn(0.01f, 5f)).apply()
    }

    fun getConfirmHitCount(): Int = prefs.getInt(KEY_CONFIRM_HITS, DEFAULT_CONFIRM_HITS)

    fun saveConfirmHitCount(value: Int) {
        prefs.edit().putInt(KEY_CONFIRM_HITS, value.coerceIn(1, 3)).apply()
    }

    fun isVadGateEnabled(): Boolean = prefs.getBoolean(KEY_VAD_GATE, DEFAULT_VAD_GATE)

    fun saveVadGateEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VAD_GATE, enabled).apply()
    }

    fun isSileroVadEnabled(): Boolean = prefs.getBoolean(KEY_SILERO_VAD, DEFAULT_SILERO_VAD)

    fun saveSileroVadEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SILERO_VAD, enabled).apply()
    }

    fun isSecondStageEnabled(): Boolean = prefs.getBoolean(KEY_SECOND_STAGE, DEFAULT_SECOND_STAGE)

    fun saveSecondStageEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SECOND_STAGE, enabled).apply()
    }

    fun isCalibrated(): Boolean = prefs.getBoolean(KEY_CALIBRATED, false)

    fun saveCalibrated(calibrated: Boolean) {
        prefs.edit().putBoolean(KEY_CALIBRATED, calibrated).apply()
    }

    fun getPreset(): WakeWordSensitivityPreset =
        WakeWordSensitivityPreset.fromId(prefs.getString(KEY_PRESET, WakeWordSensitivityPreset.BALANCED.name))

    fun savePreset(preset: WakeWordSensitivityPreset) {
        prefs.edit()
            .putString(KEY_PRESET, preset.name)
            .putFloat(KEY_KEYWORD_SCORE, preset.keywordScore)
            .putFloat(KEY_KEYWORD_THRESHOLD, preset.keywordThreshold)
            .putInt(KEY_CONFIRM_HITS, preset.confirmHits)
            .putBoolean(KEY_VAD_GATE, preset.vadGateEnabled)
            .apply()
    }

    fun applyPreset(preset: WakeWordSensitivityPreset) = savePreset(preset)

    companion object {
        private const val PREFS_NAME = "joy_for_old_prefs"
        private const val KEY_ENABLED = "wake_word_enabled"
        private const val KEY_PHRASE = "wake_word_phrase"
        private const val KEY_KEYWORD_SCORE = "wake_word_keyword_score"
        private const val KEY_KEYWORD_THRESHOLD = "wake_word_keyword_threshold"
        private const val KEY_CONFIRM_HITS = "wake_word_confirm_hits"
        private const val KEY_VAD_GATE = "wake_word_vad_gate"
        private const val KEY_SILERO_VAD = "wake_word_silero_vad"
        private const val KEY_SECOND_STAGE = "wake_word_second_stage"
        private const val KEY_CALIBRATED = "wake_word_calibrated"
        private const val KEY_PRESET = "wake_word_preset"
        const val DEFAULT_PHRASE = "Hey,Cortana"
        val DEFAULT_KEYWORD_SCORE = WakeWordSensitivityPreset.BALANCED.keywordScore
        val DEFAULT_KEYWORD_THRESHOLD = WakeWordSensitivityPreset.BALANCED.keywordThreshold
        val DEFAULT_CONFIRM_HITS = WakeWordSensitivityPreset.BALANCED.confirmHits
        const val DEFAULT_VAD_GATE = true
        const val DEFAULT_SILERO_VAD = true
        const val DEFAULT_SECOND_STAGE = true
    }
}
