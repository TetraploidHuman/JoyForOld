package com.tetraploid.joyforold.assist.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AssistControlMessage(
    val type: String,
    val seq: Int = 0,
    val w: Int = 0,
    val h: Int = 0,
    val fmt: String = "",
    val ts: Long = 0L,
    val x: Int = 0,
    val y: Int = 0,
    val x2: Int = 0,
    val y2: Int = 0,
    val name: String = "",
    val text: String = "",
    val action: String = "",
    val success: Boolean = false,
    val detail: String = "",
    val status: String = "",
    val message: String = "",
    val peer: String = "",
    val displayName: String = "",
    val reason: String = "",
) {
    companion object {
        const val TYPE_FRAME_META = "frame_meta"
        const val TYPE_TAP = "tap"
        const val TYPE_SWIPE = "swipe"
        const val TYPE_ACTION = "action"
        const val TYPE_TYPE_TEXT = "type_text"
        const val TYPE_COMMAND = "command"
        const val TYPE_ACTION_RESULT = "action_result"
        const val TYPE_AGENT_STATUS = "agent_status"
        const val TYPE_PEER_JOINED = "peer_joined"
        const val TYPE_SESSION_ENDED = "session_ended"
        const val TYPE_HANGUP = "hangup"

        fun frameMeta(seq: Int, w: Int, h: Int, fmt: String, ts: Long = System.currentTimeMillis()) =
            AssistControlMessage(type = TYPE_FRAME_META, seq = seq, w = w, h = h, fmt = fmt, ts = ts)

        fun tap(x: Int, y: Int) = AssistControlMessage(type = TYPE_TAP, x = x, y = y)

        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int) =
            AssistControlMessage(type = TYPE_SWIPE, x = x1, y = y1, x2 = x2, y2 = y2)

        fun action(name: String) = AssistControlMessage(type = TYPE_ACTION, name = name)

        fun typeText(text: String) = AssistControlMessage(type = TYPE_TYPE_TEXT, text = text)

        fun command(text: String) = AssistControlMessage(type = TYPE_COMMAND, text = text)

        fun actionResult(action: String, success: Boolean, detail: String) =
            AssistControlMessage(type = TYPE_ACTION_RESULT, action = action, success = success, detail = detail)

        fun agentStatus(status: String, message: String) =
            AssistControlMessage(type = TYPE_AGENT_STATUS, status = status, message = message)

        fun peerJoined(peer: String, displayName: String) =
            AssistControlMessage(type = TYPE_PEER_JOINED, peer = peer, displayName = displayName)

        fun sessionEnded(reason: String) =
            AssistControlMessage(type = TYPE_SESSION_ENDED, reason = reason)

        fun hangup(reason: String = "user") =
            AssistControlMessage(type = TYPE_HANGUP, reason = reason)
    }
}

object AssistMessageJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(message: AssistControlMessage): String = json.encodeToString(message)

    fun decode(text: String): AssistControlMessage? = runCatching {
        json.decodeFromString<AssistControlMessage>(text)
    }.getOrNull()

    fun typeOf(text: String): String? = runCatching {
        val obj = json.decodeFromString<JsonObject>(text)
        obj["type"]?.jsonPrimitive?.content
    }.getOrNull()
}
