package com.tetraploid.joyforold.uitreetest

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.CopyOnWriteArrayList

class UiTreeTestAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshAndNotify()
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !autoRefreshEnabled) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> refreshAndNotify()
        }
    }

    override fun onInterrupt() = Unit

    fun dumpCurrentTree(): String {
        val windows = collectAllWindows()
        return try {
            FullUiTreeDumper.dumpAllWindows(windows)
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    private fun collectAllWindows(): List<AccessibilityWindowInfo> {
        val raw = windows ?: return emptyList()
        return raw.map { AccessibilityWindowInfo.obtain(it) }
    }

    private fun refreshAndNotify() {
        val dump = dumpCurrentTree()
        latestDump = dump
        listeners.forEach { listener ->
            runCatching { listener.onTreeUpdated(dump) }
        }
        sendBroadcast(Intent(ACTION_TREE_UPDATED).setPackage(packageName))
    }

    fun interface TreeUpdateListener {
        fun onTreeUpdated(dump: String)
    }

    companion object {
        @Volatile
        var instance: UiTreeTestAccessibilityService? = null

        @Volatile
        var autoRefreshEnabled: Boolean = true

        @Volatile
        var latestDump: String = ""

        private val listeners = CopyOnWriteArrayList<TreeUpdateListener>()

        fun isConnected(): Boolean = instance != null

        fun addListener(listener: TreeUpdateListener) {
            listeners += listener
            if (latestDump.isNotBlank()) {
                listener.onTreeUpdated(latestDump)
            }
        }

        fun removeListener(listener: TreeUpdateListener) {
            listeners -= listener
        }

        fun refreshNow(): String {
            val service = instance ?: return latestDump.ifBlank { "(无障碍服务未连接)" }
            service.refreshAndNotify()
            return latestDump
        }

        const val ACTION_TREE_UPDATED = "com.tetraploid.joyforold.uitreetest.TREE_UPDATED"
    }
}
