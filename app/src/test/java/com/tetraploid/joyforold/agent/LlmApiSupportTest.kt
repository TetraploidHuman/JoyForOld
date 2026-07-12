package com.tetraploid.joyforold.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LlmApiSupportTest {
    @Test
    fun usesResponsesApi_detectsVolcEndpoint() {
        assertTrue(
            LlmApiSupport.usesResponsesApi("https://ark.cn-beijing.volces.com/api/v3/responses"),
        )
        assertFalse(
            LlmApiSupport.usesResponsesApi("https://open.bigmodel.cn/api/paas/v4/chat/completions"),
        )
    }

    @Test
    fun buildPlanningRequestBody_responsesApiUsesInstructionsAndInput() {
        val body = LlmApiSupport.buildPlanningRequestBody(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3/responses",
            model = "doubao-seed-2-0-mini-260428",
            systemInstructions = "system rules",
            chatMessages = JSONArray(),
            responsesInput = JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", "hello"))
            },
            maxTokens = 1024,
        )

        assertEquals("doubao-seed-2-0-mini-260428", body.getString("model"))
        assertEquals("system rules", body.getString("instructions"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertEquals(1024, body.getInt("max_output_tokens"))
        assertEquals("json_object", body.getJSONObject("text").getJSONObject("format").getString("type"))
        assertEquals(1, body.getJSONArray("input").length())
    }

    @Test
    fun extractAssistantContent_parsesResponsesOutputText() {
        val response = JSONObject()
            .put(
                "output",
                JSONArray().apply {
                    put(
                        JSONObject()
                            .put("type", "message")
                            .put("role", "assistant")
                            .put(
                                "content",
                                JSONArray().apply {
                                    put(
                                        JSONObject()
                                            .put("type", "output_text")
                                            .put("text", """{"action":"wait"}"""),
                                    )
                                },
                            ),
                    )
                },
            )
            .toString()

        val content = LlmApiSupport.extractAssistantContent(
            response,
            "https://ark.cn-beijing.volces.com/api/v3/responses",
        )

        assertEquals("""{"action":"wait"}""", content)
    }

    @Test
    fun extractAssistantContent_fallsBackToChatCompletions() {
        val response = JSONObject()
            .put(
                "choices",
                JSONArray().apply {
                    put(
                        JSONObject().put(
                            "message",
                            JSONObject()
                                .put("role", "assistant")
                                .put("content", """{"action":"finish"}"""),
                        ),
                    )
                },
            )
            .toString()

        val content = LlmApiSupport.extractAssistantContent(
            response,
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
        )

        assertEquals("""{"action":"finish"}""", content)
    }
}
