package com.tetraploid.joyforold.agent

import org.json.JSONArray
import org.json.JSONObject

object LlmApiSupport {
    fun usesResponsesApi(baseUrl: String): Boolean =
        baseUrl.contains("/api/v3/responses", ignoreCase = true)

    fun buildPlanningRequestBody(
        baseUrl: String,
        model: String,
        systemInstructions: String,
        chatMessages: JSONArray,
        responsesInput: JSONArray,
        maxTokens: Int,
        jsonObjectOutput: Boolean = true,
    ): JSONObject {
        return if (usesResponsesApi(baseUrl)) {
            JSONObject().apply {
                put("model", model)
                put("temperature", 0.2)
                put("thinking", JSONObject().put("type", "disabled"))
                put("max_output_tokens", maxTokens)
                if (jsonObjectOutput) {
                    put(
                        "text",
                        JSONObject().put(
                            "format",
                            JSONObject().put("type", "json_object"),
                        ),
                    )
                }
                if (systemInstructions.isNotBlank()) {
                    put("instructions", systemInstructions)
                }
                put("input", responsesInput)
            }
        } else {
            JSONObject().apply {
                put("model", model)
                put("temperature", 0.2)
                put("stream", false)
                if (jsonObjectOutput) {
                    put("response_format", JSONObject().put("type", "json_object"))
                }
                put("max_tokens", maxTokens)
                put("messages", chatMessages)
            }
        }
    }

    fun buildSimpleRequestBody(
        baseUrl: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        jsonObjectOutput: Boolean = false,
    ): JSONObject {
        return if (usesResponsesApi(baseUrl)) {
            JSONObject().apply {
                put("model", model)
                put("temperature", 0.2)
                put("thinking", JSONObject().put("type", "disabled"))
                put("max_output_tokens", maxTokens)
                if (jsonObjectOutput) {
                    put(
                        "text",
                        JSONObject().put(
                            "format",
                            JSONObject().put("type", "json_object"),
                        ),
                    )
                }
                if (systemPrompt.isNotBlank()) {
                    put("instructions", systemPrompt)
                }
                put(
                    "input",
                    JSONArray().apply {
                        put(JSONObject().put("role", "user").put("content", userPrompt))
                    },
                )
            }
        } else {
            JSONObject().apply {
                put("model", model)
                put("temperature", 0.2)
                put("stream", false)
                if (jsonObjectOutput) {
                    put("response_format", JSONObject().put("type", "json_object"))
                }
                put("max_tokens", maxTokens)
                put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", systemPrompt))
                        put(JSONObject().put("role", "user").put("content", userPrompt))
                    },
                )
            }
        }
    }

    fun extractAssistantContent(responseBody: String, baseUrl: String): String {
        val root = JSONObject(responseBody)
        if (usesResponsesApi(baseUrl)) {
            extractFromResponsesOutput(root)?.let { return it }
        }
        return extractFromChatCompletions(root, responseBody)
    }

    private fun extractFromResponsesOutput(root: JSONObject): String? {
        val output = root.optJSONArray("output") ?: return null
        for (index in output.length() - 1 downTo 0) {
            val item = output.optJSONObject(index) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (partIndex in 0 until content.length()) {
                val part = content.optJSONObject(partIndex) ?: continue
                if (part.optString("type") != "output_text") continue
                val text = part.optString("text", "").trim()
                if (text.isNotBlank()) return unwrapJsonFence(text)
            }
        }
        return null
    }

    private fun extractFromChatCompletions(root: JSONObject, responseBody: String): String {
        val choices = root.optJSONArray("choices")
            ?: throw IllegalStateException("API 响应缺少 choices/output：${responseBody.take(300)}")

        if (choices.length() == 0) {
            throw IllegalStateException("API choices 为空")
        }

        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw IllegalStateException("API 响应缺少 message")

        val content = message.optString("content", "").trim()
        if (content.isNotBlank()) return unwrapJsonFence(content)

        val reasoning = message.optString("reasoning_content", "").trim()
        if (reasoning.isNotBlank()) return unwrapJsonFence(reasoning)

        throw IllegalStateException("API 返回空 content")
    }

    private fun unwrapJsonFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }
}
