package com.tetraploid.joyforold.agent

/**
 * Shrinks agent chat payloads before they are sent to the LLM API.
 * Full snapshots remain in persisted session JSON; only API-bound messages are compacted.
 */
object AgentMessageCompactor {
    private const val PAGE_CONTEXT_MARKER = "【当前页面快览】"
    private const val PAGE_MINIMAL_MARKER = "【当前页面】"
    private const val PAGE_DIFF_MARKER = "【页面变化】"
    private const val PAGE_OMITTED = "（历史页面快照已省略，以最新观察为准）"
    private const val AGENT_FEEDBACK_DETAIL_MAX_CHARS = 1_000

    private val historicalPageBlockRegex = Regex(
        """(?:【当前页面快览】|【当前页面】)[\s\S]*?【页面变化】[\s\S]*?(?=\n请规划|\n请决定|$)""",
    )

    fun compactForApi(messages: List<ChatMessage>): List<ChatMessage> {
        val lastPageIdx = messages.indexOfLast { containsPageSection(it) }
        if (lastPageIdx < 0) return messages
        return messages.mapIndexed { index, message ->
            if (index == lastPageIdx) message else omitPageSection(message)
        }
    }

    fun formatPageSection(
        pageContext: String,
        pageDiff: String,
        minimalPageContext: String,
        mode: PageContextMode? = null,
    ): String {
        val resolved = mode ?: if (pageDiff.contains("页面指纹未变")) {
            PageContextMode.DIFF_ONLY
        } else {
            PageContextMode.FULL
        }
        return when (resolved) {
            PageContextMode.DIFF_ONLY -> buildString {
                appendLine()
                appendLine("$PAGE_MINIMAL_MARKER $minimalPageContext")
                appendLine()
                appendLine(PAGE_DIFF_MARKER)
                append("页面无明显变化，沿用上次观察，请结合近期执行结果决策。")
            }
            PageContextMode.COMPACT -> buildString {
                appendLine()
                appendLine("$PAGE_MINIMAL_MARKER $minimalPageContext")
                appendLine()
                appendLine(PAGE_DIFF_MARKER)
                append(pageDiff)
            }
            PageContextMode.FULL -> buildString {
                appendLine()
                appendLine(PAGE_CONTEXT_MARKER)
                append(pageContext)
                appendLine()
                appendLine(PAGE_DIFF_MARKER)
                append(pageDiff)
            }
        }
    }

    fun truncateAgentFeedbackDetail(detail: String): String {
        if (detail.length <= AGENT_FEEDBACK_DETAIL_MAX_CHARS) return detail
        return detail.take(AGENT_FEEDBACK_DETAIL_MAX_CHARS) +
            "\n...（工具详情已截断，共 ${detail.length} 字）"
    }

    private fun containsPageSection(message: ChatMessage): Boolean {
        if (message.role != "user") return false
        return message.content.contains(PAGE_CONTEXT_MARKER) ||
            message.content.contains(PAGE_MINIMAL_MARKER) ||
            message.content.contains(PAGE_DIFF_MARKER)
    }

    private fun omitPageSection(message: ChatMessage): ChatMessage {
        if (message.role != "user") return message
        if (!containsPageSection(message)) return message
        val compacted = message.content.replace(historicalPageBlockRegex, PAGE_OMITTED)
        return if (compacted == message.content) message else ChatMessage(message.role, compacted)
    }
}
