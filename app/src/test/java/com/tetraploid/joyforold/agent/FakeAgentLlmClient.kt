package com.tetraploid.joyforold.agent

import org.json.JSONObject
import java.util.ArrayDeque

/**
 * 可配置的 LLM 测试替身：记录调用并返回预设规划/分类结果，不发起网络请求。
 */
class FakeAgentLlmClient : AgentLlmClient {
    val beginTaskCommands = mutableListOf<String>()
    val continueAfterStepFeedbacks = mutableListOf<String>()
    val classifyPresetIntentCalls = mutableListOf<String>()
    val classifySystemIntentCalls = mutableListOf<String>()
    val extractKeyMemoryCalls = mutableListOf<String>()
    val actionSetAskCalls = mutableListOf<Pair<String, String>>()

    var presetIntentHandler: suspend (apiKey: String, utterance: String) -> Pair<String, Double>? =
        { _, _ -> null }
    var systemIntentHandler: suspend (apiKey: String, utterance: String) -> SystemIntentAiResolver.Classification? =
        { _, _ -> null }
    var extractKeyMemoryHandler: suspend (apiKey: String, sessionSummary: String) -> String =
        { _, _ -> "" }
    var actionSetAskHandler: suspend (
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        writeFields: List<String>,
    ) -> Map<String, String> = { _, _, _, _ -> emptyMap() }

    private val planningResponses = ArrayDeque<JSONObject>()

    fun enqueuePlanningResponse(json: JSONObject) {
        planningResponses.addLast(json)
    }

    fun enqueueFinishResponse(message: String) {
        enqueuePlanningResponse(
            JSONObject(
                """
                {
                  "action": "finish",
                  "message": "$message",
                  "finished": true
                }
                """.trimIndent(),
            ),
        )
    }

    override suspend fun beginTask(
        apiKey: String,
        conversation: AgentConversationSession,
        userCommand: String,
        pageContext: String,
        pageDiff: String,
        keyMemories: String,
        minimalPageContext: String,
        pageContextMode: PageContextMode,
        toolsPrompt: String?,
        loopContext: String,
        screenshotBase64: String?,
        visionMode: Boolean,
    ): JSONObject {
        beginTaskCommands += userCommand
        ensureSystemSeeded(conversation, keyMemories, visionMode, toolsPrompt)
        conversation.addUser(
            buildString {
                appendLine("【用户指令】$userCommand")
                if (loopContext.isNotBlank()) {
                    appendLine()
                    appendLine(loopContext)
                }
                append(
                    AgentMessageCompactor.formatPageSection(
                        pageContext = pageContext,
                        pageDiff = pageDiff,
                        minimalPageContext = minimalPageContext,
                        mode = pageContextMode,
                    ),
                )
            },
        )
        val response = planningResponses.pollFirst() ?: defaultFinishResponse()
        conversation.addAssistant(response.toString())
        return response
    }

    override suspend fun continueAfterStep(
        apiKey: String,
        conversation: AgentConversationSession,
        stepFeedback: String,
        pageContext: String,
        pageDiff: String,
        keyMemories: String,
        minimalPageContext: String,
        pageContextMode: PageContextMode,
        loopContext: String,
        screenshotBase64: String?,
        visionMode: Boolean,
    ): JSONObject {
        continueAfterStepFeedbacks += stepFeedback
        ensureSystemSeeded(conversation, keyMemories, visionMode)
        conversation.addUser(stepFeedback)
        val response = planningResponses.pollFirst() ?: defaultFinishResponse()
        conversation.addAssistant(response.toString())
        return response
    }

    override suspend fun extractKeyMemory(apiKey: String, sessionSummary: String): String {
        extractKeyMemoryCalls += sessionSummary
        return extractKeyMemoryHandler(apiKey, sessionSummary)
    }

    var planUserFacingPhasesHandler: suspend (apiKey: String, userCommand: String) -> List<TaskPhaseItem> =
        { _, command -> TaskPhasePlanner.planFromCommand(command) }

    override suspend fun planUserFacingPhases(
        apiKey: String,
        userCommand: String,
    ): List<TaskPhaseItem> = planUserFacingPhasesHandler(apiKey, userCommand)

    override fun ensureSystemSeeded(
        conversation: AgentConversationSession,
        keyMemories: String,
        visionMode: Boolean,
        toolsPrompt: String?,
    ) {
        val prompt = buildString {
            append("test-system-prompt\n")
            append(keyMemories)
            toolsPrompt?.let { append("\n").append(it) }
            if (visionMode) append("\nvision")
        }
        if (!conversation.hasSystem()) {
            conversation.seedSystem(prompt)
            return
        }
        if (visionMode) {
            conversation.refreshSystem(prompt)
        }
    }

    override suspend fun classifyPresetIntent(
        apiKey: String,
        utterance: String,
    ): Pair<String, Double>? {
        classifyPresetIntentCalls += utterance
        return presetIntentHandler(apiKey, utterance)
    }

    override suspend fun classifySystemIntent(
        apiKey: String,
        utterance: String,
    ): SystemIntentAiResolver.Classification? {
        classifySystemIntentCalls += utterance
        return systemIntentHandler(apiKey, utterance)
    }

    override suspend fun resolveActionSetAsk(
        apiKey: String,
        systemPrompt: String,
        userPrompt: String,
        writeFields: List<String>,
    ): Map<String, String> {
        actionSetAskCalls += systemPrompt to userPrompt
        return actionSetAskHandler(apiKey, systemPrompt, userPrompt, writeFields)
    }

    private fun defaultFinishResponse(): JSONObject = JSONObject(
        """
        {
          "action": "finish",
          "message": "好的",
          "finished": true
        }
        """.trimIndent(),
    )
}
