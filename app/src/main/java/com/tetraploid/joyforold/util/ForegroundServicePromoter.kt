package com.tetraploid.joyforold.util

import android.app.Notification
import android.app.Service
import android.util.Log

/**
 * 统一前台服务提升，避免 Android 13+ 未授予通知权限时未捕获的 SecurityException。
 */
object ForegroundServicePromoter {
    private const val TAG = "JoyFgsPromoter"

    fun promote(service: Service, notificationId: Int, notification: Notification): Boolean {
        return runCatching {
            service.startForeground(notificationId, notification)
            true
        }.onFailure { error ->
            Log.w(TAG, "startForeground failed for ${service.javaClass.simpleName}", error)
        }.getOrDefault(false)
    }
}
