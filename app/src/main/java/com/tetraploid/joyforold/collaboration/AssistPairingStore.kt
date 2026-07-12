package com.tetraploid.joyforold.collaboration

import android.content.Context
import com.tetraploid.joyforold.assist.protocol.AssistRole
import kotlin.uuid.Uuid

class AssistPairingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun deviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = Uuid.random().toString()
        prefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    fun loadRole(): AssistRole {
        val raw = prefs.getString(KEY_ROLE, AssistRole.ELDER.name).orEmpty()
        return runCatching { AssistRole.valueOf(raw) }.getOrDefault(AssistRole.ELDER)
    }

    fun saveRole(role: AssistRole) {
        prefs.edit().putString(KEY_ROLE, role.name).apply()
    }

    fun loadDisplayName(): String = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty()

    fun saveDisplayName(name: String) {
        prefs.edit().putString(KEY_DISPLAY_NAME, name.trim()).apply()
    }

    fun loadServerHttpUrl(): String = prefs.getString(KEY_SERVER_HTTP, "").orEmpty()

    fun saveServerHttpUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_HTTP, url.trim()).apply()
    }

    fun loadServerWsUrl(): String = prefs.getString(KEY_SERVER_WS, "").orEmpty()

    fun saveServerWsUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_WS, url.trim()).apply()
    }

    companion object {
        private const val PREFS_NAME = "joy_assist_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ROLE = "role"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_SERVER_HTTP = "server_http"
        private const val KEY_SERVER_WS = "server_ws"
    }
}
