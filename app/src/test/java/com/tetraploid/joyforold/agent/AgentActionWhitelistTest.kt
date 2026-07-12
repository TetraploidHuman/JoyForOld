package com.tetraploid.joyforold.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActionWhitelistTest {
    @Test
    fun allows_registered_tools() {
        assertTrue(AgentActionWhitelist.isAllowed("click"))
        assertTrue(AgentActionWhitelist.isAllowed("tap"))
        assertTrue(AgentActionWhitelist.isAllowed("finish"))
    }

    @Test
    fun blocks_unknown_tools() {
        assertFalse(AgentActionWhitelist.isAllowed("tap_screen"))
        assertNotNull(AgentActionWhitelist.blockReason("tap_screen"))
    }

    @Test
    fun allows_system_actions() {
        assertNull(AgentActionWhitelist.blockReason("navigate_home"))
    }
}
