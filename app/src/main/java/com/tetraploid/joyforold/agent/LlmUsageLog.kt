package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.privacy.SafeLog

/**
 * LLM token 用量日志。按 call 打 logcat，并可选累计到 [AgentConversationSession]。
 */
object LlmUsageLog {
    fun record(
        phase: String,
        usage: LlmApiSupport.TokenUsage?,
        conversation: AgentConversationSession? = null,
    ) {
        if (usage == null) {
            SafeLog.i("LLM usage[$phase] unavailable")
            return
        }
        conversation?.addTokenUsage(usage)
        val sessionPart = conversation?.let {
            " session=${it.sessionId.take(8)} cumPrompt=${it.promptTokensTotal} cumCompletion=${it.completionTokensTotal} cumTotal=${it.totalTokensTotal}"
        }.orEmpty()
        SafeLog.i(
            "LLM usage[$phase] prompt=${usage.promptTokens} completion=${usage.completionTokens} " +
                "total=${usage.resolvedTotal}$sessionPart",
        )
    }
}
