package com.tetraploid.joyforold.di

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tetraploid.joyforold.agent.AgentLlmClient
import com.tetraploid.joyforold.agent.AgentOrchestrator
import com.tetraploid.joyforold.agent.AgentRuntime
import com.tetraploid.joyforold.agent.AgentSessionStore
import com.tetraploid.joyforold.agent.DeepSeekClient
import com.tetraploid.joyforold.agent.FakeAgentLlmClient
import com.tetraploid.joyforold.testutil.NetworkTestSupport
import com.tetraploid.joyforold.data.ApiKeyStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.get
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class KoinDiGraphTest : KoinTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        NetworkTestSupport.enableInternet()
        KoinTestSupport.startAppKoin(context)
        get<AgentSessionStore>().clearPending()
    }

    @After
    fun tearDown() {
        NetworkTestSupport.reset()
        KoinTestSupport.stopAppKoin()
    }

    @Test
    fun agentModule_resolvesSingletons() {
        val orchestratorA = get<AgentOrchestrator>()
        val orchestratorB = get<AgentOrchestrator>()
        val runtimeA = get<AgentRuntime>()
        val runtimeB = get<AgentRuntime>()
        val llmA = get<AgentLlmClient>()
        val llmB = get<AgentLlmClient>()

        assertSame(orchestratorA, orchestratorB)
        assertSame(runtimeA, runtimeB)
        assertSame(llmA, llmB)
        assertTrue(llmA is DeepSeekClient)
        assertNotNull(orchestratorA)
        assertNotNull(runtimeA)
    }

    @Test
    fun agentRuntimeLocator_returnsSameSingleton() {
        assertSame(get<AgentRuntime>(), agentRuntime())
    }

    @Test
    fun extraModule_canOverrideAgentLlmClient() {
        val fake = FakeAgentLlmClient()
        KoinTestSupport.stopAppKoin()
        KoinTestSupport.startAppKoin(
            context,
            extraModules = listOf(
                module {
                    single<AgentLlmClient> { fake }
                },
            ),
        )

        assertSame(fake, get<AgentLlmClient>())
    }

    @Test
    fun koinOrchestrator_usesFakeLlmClient() = runTest {
        val fake = FakeAgentLlmClient().apply {
            presetIntentHandler = { _, utterance ->
                if (utterance.contains("joy_preset_payment")) {
                    "open_payment_code" to 0.95
                } else {
                    null
                }
            }
        }
        KoinTestSupport.stopAppKoin()
        KoinTestSupport.startAppKoin(
            context,
            extraModules = listOf(
                module {
                    single<AgentLlmClient> { fake }
                },
            ),
        )
        get<AgentSessionStore>().clearPending()

        val orchestrator = get<AgentOrchestrator>()
        val result = orchestrator.run(
            userCommand = "joy_preset_payment 打开付款",
            apiKey = "test-key",
            appContext = context,
        )

        assertEquals(listOf("joy_preset_payment 打开付款"), fake.classifyPresetIntentCalls)
        assertTrue(fake.beginTaskCommands.isEmpty())
        assertTrue(result.logs.isNotEmpty() || result.summary.isNotBlank())
    }

    @Test
    fun runtime_initIfNeeded_loadsPersistedApiKey() = runBlocking {
        val application = context as Application
        val apiKeyStore = get<ApiKeyStore>()
        val runtime = get<AgentRuntime>()

        apiKeyStore.saveApiKey("di-test-key")
        runtime.initIfNeeded(application)

        assertEquals("di-test-key", runtime.state.first().apiKey)
    }
}
