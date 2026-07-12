package com.tetraploid.joyforold.collaboration

import com.tetraploid.joyforold.assist.protocol.AssistControlMessage

/** 儿女端接收推帧时的 FPS / 延迟统计。 */
class AssistStreamStats {
    private val receiveTimesMs = ArrayDeque<Long>()
    private var lastLatencyMs: Long = -1L

    fun onFrameReceived(meta: AssistControlMessage?) {
        val now = System.currentTimeMillis()
        receiveTimesMs.addLast(now)
        while (receiveTimesMs.size > 30) {
            receiveTimesMs.removeFirst()
        }
        val sentAt = meta?.ts ?: 0L
        if (sentAt > 0L) {
            val latency = now - sentAt
            if (latency in 0..5_000) {
                lastLatencyMs = latency
            }
        }
    }

    fun fps(): Float {
        if (receiveTimesMs.size < 2) return 0f
        val span = receiveTimesMs.last() - receiveTimesMs.first()
        if (span <= 0L) return 0f
        return (receiveTimesMs.size - 1) * 1000f / span.toFloat()
    }

    fun latencyMs(): Long = lastLatencyMs

    fun reset() {
        receiveTimesMs.clear()
        lastLatencyMs = -1L
    }
}
