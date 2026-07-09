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

    companion object {
        private const val PREFS_NAME = "joy_for_old_prefs"
        private const val KEY_ENABLED = "wake_word_enabled"
        private const val KEY_PHRASE = "wake_word_phrase"
        private const val KEY_KEYWORD_SCORE = "wake_word_keyword_score"
        private const val KEY_KEYWORD_THRESHOLD = "wake_word_keyword_threshold"
        const val DEFAULT_PHRASE = "老头乐"
        const val DEFAULT_KEYWORD_SCORE = 3.0f
        const val DEFAULT_KEYWORD_THRESHOLD = 0.02f
    }
}

