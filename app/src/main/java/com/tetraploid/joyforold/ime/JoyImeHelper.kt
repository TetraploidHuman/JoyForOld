package com.tetraploid.joyforold.ime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

object JoyImeHelper {
    private const val IME_CLASS = "com.tetraploid.joyforold.ime.JoyInputMethodService"

    fun imeId(packageName: String): String = "$packageName/$IME_CLASS"

    fun componentName(context: Context): ComponentName =
        ComponentName(context.packageName, IME_CLASS)

    fun isEnabled(context: Context): Boolean {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
        val id = imeId(context.packageName)
        return imm.enabledInputMethodList.any { it.id == id }
    }

    fun isSelectedAsDefault(context: Context): Boolean {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
        val selected = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        ) ?: return false
        return selected == imeId(context.packageName)
    }

    fun createSettingsIntent(): Intent =
        Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
