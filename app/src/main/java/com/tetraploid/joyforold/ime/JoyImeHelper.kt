package com.tetraploid.joyforold.ime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager

object JoyImeHelper {
    private const val IME_CLASS = "com.tetraploid.joyforold.ime.JoyInputMethodService"

    fun componentName(context: Context): ComponentName =
        ComponentName(context.packageName, IME_CLASS)

    /** 与 [InputMethodInfo.getId] / 系统设置里存的 ID 一致（flattenToShortString）。 */
    fun imeId(context: Context): String = componentName(context).flattenToShortString()

    fun isEnabled(context: Context): Boolean {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
        val ours = componentName(context)
        return imm.enabledInputMethodList.any { it.component == ours }
    }

    fun isSelectedAsDefault(context: Context): Boolean {
        val selected = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        ) ?: return false
        val parsed = ComponentName.unflattenFromString(selected) ?: return false
        return parsed == componentName(context)
    }

    fun createSettingsIntent(): Intent =
        Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
