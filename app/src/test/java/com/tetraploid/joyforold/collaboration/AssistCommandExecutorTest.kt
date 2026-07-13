package com.tetraploid.joyforold.collaboration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tetraploid.joyforold.accessibility.AccessibilityGateway
import com.tetraploid.joyforold.accessibility.AccessibilityGateways
import com.tetraploid.joyforold.agent.ActionExecutionResult
import com.tetraploid.joyforold.agent.AgentAction
import com.tetraploid.joyforold.agent.StructuredPageSnapshot
import com.tetraploid.joyforold.assist.protocol.AssistControlMessage
import kotlinx.coroutines.test.runTest
import org.junit.After
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
class AssistCommandExecutorTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        AccessibilityGateways.current?.let { AccessibilityGateways.unbind(it) }
    }

    @Test
    fun execute_withoutAccessibility_returnsFailure() = runTest {
        val executor = AssistCommandExecutor(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))

        val result = executor.execute(
            application = application,
            message = AssistControlMessage.tap(x = 100, y = 200),
        )

        assertFalse(result.success)
        assertEquals("无障碍服务未连接", result.detail)
    }

    @Test
    fun execute_tap_delegatesToToolRegistry() = runTest {
        val gateway = FakeGateway()
        AccessibilityGateways.bind(gateway)
        val executor = AssistCommandExecutor(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))

        val result = executor.execute(
            application = application,
            message = AssistControlMessage.tap(x = 100, y = 200),
        )

        assertTrue(result.success)
        assertEquals("tap", result.action)
    }

    private class FakeGateway : AccessibilityGateway {
        override fun context(): Application = ApplicationProvider.getApplicationContext()

        override fun captureStructuredSnapshots(): List<StructuredPageSnapshot> = emptyList()

        override fun mergeSnapshots(snapshots: List<StructuredPageSnapshot>) = null

        override fun captureBestStructuredSnapshot(): StructuredPageSnapshot? = null

        override fun snapshotCompactForAgent(): String = ""

        override fun snapshotForAgent(): String = ""

        override fun snapshotTreeForDebug(): String = ""

        override suspend fun captureScreenshotBase64(forceFresh: Boolean): String? = null

        override fun executeWithResult(action: AgentAction): ActionExecutionResult =
            ActionExecutionResult(true, "tap ok")

        override fun swipeNormalizedBlocking(x1: Int, y1: Int, x2: Int, y2: Int): String = "swipe ok"

        override suspend fun swipeDown(): String = "down"

        override suspend fun swipeUp(): String = "up"

        override fun performGlobalHome(): Boolean = true
    }
}
