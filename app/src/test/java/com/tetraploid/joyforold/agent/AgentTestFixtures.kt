package com.tetraploid.joyforold.agent

import android.content.Context
import com.tetraploid.joyforold.preset.PresetCommandStore

object AgentTestFixtures {
    fun orchestrator(
        context: Context,
        llmClient: AgentLlmClient = DeepSeekClient(),
        sessionStore: AgentSessionStore? = null,
        contextConsentStore: ContextConsentStore? = null,
    ): AgentOrchestrator {
        val memoryStore = AgentMemoryStore(context)
        val resolvedSessionStore = sessionStore ?: AgentSessionStore(context)
        val appHintStore = AppHintStore(context)
        val presetStore = PresetCommandStore(context)
        val visionDebugStore = VisionDebugStore(context)
        val resolvedConsentStore = contextConsentStore ?: ContextConsentStore(context)
        return AgentOrchestrator(
            llmClient = llmClient,
            memoryStore = memoryStore,
            sessionStore = resolvedSessionStore,
            appHintStore = appHintStore,
            presetStore = presetStore,
            visionDebugStore = visionDebugStore,
            contextConsentStore = resolvedConsentStore,
        )
    }

    fun pendingState(
        originalCommand: String = "给610打电话",
        aiPrompt: String = "你要在哪里打电话？",
    ): PendingAgentState = PendingAgentState(
        originalCommand = originalCommand,
        aiPrompt = aiPrompt,
        session = AgentConversationSession(rootCommand = originalCommand),
        previousSnapshot = null,
    )
}
