package com.tetraploid.joyforold.collaboration

import android.app.Application

/**
 * 协助会话与宿主运行时的边界回调，避免 collaboration 包直接依赖 [com.tetraploid.joyforold.agent.AgentRuntime]。
 */
data class AssistSessionCallbacks(
    val onAssistModeChanged: (Boolean) -> Unit = {},
    val onRemoteCommand: (Application, String) -> Unit = { _, _ -> },
)
