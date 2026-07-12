package com.tetraploid.joyforold.ui.theme

import android.content.Context

class ThemePreferenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDarkTheme(): Boolean = prefs.getBoolean(KEY_DARK_THEME, false)

    fun setDarkTheme(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_THEME, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "joy_theme"
        private const val KEY_DARK_THEME = "dark_theme"
    }
}
