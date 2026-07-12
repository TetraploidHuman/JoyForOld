package com.tetraploid.joyforold.ime

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

/**
 * 隐藏输入法：无可见键盘，仅供 Agent 通过 [InputConnection] 向已聚焦输入框注入文字。
 * 参考 Mantis MantisKeyboardIME（MIT）。
 */
class JoyInputMethodService : InputMethodService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "JoyInputMethodService created")
    }

    override fun onDestroy() {
        instance = null
        Log.d(TAG, "JoyInputMethodService destroyed")
        super.onDestroy()
    }

    override fun onCreateInputView(): View = View(this)

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onEvaluateInputViewShown(): Boolean = false

    /** 在本 App 内输入时切回用户原来的键盘，避免影响设置页手动输入。 */
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (info.packageName == packageName) {
            val token = window?.window?.attributes?.token ?: return
            @Suppress("DEPRECATION")
            getSystemService(InputMethodManager::class.java).switchToLastInputMethod(token)
        }
    }

    fun commitText(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        if (text.endsWith("\n")) {
            val body = text.dropLast(1)
            if (body.isNotEmpty()) ic.commitText(body, 1)
            pressEnter()
        } else {
            ic.commitText(text, 1)
        }
        Log.d(TAG, "commitText: ${text.length} chars")
        return true
    }

    private fun pressEnter() {
        val ic = currentInputConnection ?: return
        val imeAction = currentInputEditorInfo
            ?.imeOptions
            ?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE
        if (imeAction != EditorInfo.IME_ACTION_NONE &&
            imeAction != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(imeAction)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    companion object {
        private const val TAG = "JoyIME"

        @Volatile
        var instance: JoyInputMethodService? = null
            private set

        fun typeText(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return false
            return instance?.commitText(trimmed) == true
        }
    }
}
