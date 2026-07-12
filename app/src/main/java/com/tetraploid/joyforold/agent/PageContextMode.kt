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

object PageContextSelector {
    fun modeFor(
        previous: StructuredPageSnapshot?,
        current: StructuredPageSnapshot,
        pageDiff: String,
    ): PageContextMode {
        // 效果优先：每轮规划都传完整页面快览 + diff，不因指纹未变而省略
        return PageContextMode.FULL
    }
}
