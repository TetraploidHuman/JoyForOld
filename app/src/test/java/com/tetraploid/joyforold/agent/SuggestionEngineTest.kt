package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionEngineTest {
    @Test
    fun suggestions_includeCaregiverContacts() {
        val state = AgentUiState(
            daughterPhone = "13800000000",
            homeAddress = "北京市朝阳区",
        )
        val chips = SuggestionEngine.suggestions(state)
        assertTrue(chips.contains("打电话给女儿"))
        assertTrue(chips.contains("我要回家"))
    }

    @Test
    fun suggestions_respectMemoryHints() {
        val state = AgentUiState(recentMemories = listOf("提醒用户按时吃药"))
        val chips = SuggestionEngine.suggestions(state)
        assertTrue(chips.contains("设个吃药提醒"))
    }
}
