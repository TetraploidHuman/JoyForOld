package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LlmMultimodalMessageTest {
    @Test
    fun userMessage_textOnly() {
        val json = LlmMultimodalMessage.userMessage("hello", null)
        assertEquals("user", json.getString("role"))
        assertEquals("hello", json.getString("content"))
    }

    @Test
    fun userMessage_withImage() {
        val json = LlmMultimodalMessage.userMessage("see screen", "abc123")
        assertEquals("user", json.getString("role"))
        val content = json.getJSONArray("content")
        assertEquals(2, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("see screen", content.getJSONObject(0).getString("text"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertTrue(
            content.getJSONObject(1)
                .getJSONObject("image_url")
                .getString("url")
                .startsWith("data:image/jpeg;base64,abc123"),
        )
    }

    @Test
    fun responsesUserMessage_withImage() {
        val json = LlmMultimodalMessage.responsesUserMessage("see screen", "abc123")
        assertEquals("user", json.getString("role"))
        val content = json.getJSONArray("content")
        assertEquals(2, content.length())
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        assertEquals("see screen", content.getJSONObject(0).getString("text"))
        assertEquals("input_image", content.getJSONObject(1).getString("type"))
        assertTrue(
            content.getJSONObject(1)
                .getString("image_url")
                .startsWith("data:image/jpeg;base64,abc123"),
        )
    }
}
