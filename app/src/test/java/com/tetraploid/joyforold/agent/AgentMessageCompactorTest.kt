package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMessageCompactorTest {
    @Test
    fun compactForApi_omitsHistoricalPageSnapshots() {
        val messages = listOf(
            ChatMessage("system", "system"),
            ChatMessage(
                "user",
                """
                【用户指令】打开微信
                【当前页面快览】
                old page
                【页面变化】
                old diff
                请决定第一步操作，只返回 JSON。
                """.trimIndent(),
            ),
            ChatMessage("assistant", """{"action":"wait"}"""),
            ChatMessage(
                "user",
                """
                【上一步执行结果】
                成功：等待
                【当前页面快览】
                latest page
                【页面变化】
                latest diff
                请决定下一步，只返回 JSON。
                """.trimIndent(),
            ),
        )

        val compacted = AgentMessageCompactor.compactForApi(messages)

        assertTrue(compacted[1].content.contains("历史页面快照已省略"))
        assertFalse(compacted[1].content.contains("old page"))
        assertTrue(compacted[3].content.contains("latest page"))
    }

    @Test
    fun formatPageSection_usesMinimalSummaryWhenFingerprintUnchanged() {
        val section = AgentMessageCompactor.formatPageSection(
            pageContext = "full page",
            pageDiff = "页面指纹未变（可能仍在同一屏或变化较小）",
            minimalPageContext = "当前为微信 | 可点击 12 | 可输入 1",
        )

        assertTrue(section.contains("【当前页面】"))
        assertFalse(section.contains("【当前页面快览】"))
        assertTrue(section.contains("当前为微信"))
        assertFalse(section.contains("full page"))
    }

    @Test
    fun truncateAgentFeedbackDetail_capsLargeToolOutput() {
        val detail = "x".repeat(1500)
        val truncated = AgentMessageCompactor.truncateAgentFeedbackDetail(detail)

        assertTrue(truncated.length < detail.length)
        assertTrue(truncated.contains("已截断"))
    }
}
