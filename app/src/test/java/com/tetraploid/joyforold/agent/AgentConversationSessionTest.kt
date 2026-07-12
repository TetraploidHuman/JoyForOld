package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AgentConversationSessionTest {
    @Test
    fun toJson_fromJson_roundTrip() {
        val session = AgentConversationSession(rootCommand = "给610打电话")
        session.seedSystem("system prompt")
        session.addUser("【用户指令】给610打电话")
        session.addAssistant("""{"action":"finish","waiting_for_user":true}""")
        session.recordStep(
            step = 1,
            action = AgentAction(action = "click", targetText = "610"),
            result = ActionExecutionResult(true, "已点击"),
            pageDiff = "新增可点击",
        )

        val restored = AgentConversationSession.fromJson(session.toJson())
        assertEquals(session.sessionId, restored.sessionId)
        assertEquals("给610打电话", restored.rootCommand)
        assertTrue(restored.hasSystem())
        assertEquals(1, restored.stepRecords.size)
        assertEquals("click", restored.stepRecords.first().action.action)
    }

    @Test
    fun pruneForApi_keepsSystemAndFirstCommand() {
        val session = AgentConversationSession(rootCommand = "测试")
        session.seedSystem("system")
        session.addUser("【用户指令】测试")
        repeat(30) { index ->
            session.addAssistant("""{"action":"wait"}""")
            session.addUser("步骤反馈 $index")
        }

        val apiMessages = session.toApiMessages()
        assertTrue(apiMessages.length() <= 18)
        assertEquals("system", apiMessages.getJSONObject(0).getString("role"))
    }

    @Test
    fun appendLocalStepsSummary_addsUserMessage() {
        val session = AgentConversationSession(rootCommand = "给张三发消息：你好")
        session.appendLocalStepsSummary(
            listOf(
                AgentStepLog(
                    step = 1,
                    action = AgentAction(action = "click", targetText = "张三"),
                    success = true,
                    detail = "ok",
                ),
            ),
        )
        val messages = session.toJson().getJSONArray("messages")
        assertTrue(messages.length() >= 1)
        assertTrue(messages.getJSONObject(0).getString("content").contains("本地快路径"))
    }

    @Test
    fun toResponsesApiInput_putsSystemIntoInstructionsOnly() {
        val session = AgentConversationSession(rootCommand = "测试")
        session.seedSystem("system")
        session.addUser("【用户指令】测试")
        session.addAssistant("""{"action":"wait"}""")
        session.addUser("【上一步执行结果】成功")

        val input = session.toResponsesApiInput()
        assertEquals(3, input.length())
        assertEquals("user", input.getJSONObject(0).getString("role"))
        assertEquals("assistant", input.getJSONObject(1).getString("role"))
        assertEquals("user", input.getJSONObject(2).getString("role"))
        assertEquals("system", session.systemInstructions())
    }

    @Test
    fun toResponsesApiInput_attachesScreenshotOnLatestUser() {
        val session = AgentConversationSession(rootCommand = "测试")
        session.seedSystem("system")
        session.addUser("【用户指令】测试")
        session.addAssistant("""{"action":"wait"}""")
        session.addUser("【上一步执行结果】成功")

        val input = session.toResponsesApiInput("imgb64")
        val latest = input.getJSONObject(input.length() - 1)
        assertEquals("user", latest.getString("role"))
        val content = latest.getJSONArray("content")
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        assertEquals("input_image", content.getJSONObject(1).getString("type"))
        assertTrue(content.getJSONObject(1).getString("image_url").contains("imgb64"))
    }
}
