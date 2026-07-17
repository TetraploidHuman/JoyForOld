package com.tetraploid.joyforold.uitreetest

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.CopyOnWriteArrayList

open class UiTreeTestAccessibilityService : AccessibilityService() {

    private var lastLogcatDump: String? = null
    private var lastLogcatAtMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        android.util.Log.i(
            LOG_TAG,
            "connected component=${WhitelistDisguise.enabledServiceComponentId(packageName)} " +
                "runtimeClass=${javaClass.name}",
        )
        refreshAndNotify()
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val shouldReact = autoRefreshEnabled ||
            UiTreeTestPrefs.isContinuousLogcatEnabled(applicationContext)
        if (!shouldReact) return
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
        maybeLogContinuously(dump)
        listeners.forEach { listener ->
            runCatching { listener.onTreeUpdated(dump) }
        }
        sendBroadcast(Intent(ACTION_TREE_UPDATED).setPackage(packageName))
    }

    private fun maybeLogContinuously(dump: String) {
        if (!UiTreeTestPrefs.isContinuousLogcatEnabled(applicationContext)) return
        val now = System.currentTimeMillis()
        if (dump == lastLogcatDump) return
        if (now - lastLogcatAtMs < Companion.MIN_LOGCAT_INTERVAL_MS) return
        lastLogcatDump = dump
        lastLogcatAtMs = now
        FullUiTreeDumper.logToLogcat(dump)
    }

    fun setContinuousLogcatEnabled(enabled: Boolean) {
        UiTreeTestPrefs.setContinuousLogcatEnabled(applicationContext, enabled)
        if (!enabled) {
            lastLogcatDump = null
            lastLogcatAtMs = 0L
            return
        }
        val dump = latestDump.ifBlank { dumpCurrentTree() }
        if (dump.isNotBlank()) {
            lastLogcatDump = null
            maybeLogContinuously(dump)
        }
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

        fun isContinuousLogcatEnabled(context: Context): Boolean =
            UiTreeTestPrefs.isContinuousLogcatEnabled(context)

        fun setContinuousLogcatEnabled(context: Context, enabled: Boolean) {
            UiTreeTestPrefs.setContinuousLogcatEnabled(context, enabled)
            instance?.setContinuousLogcatEnabled(enabled)
        }

        private const val MIN_LOGCAT_INTERVAL_MS = 1_000L
        private const val LOG_TAG = "UiTreeTest"

        const val ACTION_TREE_UPDATED = "com.tetraploid.joyforold.uitreetest.TREE_UPDATED"
    }
}
