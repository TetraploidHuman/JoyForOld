package com.tetraploid.joyforold.accessibility

import android.content.Context
import com.tetraploid.joyforold.agent.ActionExecutionResult
import com.tetraploid.joyforold.agent.AgentAction
import com.tetraploid.joyforold.agent.StructuredPageSnapshot

/**
 * Agent / 协作层访问无障碍能力的端口，避免直接依赖 [JoyAccessibilityService.instance]。
 */
interface AccessibilityGateway {
    fun context(): Context

    fun captureStructuredSnapshots(): List<StructuredPageSnapshot>

    fun mergeSnapshots(snapshots: List<StructuredPageSnapshot>): StructuredPageSnapshot?

    fun captureBestStructuredSnapshot(): StructuredPageSnapshot?

    fun snapshotCompactForAgent(): String

    fun snapshotForAgent(): String

    fun snapshotTreeForDebug(): String

    /** 开启/关闭持续将 UI 树打到 logcat（内容与 [snapshotForAgent] 一致）。 */
    fun setContinuousUiTreeLogcatEnabled(enabled: Boolean)

    suspend fun captureScreenshotBase64(forceFresh: Boolean = false): String?

    fun executeWithResult(action: AgentAction): ActionExecutionResult

    fun swipeNormalizedBlocking(x1: Int, y1: Int, x2: Int, y2: Int): String

    suspend fun swipeDown(): String

    suspend fun swipeUp(): String

    fun performGlobalHome(): Boolean
}

object AccessibilityGateways {
    @Volatile
    var current: AccessibilityGateway? = null
        private set

    internal fun bind(gateway: AccessibilityGateway) {
        current = gateway
    }

    internal fun unbind(gateway: AccessibilityGateway) {
        if (current === gateway) current = null
    }

    val isConnected: Boolean
        get() = current != null
}
