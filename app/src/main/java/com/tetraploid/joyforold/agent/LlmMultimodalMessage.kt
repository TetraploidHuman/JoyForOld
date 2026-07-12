package com.tetraploid.joyforold.agent

import org.json.JSONArray
import org.json.JSONObject

object LlmMultimodalMessage {
    fun userMessage(text: String, imageBase64Jpeg: String?): JSONObject {
        if (imageBase64Jpeg.isNullOrBlank()) {
            return JSONObject().put("role", "user").put("content", text)
        }
        return JSONObject()
            .put("role", "user")
            .put(
                "content",
                JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", text))
                    put(
                        JSONObject()
                            .put("type", "image_url")
                            .put(
                                "image_url",
                                JSONObject()
                                    .put("url", "data:image/jpeg;base64,$imageBase64Jpeg"),
                            ),
                    )
                },
            )
    }

    /** 火山方舟 Responses API：input_image / input_text */
    fun responsesUserMessage(text: String, imageBase64Jpeg: String?): JSONObject {
        if (imageBase64Jpeg.isNullOrBlank()) {
            return JSONObject().put("role", "user").put("content", text)
        }
        return JSONObject()
            .put("role", "user")
            .put(
                "content",
                JSONArray().apply {
                    put(JSONObject().put("type", "input_text").put("text", text))
                    put(
                        JSONObject()
                            .put("type", "input_image")
                            .put("image_url", "data:image/jpeg;base64,$imageBase64Jpeg"),
                    )
                },
            )
    }
}
