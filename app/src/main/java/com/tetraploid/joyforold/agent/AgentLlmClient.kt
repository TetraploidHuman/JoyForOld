package com.tetraploid.joyforold.agent

import org.json.JSONObject

/** Agent 规划与分类所用的 LLM 端口；生产实现为 [DeepSeekClient]，测试可替换为 fake。 */
interface AgentLlmClient {
    suspend fun beginTask(
        apiKey: String,
        conversation: AgentConversationSession,
        userCommand: String,
        pageContext: String,
        pageDiff: String,
        keyMemories: String,
        minimalPageContext: String,
        pageContextMode: PageContextMode = PageContextMode.FULL,
        toolsPrompt: String? = null,
        loopContext: String = "",
        screenshotBase64: String? = null,
        visionMode: Boolean = false,
    ): JSONObject

    suspend fun continueAfterStep(
        apiKey: String,
        conversation: AgentConversationSession,
        stepFeedback: String,
        pageContext: String,
        pageDiff: String,
        keyMemories: String = "",
        minimalPageContext: String = "",
        pageContextMode: PageContextMode = PageContextMode.FULL,
        loopContext: String = "",
        screenshotBase64: String? = null,
        visionMode: Boolean = false,
    ): JSONObject

    suspend fun extractKeyMemory(apiKey: String, sessionSummary: String): String

    fun ensureSystemSeeded(
        conversation: AgentConversationSession,
        keyMemories: String,
        visionMode: Boolean = false,
        toolsPrompt: String? = null,
    )

    suspend fun classifyPresetIntent(apiKey: String, utterance: String): Pair<String, Double>?

    suspend fun classifySystemIntent(
        apiKey: String,
        utterance: String,
    ): SystemIntentAiResolver.Classification?
}
