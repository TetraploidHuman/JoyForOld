package com.tetraploid.joyforold.agent

import java.util.Calendar

/**
 * 根据时段、记忆与照护预设生成上下文相关建议芯片。
 */
object SuggestionEngine {
    fun suggestions(state: AgentUiState): List<String> {
        val chips = linkedSetOf<String>()
        chips += timeBasedSuggestions()
        chips += memoryBasedSuggestions(state.recentMemories)
        chips += caregiverSuggestions(state)
        if (chips.isEmpty()) {
            chips += listOf("几点了", "我要回家", "打电话给女儿")
        }
        return chips.take(6).toList()
    }

    private fun timeBasedSuggestions(): List<String> {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..10 -> listOf("几点了", "今天天气怎么样", "帮我读一下未读消息")
            in 11..13 -> listOf("发送消息给家人", "几点了")
            in 14..17 -> listOf("打开相册", "今天天气怎么样")
            in 18..21 -> listOf("我要回家", "打电话给女儿", "打开微信")
            else -> listOf("我要回家", "几点了")
        }
    }

    private fun memoryBasedSuggestions(memories: List<String>): List<String> {
        val joined = memories.joinToString(" ")
        val result = mutableListOf<String>()
        if (joined.contains("药") || joined.contains("吃药")) {
            result += "设个吃药提醒"
        }
        if (joined.contains("医院") || joined.contains("体检")) {
            result += "添加日程"
        }
        if (joined.contains("天气")) {
            result += "今天天气怎么样"
        }
        return result
    }

    private fun caregiverSuggestions(state: AgentUiState): List<String> {
        val result = mutableListOf<String>()
        if (state.daughterPhone.isNotBlank()) result += "打电话给女儿"
        if (state.sonPhone.isNotBlank()) result += "打电话给儿子"
        if (state.homeAddress.isNotBlank()) result += "我要回家"
        if (state.emergencyPhone.isNotBlank()) result += "紧急呼救"
        return result
    }
}
