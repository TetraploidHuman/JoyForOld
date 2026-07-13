package com.tetraploid.joyforold.core

/** 系统意图层发起老人端远程协助，避免 system → AgentRuntime 直接依赖。 */
fun interface AssistSessionStarter {
    fun startElderAssistSession()
}

object AssistSessionStarters {
    @Volatile
    var delegate: AssistSessionStarter = AssistSessionStarter {}
}
