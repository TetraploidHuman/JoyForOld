package com.tetraploid.joyforold.agent

import android.content.Context

/**
 * 云端发送无障碍页面结构前的用户授权（在设置页一次性开启，不在对话中反复询问）。
 */
class ContextConsentStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasConsented(): Boolean = prefs.getBoolean(KEY_CONSENTED, false)

    fun grantConsent() {
        prefs.edit().putBoolean(KEY_CONSENTED, true).apply()
    }

    fun revokeConsent() {
        prefs.edit().putBoolean(KEY_CONSENTED, false).apply()
    }

    companion object {
        private const val PREFS_NAME = "joy_context_consent"
        private const val KEY_CONSENTED = "ui_context_consented"

        const val SETTINGS_HINT =
            "此操作需要读取屏幕内容并发送到云端。请先在「设置 → 隐私与权限」中开启「允许云端理解屏幕内容」。"
    }
}
