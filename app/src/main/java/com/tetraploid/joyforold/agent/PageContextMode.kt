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
)

object PageContextSelector {
    fun modeFor(
        previous: StructuredPageSnapshot?,
        current: StructuredPageSnapshot,
        pageDiff: String,
    ): PageContextMode {
        if (previous == null) return PageContextMode.FULL
        if (previous.fingerprint == current.fingerprint ||
            pageDiff.contains("页面指纹未变")
        ) {
            return PageContextMode.DIFF_ONLY
        }
        if (PageObservation.isMinorChange(previous, current)) {
            return PageContextMode.COMPACT
        }
        return PageContextMode.FULL
    }
}
