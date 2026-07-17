package com.tetraploid.joyforold.uitreetest

import android.content.Context

object UiTreeTestPrefs {
    private const val PREFS_NAME = "uitreetest_prefs"
    private const val KEY_CONTINUOUS_LOGCAT = "continuous_logcat"

    fun isContinuousLogcatEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONTINUOUS_LOGCAT, false)

    fun setContinuousLogcatEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONTINUOUS_LOGCAT, enabled)
            .apply()
    }
}
