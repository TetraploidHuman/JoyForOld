package com.tetraploid.joyforold.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tetraploid.joyforold.testutil.NetworkTestSupport
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CommandRouteResolverLlmIntegrationTest {
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
    fun resolve_mockPresetIntent_openPaymentCode() = runTest {
        fakeLlm.presetIntentHandler = { _, utterance ->
            if (utterance.contains("joy_preset_payment")) {
                "open_payment_code" to 0.92
            } else {
                null
            }
        }

        val route = CommandRouteResolver.resolve(
            command = "joy_preset_payment 打开付款",
            apiKey = "test-key",
            llmClient = fakeLlm,
            appContext = context,
        )

        assertNotNull(route)
        assertEquals("preset_ai", route!!.source)
        assertEquals("open_payment_code", route.steps.first().action)
        assertTrue(route.confidence >= CommandRouteResolver.AUTO_EXECUTE_THRESHOLD)
        assertEquals(listOf("joy_preset_payment 打开付款"), fakeLlm.classifyPresetIntentCalls)
    }

    @Test
    fun resolve_mockSystemIntent_setAlarm() = runTest {
        fakeLlm.systemIntentHandler = { _, utterance ->
            if (utterance.contains("joy_system_alarm")) {
                SystemIntentAiResolver.Classification(
                    intent = "set_alarm",
                    confidence = 0.9,
                    timeHhmm = "08:00",
                    title = "起床",
                )
            } else {
                null
            }
        }

        val route = CommandRouteResolver.resolve(
            command = "joy_system_alarm 八点起床",
            apiKey = "test-key",
            llmClient = fakeLlm,
            appContext = context,
        )

        assertNotNull(route)
        assertEquals("system_ai", route!!.source)
        assertEquals("set_alarm", route.steps.first().action)
        assertEquals("08:00", route.steps.first().targetText)
        assertEquals("起床", route.steps.first().inputText)
        assertEquals(listOf("joy_system_alarm 八点起床"), fakeLlm.classifySystemIntentCalls)
    }

    @Test
    fun resolve_lowConfidencePresetIntent_isIgnored() = runTest {
        fakeLlm.presetIntentHandler = { _, _ -> "navigate_home" to 0.4 }

        val route = CommandRouteResolver.resolve(
            command = "joy_preset_low_confidence",
            apiKey = "test-key",
            llmClient = fakeLlm,
            appContext = context,
        )

        assertEquals(null, route)
        assertEquals(listOf("joy_preset_low_confidence"), fakeLlm.classifyPresetIntentCalls)
    }
}
