package com.google.android.accessibility.selecttospeak

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.tetraploid.joyforold.di.agentRuntime

/**
 * 白名单类名无障碍服务：不替代 [com.tetraploid.joyforold.accessibility.JoyAccessibilityService]，
 * 仅用于向微信等 App 暴露完整 UI 树；手势与 Agent 仍由 Joy 服务执行。
 *
 * 用户需在系统设置中同时开启「JoyForOld」与本组件。
 */
class SelectToSpeakService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        agentRuntime().refreshAccessibilityState()
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            agentRuntime().refreshAccessibilityState()
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        var instance: SelectToSpeakService? = null
    }
}
