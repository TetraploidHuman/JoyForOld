package com.tetraploid.joyforold.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AgentOrchestratorBehaviorTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun run_emptyCommand_returnsFailure() = runTest {
        val orchestrator = AgentTestFixtures.orchestrator(context)

        val result = orchestrator.run("", apiKey = "unused", appContext = context)

        assertFalse(result.success)
        assertEquals("请输入指令", result.summary)
    }

    @Test
    fun run_tellTime_withoutAccessibility_executesLocally() = runTest {
        val orchestrator = AgentTestFixtures.orchestrator(context)

        val result = orchestrator.run("现在几点", apiKey = "unused", appContext = context)

        assertTrue(result.success)
        assertTrue(result.summary.isNotBlank())
    }

    @Test
    fun init_restoresPendingFromSessionStore() {
        val sessionStore = AgentSessionStore(context)
        sessionStore.clearPending()
        sessionStore.savePending(AgentTestFixtures.pendingState())

        val orchestrator = AgentTestFixtures.orchestrator(context, sessionStore = sessionStore)

        assertTrue(orchestrator.hasPendingConfirm())
        assertEquals("你要在哪里打电话？", orchestrator.peekPendingPrompt())
    }

    @Test
    fun clearPendingUserReply_clearsRestoredPending() {
        val sessionStore = AgentSessionStore(context)
        sessionStore.clearPending()
        sessionStore.savePending(AgentTestFixtures.pendingState())

        val orchestrator = AgentTestFixtures.orchestrator(context, sessionStore = sessionStore)
        assertTrue(orchestrator.hasPendingConfirm())

        orchestrator.clearPendingUserReply()

        assertFalse(orchestrator.hasPendingConfirm())
        assertEquals(null, sessionStore.loadPending())
    }

    @Test
    fun restorePendingFromDisk_reloadsAfterExternalClear() {
        val sessionStore = AgentSessionStore(context)
        sessionStore.clearPending()
        sessionStore.savePending(AgentTestFixtures.pendingState())

        val orchestrator = AgentTestFixtures.orchestrator(context, sessionStore = sessionStore)
        orchestrator.clearPendingUserReply()
        assertFalse(orchestrator.hasPendingConfirm())

        sessionStore.savePending(
            AgentTestFixtures.pendingState(
                originalCommand = "打开微信",
                aiPrompt = "请确认是否继续",
            ),
        )
        orchestrator.restorePendingFromDisk()

        assertTrue(orchestrator.hasPendingConfirm())
        assertEquals("请确认是否继续", orchestrator.peekPendingPrompt())
        assertEquals("打开微信", orchestrator.peekPendingOriginalCommand())
    }
}
