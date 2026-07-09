package com.tetraploid.joyforold.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AgentSessionStoreTest {
    private lateinit var store: AgentSessionStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = AgentSessionStore(context)
        store.clearPending()
    }

    @Test
    fun saveAndLoadPending_roundTrip() {
        val session = AgentConversationSession(rootCommand = "给610打电话")
        session.seedSystem("system")
        session.addUser("【用户指令】给610打电话")
        val snapshot = StructuredPageSnapshot(
            packageName = "com.tencent.mobileqq",
            appHint = "当前为 QQ",
            clickables = listOf("610"),
            editables = emptyList(),
            visibleTexts = listOf("610"),
            sendButtons = emptyList(),
            fingerprint = "fp",
        )
        val state = PendingAgentState(
            originalCommand = "给610打电话",
            aiPrompt = "你要在哪里打电话？",
            session = session,
            previousSnapshot = snapshot,
        )

        store.savePending(state)
        val loaded = store.loadPending()

        assertNotNull(loaded)
        assertEquals("给610打电话", loaded!!.originalCommand)
        assertEquals("你要在哪里打电话？", loaded.aiPrompt)
        assertTrue(loaded.session.hasSystem())
        assertEquals("com.tencent.mobileqq", loaded.previousSnapshot?.packageName)
    }

    @Test
    fun clearPending_removesSavedState() {
        val state = PendingAgentState(
            originalCommand = "test",
            aiPrompt = "prompt",
            session = AgentConversationSession(rootCommand = "test"),
            previousSnapshot = null,
        )
        store.savePending(state)
        store.clearPending()
        assertNull(store.loadPending())
    }
}
