package com.tetraploid.joyforold.agent

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
