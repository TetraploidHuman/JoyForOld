package com.tetraploid.joyforold.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.google.android.accessibility.selecttospeak.SelectToSpeakService

object AccessibilityPermission {
    private fun joyComponent(context: Context): ComponentName =
        ComponentName(context, JoyAccessibilityService::class.java)

    private fun whitelistReaderComponent(context: Context): ComponentName =
        ComponentName(context, SelectToSpeakService::class.java)

    /** 主无障碍服务（手势、Agent）是否在系统设置中已开启 */
    fun isSettingEnabled(context: Context): Boolean {
        if (isComponentEnabledInSecureSettings(context, joyComponent(context))) return true
        return isComponentEnabledInAccessibilityManager(context, joyComponent(context))
    }

    /** 微信读取增强（白名单类名）是否在系统设置中已开启 */
    fun isWhitelistReaderSettingEnabled(context: Context): Boolean {
        if (isComponentEnabledInSecureSettings(context, whitelistReaderComponent(context))) return true
        return isComponentEnabledInAccessibilityManager(context, whitelistReaderComponent(context))
    }

    /** 主服务进程是否已连接 */
    fun isServiceConnected(): Boolean = AccessibilityGateways.isConnected

    /** 白名单读取服务进程是否已连接 */
    fun isWhitelistReaderConnected(): Boolean = SelectToSpeakService.instance != null

    /**
     * 兼容旧调用：主服务设置已开或主服务实例已连接。
     * UI 状态请优先用 [isSettingEnabled] + [isServiceConnected] 分开展示。
     */
    fun isEnabled(context: Context): Boolean {
        if (isServiceConnected()) return true
        return isSettingEnabled(context)
    }

    private fun isComponentEnabledInSecureSettings(context: Context, component: ComponentName): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val expected = component.flattenToString()
        return flat.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun isComponentEnabledInAccessibilityManager(
        context: Context,
        component: ComponentName,
    ): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        if (!manager.isEnabled) return false
        val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return services.any { info ->
            val service = info.resolveInfo.serviceInfo
            service.packageName == component.packageName && service.name == component.className
        }
    }
}
