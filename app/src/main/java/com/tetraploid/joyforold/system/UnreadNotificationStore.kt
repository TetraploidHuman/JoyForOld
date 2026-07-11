package com.tetraploid.joyforold.system

import com.tetraploid.joyforold.privacy.SafeLog
import java.util.concurrent.CopyOnWriteArrayList

data class UnreadNotificationEntry(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAtMs: Long,
)

/**
 * 进程内缓存最近通知（已脱敏），供 read_unread_messages 朗读。
 */
object UnreadNotificationStore {
    private const val MAX_ENTRIES = 30
    private val entries = CopyOnWriteArrayList<UnreadNotificationEntry>()

    fun record(entry: UnreadNotificationEntry) {
        entries.removeAll { it.packageName == entry.packageName && it.title == entry.title && it.text == entry.text }
        entries.add(0, entry)
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(entries.lastIndex)
        }
        SafeLog.i("通知缓存 +1：${entry.appLabel}")
    }

    fun recent(limit: Int = 5): List<UnreadNotificationEntry> {
        return entries.take(limit)
    }

    fun clear() {
        entries.clear()
    }
}
