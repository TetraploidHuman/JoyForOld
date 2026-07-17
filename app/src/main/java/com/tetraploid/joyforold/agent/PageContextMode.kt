package com.tetraploid.joyforold.agent

enum class PageContextMode {
    /** 不传页面上下文（开放问答 / 纯系统动作） */
    NONE,
    /** 页面明显变化：传完整快览 + diff */
    FULL,
    /** 小幅变化：只传一行摘要 + diff */
    COMPACT,
    /** 指纹未变：只传一行摘要 + 简短说明 */
    DIFF_ONLY,
}

data class PageObservationPayload(
    val pageContext: String,
    val pageDiff: String,
    val minimalPageContext: String,
    val mode: PageContextMode,
    val screenshotBase64: String? = null,
    val visionMode: Boolean = false,
    /** 无障碍树无可用 UI 信号（与是否附带截图无关） */
    val a11yUnavailable: Boolean = false,
) {
    /** 传给 LLM 规划器：视觉兜底或无障碍不可用 */
    fun plannerVisionMode(): Boolean = visionMode || a11yUnavailable
}

/**
 * 动态选择页面上下文粒度：FULL 扛关键决策，COMPACT 扛增量，DIFF_ONLY 仅同屏空转。
 */
object PageContextSelector {
    /** 连续若干步未 FULL 后强制拉回，避免压缩漂移 */
    const val FULL_REFRESH_EVERY_N_STEPS = 3

    @Suppress("UNUSED_PARAMETER")
    fun modeFor(
        previous: StructuredPageSnapshot?,
        current: StructuredPageSnapshot,
        pageDiff: String,
        forceFull: Boolean = false,
        stepsSinceLastFull: Int = 0,
        a11yUnavailable: Boolean = false,
    ): PageContextMode {
        if (forceFull || a11yUnavailable) return PageContextMode.FULL
        if (previous == null) return PageContextMode.FULL
        if (previous.packageName != current.packageName) return PageContextMode.FULL
        if (stepsSinceLastFull >= FULL_REFRESH_EVERY_N_STEPS) return PageContextMode.FULL
        if (previous.fingerprint == current.fingerprint) return PageContextMode.DIFF_ONLY
        if (PageObservation.isMinorChange(previous, current)) return PageContextMode.COMPACT
        return PageContextMode.FULL
    }
}
