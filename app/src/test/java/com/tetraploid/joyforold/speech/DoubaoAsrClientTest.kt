package com.tetraploid.joyforold.speech

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
class DoubaoAsrClientTest {
    @Test
    fun parseAsrResponse_readsDefiniteUtterance() {
        val json = JSONObject(
            """
            {
              "result": {
                "text": "打开设置",
                "utterances": [
                  {"text": "打开", "definite": false},
                  {"text": "设置", "definite": true}
                ]
              }
            }
            """.trimIndent(),
        )

        val parsed = DoubaoAsrClient.parseAsrResponse(json)

        assertEquals("打开设置", parsed.text)
        assertTrue(parsed.hasDefiniteUtterance)
    }

    @Test
    fun parseAsrResponse_ignoresBlankDefiniteUtterance() {
        val json = JSONObject(
            """
            {
              "result": {
                "text": "你好",
                "utterances": [
                  {"text": "", "definite": true}
                ]
              }
            }
            """.trimIndent(),
        )

        val parsed = DoubaoAsrClient.parseAsrResponse(json)

        assertEquals("你好", parsed.text)
        assertFalse(parsed.hasDefiniteUtterance)
    }
}
