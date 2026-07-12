package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.agent.CommandRouteResolver.Route
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFastPathGuardTest {
    @Test
    fun needsPreview_onlyForLowRiskLocalRoutes() {
        val wifiRoute = Route(
            steps = listOf(
                AgentAction(action = "open_wifi_settings"),
                AgentAction(action = "finish", message = "已打开无线网络设置", finished = true),
            ),
            source = "offline_nlu",
            confidence = 0.94,
        )
        assertTrue(LocalFastPathGuard.needsPreview(wifiRoute))

        val dialRoute = Route(
            steps = listOf(
                AgentAction(action = "dial_contact", targetText = "女儿"),
                AgentAction(
                    action = "finish",
                    message = "准备拨号",
                    finished = true,
                    needsBinaryConfirm = true,
                ),
            ),
            source = "offline_nlu",
            confidence = 0.94,
        )
        assertFalse(LocalFastPathGuard.needsPreview(dialRoute))
    }

    @Test
    fun isUndoable_forOpenSettingsAndApps() {
        val steps = listOf(
            AgentAction(action = "open_bluetooth_settings"),
            AgentAction(action = "finish", finished = true),
        )
        assertTrue(LocalFastPathGuard.isUndoable(steps))
    }

    @Test
    fun isUndoable_forNavigateHomeAndOpenApp() {
        val navigateHome = listOf(
            AgentAction(action = "navigate_home"),
            AgentAction(action = "finish", finished = true),
        )
        assertTrue(LocalFastPathGuard.isUndoable(navigateHome))

        val openApp = listOf(
            AgentAction(action = "open_app", targetText = "微信"),
            AgentAction(action = "finish", finished = true),
        )
        assertTrue(LocalFastPathGuard.isUndoable(openApp))
    }

    @Test
    fun isUndoable_notForDialContact() {
        val steps = listOf(
            AgentAction(action = "dial_contact", targetText = "女儿"),
            AgentAction(action = "finish", finished = true),
        )
        assertFalse(LocalFastPathGuard.isUndoable(steps))
    }
}
