package com.tetraploid.joyforold.overlay

import com.tetraploid.joyforold.agent.AgentAction
import com.tetraploid.joyforold.agent.PageReadiness
import com.tetraploid.joyforold.agent.PageScreenshotCapture
import com.tetraploid.joyforold.agent.StructuredPageSnapshot

/**
 * Agent 触控步骤期间临时隐藏悬浮层，避免：
 * 1. 视觉截图被悬浮卡片污染
 * 2. 手势 click/tap 点到悬浮窗（高德底栏「导航/路线」常被挡住）
 *
 * 截图路径会清缓存并等一帧合成；点击路径 GONE 后等手势完成再恢复。
 */
object VisionOverlayGuard {
    /** 视觉坐标类动作：仅在无障碍树不可用时需要藏窗 */
    private val visionUiActions = setOf("tap", "type")

    /**
     * 可能走 [dispatchGesture] 的动作（ACTION_CLICK 失败后的手势兜底、滑动等）。
     * 悬浮窗盖住目标时须先藏，避免手势点到本应用浮层。
     */
    private val gestureUiActions = setOf(
        "click", "long_click", "long_press", "swipe", "scroll", "send",
    )

    fun actionNeedsHiddenOverlay(
        action: AgentAction,
        snapshot: StructuredPageSnapshot?,
    ): Boolean {
        val name = action.action.lowercase()
        if (name in gestureUiActions) return true
        if (!PageReadiness.needsVisionFallback(snapshot)) return false
        return name in visionUiActions
    }

    suspend fun <T> withHidden(block: suspend () -> T): T {
        val suppressor = VisionOverlaySuppressors.current
        if (suppressor.isVisionAgentActive()) {
            return block()
        }
        // 等一帧合成：避免 GONE 尚未生效时手势仍点到悬浮层
        suppressor.pushSuppressionAwait(waitFrame = true)
        return try {
            block()
        } finally {
            suppressor.popSuppression()
        }
    }

    suspend fun <T> withHiddenForCapture(block: suspend () -> T): T {
        PageScreenshotCapture.invalidateCache()
        val suppressor = VisionOverlaySuppressors.current
        if (suppressor.isVisionAgentActive()) {
            return block()
        }
        suppressor.pushSuppressionAwait(waitFrame = true)
        return try {
            block()
        } finally {
            suppressor.popSuppression()
        }
    }
}
