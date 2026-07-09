package com.tetraploid.joyforold.agent

/**
 * 用户确认续跑：原样写入上下文，不做语义硬解析。
 */
object ConfirmResumeBuilder {
    fun buildEnrichedResume(
        originalCommand: String,
        aiPrompt: String,
        userReply: String,
    ): String = buildString {
        appendLine("原指令：$originalCommand")
        appendLine("助手询问：$aiPrompt")
        appendLine("用户回答：$userReply")
        appendLine()
        append(
            "【系统提示】用户已回答上述问题。请结合用户原话继续任务，不要重复询问同一问题。",
        )
    }.trim()
}
