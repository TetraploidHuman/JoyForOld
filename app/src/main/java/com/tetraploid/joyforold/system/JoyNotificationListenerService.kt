package com.tetraploid.joyforold.system

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.tetraploid.joyforold.privacy.PageContextRedactor

class JoyNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.isOngoing || sbn.isGroup) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val appLabel = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0),
            ).toString()
        }.getOrDefault(sbn.packageName)

        UnreadNotificationStore.record(
            UnreadNotificationEntry(
                packageName = sbn.packageName,
                appLabel = appLabel,
                title = PageContextRedactor.redact(title),
                text = PageContextRedactor.redact(text),
                postedAtMs = sbn.postTime,
            ),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = Unit
}
