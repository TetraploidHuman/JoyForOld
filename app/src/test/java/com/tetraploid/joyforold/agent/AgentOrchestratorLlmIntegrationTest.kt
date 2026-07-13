package com.tetraploid.joyforold.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tetraploid.joyforold.testutil.NetworkTestSupport
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AgentOrchestratorLlmIntegrationTest {
    private lateinit var context: Context
    private lateinit var fakeLlm: FakeAgentLlmClient

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        NetworkTestSupport.enableInternet()
        fakeLlm = FakeAgentLlmClient()
    }

    @After
    fun tearDown() {
        NetworkTestSupport.reset()
    }

    @Test
    fun run_mockSystemIntent_setAlarm_withoutAccessibility() = runTest {
        fakeLlm.systemIntentHandler = { _, utterance ->
            if (utterance.contains("joy_system_alarm")) {
                SystemIntentAiResolver.Classification(
                    intent = "set_alarm",
                    confidence = 0.92,
                    timeHhmm = "08:00",
                    title = "起床",
                )
            } else {
                null
            }
        }

        val orchestrator = AgentTestFixtures.orchestrator(context, llmClient = fakeLlm)
        val result = orchestrator.run(
            userCommand = "joy_system_alarm 八点起床",
            apiKey = "test-key",
            appContext = context,
        )

        assertTrue(result.success)
        assertTrue(result.summary.contains("闹钟") || result.summary.contains("起床"))
        assertEquals(listOf("joy_system_alarm 八点起床"), fakeLlm.classifySystemIntentCalls)
        assertTrue(fakeLlm.beginTaskCommands.isEmpty())
    }

    @Test
    fun run_mockPresetIntent_openPaymentCode_withoutAccessibility() = runTest {
        fakeLlm.presetIntentHandler = { _, utterance ->
            if (utterance.contains("joy_preset_payment")) {
                "open_payment_code" to 0.95
            } else {
                null
            }
        }

        val orchestrator = AgentTestFixtures.orchestrator(context, llmClient = fakeLlm)
        val result = orchestrator.run(
            userCommand = "joy_preset_payment 打开付款",
            apiKey = "test-key",
            appContext = context,
        )

        assertEquals(listOf("joy_preset_payment 打开付款"), fakeLlm.classifyPresetIntentCalls)
        assertTrue(fakeLlm.beginTaskCommands.isEmpty())
        assertTrue(result.logs.isNotEmpty() || result.summary.isNotBlank())
    }

    @Test
    fun beginTask_recordsPlanningWithoutNetwork() = runTest {
        fakeLlm.enqueueFinishResponse("这是测试回复")
        val session = AgentConversationSession(rootCommand = "joy_planning_test")

        val json = fakeLlm.beginTask(
            apiKey = "test-key",
            conversation = session,
            userCommand = "joy_planning_test",
            pageContext = "",
            pageDiff = "",
            keyMemories = "",
            minimalPageContext = "",
        )

        assertEquals("finish", json.optString("action"))
        assertEquals("这是测试回复", json.optString("message"))
        assertEquals(listOf("joy_planning_test"), fakeLlm.beginTaskCommands)
        assertTrue(session.hasSystem())
    }
}
