package com.tetraploid.joyforold.collaboration

import android.app.Application
import com.tetraploid.joyforold.accessibility.AccessibilityActionDispatcher
import com.tetraploid.joyforold.accessibility.JoyAccessibilityService
import com.tetraploid.joyforold.agent.AgentAction
import com.tetraploid.joyforold.agent.AgentRuntime
import com.tetraploid.joyforold.agent.AgentToolRegistry
import com.tetraploid.joyforold.assist.protocol.AssistControlMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class AssistCommandExecutor(
    private val scope: CoroutineScope,
) {
    suspend fun execute(application: Application, message: AssistControlMessage): AssistControlMessage {
        val service = JoyAccessibilityService.instance
            ?: return AssistControlMessage.actionResult(
                action = message.type,
                success = false,
                detail = "无障碍服务未连接",
            )

        return when (message.type) {
            AssistControlMessage.TYPE_TAP -> {
                val action = AgentAction(action = "tap", targetText = "${message.x},${message.y}")
                val result = AgentToolRegistry.execute(application, service, action)
                AssistControlMessage.actionResult("tap", result.success, result.summary)
            }
            AssistControlMessage.TYPE_SWIPE -> {
                val summary = AccessibilityActionDispatcher.runAction {
                    service.swipeNormalizedBlocking(message.x, message.y, message.x2, message.y2)
                } ?: "滑动手势失败"
                AssistControlMessage.actionResult(
                    action = "swipe",
                    success = summary != "滑动手势失败",
                    detail = summary,
                )
            }
            AssistControlMessage.TYPE_ACTION -> {
                val action = AgentAction(action = message.name)
                val result = AgentToolRegistry.execute(application, service, action)
                AssistControlMessage.actionResult(message.name, result.success, result.summary)
            }
            AssistControlMessage.TYPE_TYPE_TEXT -> {
                val action = AgentAction(action = "type", inputText = message.text)
                val result = AgentToolRegistry.execute(application, service, action)
                AssistControlMessage.actionResult("type", result.success, result.summary)
            }
            AssistControlMessage.TYPE_COMMAND -> {
                scope.launch {
                    AgentRuntime.submitRemoteAssistCommand(application, message.text)
                }
                AssistControlMessage.agentStatus("running", "正在执行：${message.text}")
            }
            else -> AssistControlMessage.actionResult(message.type, false, "不支持的远程指令")
        }
    }
}
