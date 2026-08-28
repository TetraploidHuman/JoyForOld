package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActionGuardTest {
    @Test
    fun blockedRepeatReason_blocksSameFailedAction() {
        val session = AgentConversationSession(rootCommand = "点击610")
        session.recordStep(
            step = 1,
            action = AgentAction(action = "click", targetText = "610"),
            result = ActionExecutionResult(false, "未找到"),
            pageDiff = "",
        )

        assertNull(
            AgentActionGuard.blockedRepeatReason(
                session,
                AgentAction(action = "click", targetText = "610"),
            ),
        )

        session.recordStep(
            step = 2,
            action = AgentAction(action = "click", targetText = "610"),
            result = ActionExecutionResult(false, "仍未找到"),
            pageDiff = "",
        )

        val reason = AgentActionGuard.blockedRepeatReason(
            session,
            AgentAction(action = "click", targetText = "610"),
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("禁止"))
    }

    @Test
    fun blockedRepeatReason_allowsDifferentAction() {
        val session = AgentConversationSession(rootCommand = "点击610")
        session.recordStep(
            step = 1,
            action = AgentAction(action = "click", targetText = "610"),
            result = ActionExecutionResult(false, "未找到"),
            pageDiff = "",
        )

        assertNull(
            AgentActionGuard.blockedRepeatReason(
                session,
                AgentAction(action = "scroll_down"),
            ),
        )
    }

    @Test
    fun blockedRepeatReason_blocksRepeatedFindOnPage() {
        val session = AgentConversationSession(rootCommand = "哔哩哔哩打开视频叫我要吃饭")
        repeat(2) { step ->
            session.recordStep(
                step = step + 1,
                action = AgentAction(action = "find_on_page", targetText = "我要吃饭"),
                result = ActionExecutionResult(false, "未找到"),
                pageDiff = "",
            )
        }

        val reason = AgentActionGuard.blockedRepeatReason(
            session,
            AgentAction(action = "find_on_page", targetText = "我要吃饭"),
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("禁止"))
    }

    @Test
    fun blockedRepeatReason_blocksRepeatedSuccessfulClick() {
        val session = AgentConversationSession(rootCommand = "搜索视频")
        repeat(2) { step ->
            session.recordStep(
                step = step + 1,
                action = AgentAction(action = "click", targetText = "搜索"),
                result = ActionExecutionResult(true, "已点击"),
                pageDiff = "新增可见文字(1): 搜索发现",
            )
        }

        val reason = AgentActionGuard.blockedRepeatReason(
            session,
            AgentAction(action = "click", targetText = "搜索"),
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("已连续成功"))
    }

    @Test
    fun sensitiveConfirmOverride_sendRequiresConfirm() {
        val session = AgentConversationSession(rootCommand = "发消息：你好")
        val override = AgentActionGuard.sensitiveConfirmOverride(
            session = session,
            action = AgentAction(action = "send"),
        )
        assertNotNull(override)
        assertTrue(override!!.waitingForUser)
    }

    @Test
    fun sensitiveConfirmOverride_callRouteBeforeDialButton() {
        val session = AgentConversationSession(rootCommand = "给610打电话")
        val override = AgentActionGuard.sensitiveConfirmOverride(
            session = session,
            action = AgentAction(action = "click", targetText = "语音通话"),
        )
        assertNotNull(override)
        assertEquals("你要在哪里打电话？请说 QQ电话 或 手机电话。", override!!.message)
    }

    @Test
    fun isSendConfirmPrompt_matchesGuardCopy() {
        assertTrue(AgentActionGuard.isSendConfirmPrompt(AgentActionGuard.SEND_PROMPT))
        assertTrue(AgentActionGuard.isSendConfirmPrompt(AgentActionGuard.SEND_CLICK_PROMPT))
        assertTrue(AgentActionGuard.isSendConfirmPrompt("即将发送消息。请确认：要说「发送」还是「取消」？"))
    }

    @Test
    fun sensitiveConfirmOverride_skipsAfterSendTopicResolved() {
        val session = AgentConversationSession(rootCommand = "给吴志强发消息")
        session.recordConfirmAnswer(AgentActionGuard.SEND_PROMPT, "发送")
        assertNull(
            AgentActionGuard.sensitiveConfirmOverride(
                session = session,
                action = AgentAction(action = "send"),
            ),
        )
    }

    @Test
    fun sensitiveConfirmOverride_stillRequiresConfirmAfterCancelReply() {
        val session = AgentConversationSession(rootCommand = "给吴志强发消息")
        session.recordConfirmAnswer(AgentActionGuard.SEND_PROMPT, "取消")
        assertNotNull(
            AgentActionGuard.sensitiveConfirmOverride(
                session = session,
                action = AgentAction(action = "send"),
            ),
        )
    }

    @Test
    fun sensitiveConfirmOverride_tapAfterTypeInSendFlowRequiresConfirm() {
        val session = AgentConversationSession(rootCommand = "给张三发消息：你好")
        session.recordStep(
            step = 1,
            action = AgentAction(action = "type", inputText = "你好"),
            result = ActionExecutionResult(true, "已输入"),
            pageDiff = "",
        )
        val override = AgentActionGuard.sensitiveConfirmOverride(
            session = session,
            action = AgentAction(action = "tap", targetText = "0.92,0.95"),
        )
        assertNotNull(override)
        assertTrue(override!!.waitingForUser)
    }

    @Test
    fun blockedWrongImSearch_blocksMiniProgramSearchWhenContactVisible() {
        val session = AgentConversationSession(rootCommand = "去微信给吴志强发消息说你好")
        val snapshot = StructuredPageSnapshot(
            packageName = "com.tencent.mm",
            appHint = "当前为微信",
            clickables = listOf("搜索小程序 搜索栏md5", "吴志强 [语音] 2\"", "搜索jha"),
            editables = emptyList(),
            visibleTexts = listOf("吴志强"),
            sendButtons = emptyList(),
            fingerprint = "mm",
        )
        val reason = AgentActionGuard.blockedWrongImSearch(
            session,
            AgentAction(action = "click", targetText = "搜索小程序 搜索栏"),
            snapshot,
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("吴志强"))
        assertTrue(reason.contains("禁止"))
    }
}
