package com.tetraploid.joyforold.privacy

import android.util.Log

object SafeLog {
    private const val TAG = "JoyForOld"

    fun redact(message: String): String = PageContextRedactor.redactForLog(message)

    fun i(message: String) {
        Log.i(TAG, redact(message))
    }

    fun w(message: String, error: Throwable? = null) {
        if (error == null) {
            Log.w(TAG, redact(message))
        } else {
            Log.w(TAG, redact(message), error)
        }
    }
}
