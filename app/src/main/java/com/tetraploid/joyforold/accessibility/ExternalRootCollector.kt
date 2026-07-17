package com.tetraploid.joyforold.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.tetraploid.joyforold.agent.ExternalWindowFilter

/**
 * 从指定 [AccessibilityService] 收集外部窗口 root（读树与 Joy 手势服务可分离）。
 *
 * 同一 App 常有多个窗口（如高德地图层 + POI 列表层）。按包名只留一个会漏掉
 * 带「路线」的列表窗，导致 find_on_page/click 找不到页面快览里已有的按钮。
 */
object ExternalRootCollector {

    fun collect(
        service: AccessibilityService,
        ownPackage: String,
        cachedRoot: AccessibilityNodeInfo? = null,
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        val seenKeys = linkedSetOf<String>()

        fun windowKey(root: AccessibilityNodeInfo): String {
            val pkg = root.packageName?.toString().orEmpty()
            val rect = android.graphics.Rect()
            root.getBoundsInScreen(rect)
            return "$pkg@${rect.left},${rect.top},${rect.right},${rect.bottom}"
        }

        fun addRoot(root: AccessibilityNodeInfo) {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank() || pkg == ownPackage || ExternalWindowFilter.isIgnoredPackage(pkg)) {
                root.recycle()
                return
            }
            val key = windowKey(root)
            if (key in seenKeys) {
                root.recycle()
                return
            }
            seenKeys += key
            results += AccessibilityNodeInfo.obtain(root)
            root.recycle()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            service.windows?.forEach { window ->
                if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@forEach
                val root = window.root ?: return@forEach
                addRoot(root)
            }
        }

        cachedRoot?.let { cached ->
            addRoot(cached)
        }

        if (results.isEmpty()) {
            service.rootInActiveWindow?.let { active ->
                val pkg = active.packageName?.toString()
                if (!pkg.isNullOrBlank() && pkg != ownPackage) {
                    results += AccessibilityNodeInfo.obtain(active)
                }
                active.recycle()
            }
        }
        return results
    }
}
