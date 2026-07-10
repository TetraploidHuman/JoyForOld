package com.tetraploid.joyforold.speech

/**
 * Debounces Doubao ASR endpoint (`definite`) signals so brief pauses mid-utterance
 * do not immediately end recording.
 */
internal class AsrEndpointStopTracker(
    private val debounceAfterDefiniteMs: Long,
    private val minRecordBeforeStopMs: Long,
    private val tailChunksRequired: Int,
) {
    private var recordingStartedAtMs = 0L
    private var lastText = ""
    private var lastTextChangeAtMs = 0L
    private var stopPending = false
    private var endpointConfirmed = false
    private var tailChunks = 0

    fun reset(nowMs: Long) {
        recordingStartedAtMs = nowMs
        lastText = ""
        lastTextChangeAtMs = nowMs
        stopPending = false
        endpointConfirmed = false
        tailChunks = 0
    }

    fun onPartial(text: String, definite: Boolean, nowMs: Long): EndpointStopAction {
        if (text.isNotBlank() && text != lastText) {
            lastText = text
            lastTextChangeAtMs = nowMs
            stopPending = false
            endpointConfirmed = false
            tailChunks = 0
        }
        if (definite && text.isNotBlank()) {
            stopPending = true
        }
        if (!stopPending) return EndpointStopAction.Continue

        val quietMs = nowMs - lastTextChangeAtMs
        val recordMs = nowMs - recordingStartedAtMs
        if (recordMs < minRecordBeforeStopMs || quietMs < debounceAfterDefiniteMs) {
            return EndpointStopAction.Continue
        }
        if (!endpointConfirmed) {
            endpointConfirmed = true
            tailChunks = 0
        }
        tailChunks++
        return if (tailChunks >= tailChunksRequired) {
            EndpointStopAction.Stop
        } else {
            EndpointStopAction.Continue
        }
    }
}

internal enum class EndpointStopAction {
    Continue,
    Stop,
}
