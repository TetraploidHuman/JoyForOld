package com.tetraploid.joyforold.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeAgentLlmClientActionSetAskTest {
    @Test
    fun resolveActionSetAsk_usesHandlerAndRecordsCall() = runBlocking {
        val fake = FakeAgentLlmClient().apply {
            actionSetAskHandler = { _, _, userPrompt, writeFields ->
                assertTrue(userPrompt.contains("王小明"))
                assertEquals(listOf("contact"), writeFields)
                mapOf("contact" to "王晓明")
            }
        }
        val result = fake.resolveActionSetAsk(
            apiKey = "k",
            systemPrompt = "sys",
            userPrompt = "用户说的联系人：王小明",
            writeFields = listOf("contact"),
        )
        assertEquals("王晓明", result["contact"])
        assertEquals(1, fake.actionSetAskCalls.size)
        assertEquals("sys", fake.actionSetAskCalls.first().first)
    }

    @Test
    fun resolveActionSetAsk_defaultReturnsEmpty() = runBlocking {
        val fake = FakeAgentLlmClient()
        assertTrue(
            fake.resolveActionSetAsk(
                apiKey = "k",
                systemPrompt = "s",
                userPrompt = "u",
                writeFields = listOf("contact"),
            ).isEmpty(),
        )
    }
}
