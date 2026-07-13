package com.tetraploid.joyforold.overlay

/**
 * 视觉 Agent 执行期间对悬浮层的抑制能力。
 *
 * 通过 [VisionOverlaySuppressors] 注册实现，避免 overlay / agent / runtime 之间的硬编码循环依赖。
 */
interface VisionOverlaySuppressor {
    fun isVisionAgentActive(): Boolean

    suspend fun pushSuppressionAwait(waitFrame: Boolean)

    fun popSuppression()

    suspend fun activateVisionAgentMode()

    fun deactivateVisionAgentMode()
}

object VisionOverlaySuppressors {
    @Volatile
    private var delegate: VisionOverlaySuppressor = NoOpVisionOverlaySuppressor

    fun install(suppressor: VisionOverlaySuppressor) {
        delegate = suppressor
    }

    internal val current: VisionOverlaySuppressor
        get() = delegate
}

private object NoOpVisionOverlaySuppressor : VisionOverlaySuppressor {
    override fun isVisionAgentActive(): Boolean = false

    override suspend fun pushSuppressionAwait(waitFrame: Boolean) = Unit

    override fun popSuppression() = Unit

    override suspend fun activateVisionAgentMode() = Unit

    override fun deactivateVisionAgentMode() = Unit
}
