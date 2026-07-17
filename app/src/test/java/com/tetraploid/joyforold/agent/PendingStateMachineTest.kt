package com.tetraploid.joyforold.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tetraploid.joyforold.accessibility.AccessibilityGateway
import com.tetraploid.joyforold.accessibility.AccessibilityGateways
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
class PendingStateMachineTest {
    private lateinit var context: Context
    private lateinit var sessionStore: AgentSessionStore
    private lateinit var machine: PendingStateMachine
    private val fakeGateway = FakeAccessibilityGateway()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sessionStore = AgentSessionStore(context)
        sessionStore.clearPending()
        machine = PendingStateMachine(sessionStore)
        machine.restoreFromDisk()
        AccessibilityGateways.bind(fakeGateway)
    }

    @After
    fun tearDown() {
        AccessibilityGateways.unbind(fakeGateway)
        sessionStore.clearPending()
    }

    @Test
    fun saveUserConfirmPending_persistsAndPeeks() {
        machine.saveUserConfirmPending(
            originalCommand = "发微信",
            aiPrompt = "要发送吗？",
            session = AgentConversationSession(rootCommand = "发微信"),
            previousSnapshot = null,
            needsBinaryConfirm = true,
        )

        assertTrue(machine.hasPending())
        assertEquals(PendingKind.USER_CONFIRM, machine.peekPendingKind())
        assertEquals("要发送吗？", machine.peekPendingPrompt())
        assertTrue(machine.peekPendingNeedsBinaryConfirm())
        assertEquals("发微信", sessionStore.loadPending()?.originalCommand)
    }

    @Test
    fun handleRouteClarifyReply_confirm_executesSteps() = runTest {
        val steps = listOf(AgentAction(action = "tell_time"))
        machine.saveRouteClarifyPending("打开蓝牙", "确认打开蓝牙吗？", steps, fakeGateway)

        val executor = RecordingPendingExecutor()
        val result = machine.resumePending(
            pending = machine.current()!!,
            command = "确认",
            apiKey = "unused",
            service = fakeGateway,
            runContext = AgentRunContext(),
            onProgress = null,
            executor = executor,
        )

        assertTrue(result.success)
        assertFalse(machine.hasPending())
        assertEquals(1, executor.localStepCalls)
        assertEquals(steps, executor.lastSteps)
    }

    @Test
    fun handleRouteClarifyReply_cancel_clearsPending() = runTest {
        machine.saveRouteClarifyPending(
            "打开蓝牙",
            "确认吗？",
            listOf(AgentAction(action = "open_bluetooth_settings")),
            fakeGateway,
        )

        val result = machine.resumePending(
            pending = machine.current()!!,
            command = "取消",
            apiKey = "unused",
            service = fakeGateway,
            runContext = AgentRunContext(),
            onProgress = null,
            executor = RecordingPendingExecutor(),
        )

        assertTrue(result.success)
        assertFalse(machine.hasPending())
        assertEquals("好的，已取消", result.summary)
    }

    @Test
    fun handleTaskAbandonReply_abandon_runsDeferredCommand() = runTest {
        machine.save(
            PendingAgentState(
                originalCommand = "新指令",
                aiPrompt = "放弃旧任务？",
                session = AgentConversationSession(rootCommand = "新指令"),
                previousSnapshot = null,
                kind = PendingKind.TASK_ABANDON,
                deferredCommand = "现在几点",
            ),
        )

        val executor = RecordingPendingExecutor()
        val result = machine.handleTaskAbandonReply(
            command = "放弃",
            apiKey = "unused",
            service = fakeGateway,
            runContext = AgentRunContext(),
            onProgress = null,
            executor = executor,
        )

        assertTrue(result.success)
        assertFalse(machine.hasPending())
        assertEquals(1, executor.newCommandCalls)
        assertEquals("现在几点", executor.lastNewCommand)
    }

    private class FakeAccessibilityGateway : AccessibilityGateway {
        override fun context(): Context = ApplicationProvider.getApplicationContext()

        override fun captureStructuredSnapshots(): List<StructuredPageSnapshot> = emptyList()

        override fun mergeSnapshots(snapshots: List<StructuredPageSnapshot>) = null

        override fun captureBestStructuredSnapshot(): StructuredPageSnapshot? = null

        override fun snapshotCompactForAgent(): String = ""

        override fun snapshotForAgent(): String = ""

        override fun snapshotTreeForDebug(): String = ""

        override fun setContinuousUiTreeLogcatEnabled(enabled: Boolean) = Unit

        override suspend fun captureScreenshotBase64(forceFresh: Boolean): String? = null

        override fun executeWithResult(action: AgentAction): ActionExecutionResult =
            ActionExecutionResult(true, "ok")

        override fun swipeNormalizedBlocking(x1: Int, y1: Int, x2: Int, y2: Int): String = "ok"

        override suspend fun swipeDown(): String = "ok"

        override suspend fun swipeUp(): String = "ok"

        override fun performGlobalHome(): Boolean = true
    }

    private class RecordingPendingExecutor : PendingExecutor {
        var localStepCalls = 0
        var newCommandCalls = 0
        var lastSteps: List<AgentAction> = emptyList()
        var lastNewCommand: String? = null

        override suspend fun resumeUserConfirm(
            pending: PendingAgentState,
            command: String,
            apiKey: String,
            service: AccessibilityGateway,
            runContext: AgentRunContext,
            onProgress: ((Int, String) -> Unit)?,
        ): AgentRunResult = AgentRunResult(false, "unused", emptyList())

        override suspend fun executeLocalSteps(
            context: Context,
            service: AccessibilityGateway,
            steps: List<AgentAction>,
            originalCommand: String,
            runContext: AgentRunContext,
        ): AgentRunResult {
            localStepCalls++
            lastSteps = steps
            return AgentRunResult(true, "done", emptyList())
        }

        override suspend fun runNewCommand(
            command: String,
            apiKey: String,
            service: AccessibilityGateway,
            runContext: AgentRunContext,
            onProgress: ((Int, String) -> Unit)?,
        ): AgentRunResult {
            newCommandCalls++
            lastNewCommand = command
            return AgentRunResult(true, "done", emptyList())
        }

        override suspend fun runDisambiguatedIntent(
            command: String,
            intentId: String,
            apiKey: String,
            appContext: Context,
            runContext: AgentRunContext,
            onProgress: ((Int, String) -> Unit)?,
        ): AgentRunResult = AgentRunResult(false, "unused", emptyList())

        override suspend fun runNavPoiPick(
            poiIntentId: String,
            originalCommand: String,
            appContext: Context,
            runContext: AgentRunContext,
            onProgress: ((Int, String) -> Unit)?,
        ): AgentRunResult = AgentRunResult(false, "unused", emptyList())
    }
}
