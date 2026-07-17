package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlmApiSupportUsageTest {
    @Test
    fun extractUsage_chatCompletions() {
        val body = """
            {"choices":[{"message":{"content":"{}"}}],"usage":{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150}}
        """.trimIndent()
        val usage = LlmApiSupport.extractUsage(body, "https://api.example.com/v1/chat/completions")
        assertEquals(120, usage?.promptTokens)
        assertEquals(30, usage?.completionTokens)
        assertEquals(150, usage?.resolvedTotal)
    }

    @Test
    fun extractUsage_responsesApi() {
        val body = """
            {"output":[{"type":"message","content":[{"type":"output_text","text":"{}"}]}],"usage":{"input_tokens":200,"output_tokens":40,"total_tokens":240}}
        """.trimIndent()
        val usage = LlmApiSupport.extractUsage(body, "https://ark.cn-beijing.volces.com/api/v3/responses")
        assertEquals(200, usage?.promptTokens)
        assertEquals(40, usage?.completionTokens)
        assertEquals(240, usage?.resolvedTotal)
    }

    @Test
    fun extractUsage_missing_returnsNull() {
        val body = """{"choices":[{"message":{"content":"{}"}}]}"""
        assertNull(LlmApiSupport.extractUsage(body, "https://api.example.com/v1/chat/completions"))
    }

    @Test
    fun session_accumulatesUsage() {
        val session = AgentConversationSession(rootCommand = "测试")
        session.addTokenUsage(LlmApiSupport.TokenUsage(100, 20, 120))
        session.addTokenUsage(LlmApiSupport.TokenUsage(50, 10, 60))
        assertEquals(150, session.promptTokensTotal)
        assertEquals(30, session.completionTokensTotal)
        assertEquals(180, session.totalTokensTotal)
        assertEquals(true, session.buildSessionSummary().contains("Token：prompt=150"))
    }
}
