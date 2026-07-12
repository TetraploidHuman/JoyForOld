package com.tetraploid.joyforold.speech

import android.content.Context
import org.json.JSONObject

/**
 * 说话人适应：存储唤醒词校准与常见 ASR 误识别纠正映射。
 */
class AsrSpeakerProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadCorrections(): Map<String, String> {
        val raw = prefs.getString(KEY_CORRECTIONS, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key ->
                    val value = json.optString(key).trim()
                    if (key.isNotBlank() && value.isNotBlank()) put(key, value)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun rememberWakePhrase(phrase: String) {
        val trimmed = phrase.trim()
        if (trimmed.isBlank()) return
        prefs.edit().putString(KEY_WAKE_PHRASE, trimmed).apply()
    }

    fun wakePhrase(): String = prefs.getString(KEY_WAKE_PHRASE, "").orEmpty()

    fun recordCalibrationPhrase(correctPhrase: String) {
        rememberWakePhrase(correctPhrase)
        val variants = phoneticVariants(correctPhrase)
        val merged = loadCorrections().toMutableMap()
        variants.forEach { variant ->
            if (variant != correctPhrase) merged[variant] = correctPhrase
        }
        saveCorrections(merged)
    }

    fun addCorrection(wrong: String, right: String) {
        val w = wrong.trim()
        val r = right.trim()
        if (w.isBlank() || r.isBlank() || w == r) return
        val merged = loadCorrections().toMutableMap()
        merged[w] = r
        saveCorrections(merged)
    }

    private fun saveCorrections(map: Map<String, String>) {
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k, v) }
        prefs.edit().putString(KEY_CORRECTIONS, json.toString()).apply()
    }

    private fun phoneticVariants(phrase: String): List<String> {
        val variants = mutableListOf(phrase)
        val spaced = phrase.toCharArray().joinToString(" ")
        variants += spaced
        if (phrase.length >= 2) {
            variants += phrase.substring(0, 2)
        }
        return variants.distinct()
    }

    companion object {
        private const val PREFS_NAME = "joy_asr_speaker_profile"
        private const val KEY_CORRECTIONS = "corrections_json"
        private const val KEY_WAKE_PHRASE = "wake_phrase"
    }
}
