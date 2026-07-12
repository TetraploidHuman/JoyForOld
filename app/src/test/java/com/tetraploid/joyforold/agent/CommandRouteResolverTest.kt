package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.offline.nlu.OfflineNluRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CommandRouteResolverTest {
    @Test
    fun resolve_exactTemplate_hasFullConfidence() {
        val route = kotlinx.coroutines.runBlocking {
            CommandRouteResolver.resolve(
                command = "导航回家",
                apiKey = "",
                deepSeekClient = DeepSeekClient(),
            )
        }
        assertNotNull(route)
        assertEquals(1.0, route!!.confidence, 0.001)
        assertNull(route.clarifyMessage)
    }

    @Test
    fun looksLikeComplexQuery_detectsQuestion() {
        assertEquals(true, CommandRouteResolver.looksLikeComplexQuery("煤气味很重怎么办"))
        assertEquals(true, CommandRouteResolver.looksLikeComplexQuery("明天会降温吗我该穿啥"))
        assertEquals(false, CommandRouteResolver.looksLikeComplexQuery("打开蓝牙"))
    }

    @Test
    fun shouldUseOfflineNlu_skipsComplexWhenOnline() {
        val offline = OfflineNluRouter.Match(
            steps = listOf(AgentAction(action = "finish", message = "test")),
            confidence = 0.94,
            intent = "emergency_help",
        )
        assertEquals(
            false,
            CommandRouteResolver.shouldUseOfflineNlu("煤气味很重怎么办", offline, appContext = null),
        )
    }

    @Test
    fun shouldUseOfflineNlu_rejectsClarifyBand() {
        val offline = OfflineNluRouter.Match(
            steps = listOf(AgentAction(action = "finish", message = "打开蓝牙")),
            confidence = 0.68,
            clarifyMessage = "您是要打开蓝牙吗？",
        )
        assertEquals(
            false,
            CommandRouteResolver.shouldUseOfflineNlu("打开蓝牙", offline, appContext = null),
        )
    }

    @Test
    fun shouldUseOfflineNlu_allowsSimpleHighConfidenceWhenOnline() {
        val offline = OfflineNluRouter.Match(
            steps = listOf(AgentAction(action = "finish", message = "打开蓝牙")),
            confidence = 0.94,
            intent = "open_bluetooth_settings",
        )
        assertEquals(
            true,
            CommandRouteResolver.shouldUseOfflineNlu("打开蓝牙", offline, appContext = null),
        )
    }

    @Test
    fun shouldUseOfflineNlu_skipsMultiStepComposableCommand() {
        val offline = OfflineNluRouter.Match(
            steps = listOf(AgentAction(action = "open_app", targetText = "微信")),
            confidence = 0.94,
            intent = "open_app",
        )
        assertEquals(
            false,
            CommandRouteResolver.shouldUseOfflineNlu(
                "打开微信，给大女儿发消息说今晚回家吃饭",
                offline,
                appContext = null,
            ),
        )
    }

    @Test
    fun looksLikeMultiStep_detectsComposableWeChatMessage() {
        assertEquals(
            true,
            CommandRouteResolver.looksLikeMultiStepUtterance("打开微信，给大女儿发消息说今晚回家吃饭"),
        )
        assertEquals(
            true,
            CommandRouteResolver.looksLikeMultiStepUtterance("打开微信给大女儿发消息说回家吃饭"),
        )
        assertEquals(false, CommandRouteResolver.looksLikeMultiStepUtterance("打开微信"))
        assertEquals(false, CommandRouteResolver.looksLikeMultiStepUtterance("打开蓝牙"))
    }

    @Test
    fun buildClarifyMessage_usesFinishHint() {
        val route = CommandRouteResolver.Route(
            steps = listOf(
                AgentAction(action = "navigate_home"),
                AgentAction(action = "finish", message = "导航回家", finished = true),
            ),
            source = "template",
            confidence = 0.7,
        )
        val message = CommandRouteResolver.buildClarifyMessage("回家", route)
        assertEquals(true, message.contains("确认"))
    }
}
