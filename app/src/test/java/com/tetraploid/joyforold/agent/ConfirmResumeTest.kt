package com.tetraploid.joyforold.agent

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmResumeTest {
    @Test
    fun recordConfirmAnswer_keepsFullUserReplyInContext() {
        val session = AgentConversationSession(rootCommand = "给610打电话")
        session.recordConfirmAnswer(
            aiPrompt = "你要在哪里打电话？请说 QQ电话 或 手机电话。",
            userReply = "我想用系统自带的那个电话打",
        )

        assertTrue(session.rootCommand.contains("我想用系统自带的那个电话打"))
        assertTrue(session.hasResolvedConfirmTopic(AgentConversationSession.CONFIRM_TOPIC_CALL_ROUTE))
    }

    @Test
    fun guardDoesNotReaskAfterUserAnsweredCallRoute() {
        val session = AgentConversationSession(rootCommand = "给610打电话")
        session.recordConfirmAnswer(
            aiPrompt = "你要在哪里打电话？请说 QQ电话 或 手机电话。",
            userReply = "随便什么说法都可以",
        )

        assertNull(
            AgentActionGuard.sensitiveConfirmOverride(
                session = session,
                action = AgentAction(action = "open_app", targetText = "电话"),
            ),
        )
    }

    @Test
    fun buildEnrichedResume_includesVerbatimReply() {
        val text = ConfirmResumeBuilder.buildEnrichedResume(
            originalCommand = "给610打电话",
            aiPrompt = "你要在哪里打电话？",
            userReply = "就用手机默认的拨号应用",
        )
        assertTrue(text.contains("就用手机默认的拨号应用"))
        assertTrue(text.contains("不要重复询问"))
    }
}
