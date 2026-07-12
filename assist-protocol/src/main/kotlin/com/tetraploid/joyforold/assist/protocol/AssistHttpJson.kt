package com.tetraploid.joyforold.assist.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object AssistHttpJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    inline fun <reified T> encode(value: T): String = json.encodeToString(value)

    inline fun <reified T> decode(text: String): T = json.decodeFromString(text)
}
