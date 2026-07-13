package com.tetraploid.joyforold.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object AccessibilityPermission {
    private fun expectedComponent(context: Context): ComponentName {
        return ComponentName(context, JoyAccessibilityService::class.java)
    }

    /** 系统设置里是否已勾选本应用的无障碍服务 */
    fun isSettingEnabled(context: Context): Boolean {
        if (isEnabledInSecureSettings(context)) return true
        return isEnabledInAccessibilityManager(context)
    }

    /** 无障碍服务进程是否已绑定到当前应用 */
    fun isServiceConnected(): Boolean = AccessibilityGateways.isConnected

    /**
     * 兼容旧调用：设置已开或实例已连接均视为可用。
     * UI 状态请优先用 [isSettingEnabled] + [isServiceConnected] 分开展示。
     */
    fun isEnabled(context: Context): Boolean {
        if (isServiceConnected()) return true
        return isSettingEnabled(context)
    }

    private fun isEnabledInSecureSettings(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val expected = expectedComponent(context).flattenToString()
        return flat.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun isEnabledInAccessibilityManager(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        if (!manager.isEnabled) return false
        val expected = expectedComponent(context)
        val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return services.any { info ->
            val service = info.resolveInfo.serviceInfo
            service.packageName == expected.packageName && service.name == expected.className
        }
    }
}
