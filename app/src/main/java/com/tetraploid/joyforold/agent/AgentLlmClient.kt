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

    /**
     * 面向用户的粗略任务阶段（2~5 步），不绑定具体 click/tap。
     * 失败或空白时由调用方回退 [TaskPhasePlanner.planFromCommand]。
     */
    suspend fun planUserFacingPhases(apiKey: String, userCommand: String): List<TaskPhaseItem>

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

    /**
     * ActionSet 窄域 askLlm：只看列表/候选切片做选型，按 [writeFields] 从 JSON 写回 params。
     * 不应替代主规划，也不应接收整棵 UI 树。
     * 失败时返回 emptyMap（调用方可不改 params，仍记成功以推进流程，或自行重试）。
     */
    suspend fun resolveActionSetAsk(
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        writeFields: List<String>,
    ): Map<String, String>
}
