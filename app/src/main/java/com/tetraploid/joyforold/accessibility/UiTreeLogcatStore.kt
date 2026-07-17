package com.tetraploid.joyforold.accessibility

import android.content.Context

/** 主应用 UI 树实时 logcat 开关（与设置页同步）。 */
class UiTreeLogcatStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS = "joy_ui_tree_logcat"
        private const val KEY_ENABLED = "continuous_enabled"
    }
}
