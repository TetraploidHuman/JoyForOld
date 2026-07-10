package com.tetraploid.joyforold.preset

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PresetCommand(
    val name: String,
    val aliases: List<String>,
    val action: String,
    val target: String = "",
    val extra: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("aliases", JSONArray(aliases))
        put("action", action)
        put("target", target)
        put("extra", extra)
    }

    companion object {
        fun fromJson(json: JSONObject): PresetCommand = PresetCommand(
            name = json.optString("name").ifBlank { json.optString("phrase") },
            aliases = json.optJSONArray("aliases")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) add(arr.optString(i))
                }
            } ?: listOf(json.optString("phrase")).filter { it.isNotBlank() },
            action = json.optString("action"),
            target = json.optString("target"),
            extra = json.optString("extra"),
        )
    }
}

class PresetCommandStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun ensureSeededDefaults() {
        if (!prefs.contains(KEY_PRESETS)) {
            savePresets(DEFAULT_PRESETS)
        }
    }

    fun loadPresets(): List<PresetCommand> {
        val raw = prefs.getString(KEY_PRESETS, null).orEmpty()
        if (raw.isBlank()) return DEFAULT_PRESETS
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(PresetCommand.fromJson(arr.getJSONObject(i)))
                }
            }.filter { it.aliases.isNotEmpty() && it.action.isNotBlank() }
        }.getOrDefault(DEFAULT_PRESETS)
    }

    fun savePresets(presets: List<PresetCommand>) {
        val arr = JSONArray()
        presets.filter { it.aliases.isNotEmpty() && it.action.isNotBlank() }.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_PRESETS, arr.toString()).apply()
    }

    fun findByPhrase(command: String): PresetCommand? {
        val normalized = PresetTextNormalizer.normalize(command)
        if (normalized.isBlank()) return null
        return loadPresets().firstOrNull { preset ->
            preset.aliases.any { PresetTextNormalizer.normalize(it) == normalized }
        }
    }

    companion object {
        private const val PREFS_NAME = "joy_for_old_prefs"
        private const val KEY_PRESETS = "preset_commands"

        val DEFAULT_PRESETS = listOf(
            PresetCommand(
                name = "回家导航",
                aliases = listOf("我要回家", "导航回家", "送我回家"),
                action = "navigate_home",
            ),
        )
    }
}
