package com.tetraploid.joyforold.agent

import android.content.Context

class VoiceInteractionConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isBargeInEnabled(): Boolean = prefs.getBoolean(KEY_BARGE_IN, true)

    fun setBargeInEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BARGE_IN, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "joy_voice_interaction"
        private const val KEY_BARGE_IN = "voice_barge_in_enabled"
    }
}
