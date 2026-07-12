package com.tetraploid.joyforold.agent

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentActionGuardVisionTest {
    @Test
    fun blocksReadTreeInVisionMode() {
        assertNotNull(AgentActionGuard.blockedInVisionMode(AgentAction(action = "read_tree")))
    }

    @Test
    fun blocksClickAndFindOnPageInVisionMode() {
        assertNotNull(AgentActionGuard.blockedInVisionMode(AgentAction(action = "click", targetText = "搜索")))
        assertNotNull(AgentActionGuard.blockedInVisionMode(AgentAction(action = "find_on_page", targetText = "张三")))
    }

    @Test
    fun allowsTapInVisionMode() {
        assertNull(AgentActionGuard.blockedInVisionMode(AgentAction(action = "tap", targetText = "500,100")))
    }
}
