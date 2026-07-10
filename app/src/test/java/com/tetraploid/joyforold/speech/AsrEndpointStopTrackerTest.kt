package com.tetraploid.joyforold.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrEndpointStopTrackerTest {
    @Test
    fun definite_doesNotStopUntilQuietDebounceExpires() {
        val tracker = AsrEndpointStopTracker(
            debounceAfterDefiniteMs = 2000L,
            minRecordBeforeStopMs = 1000L,
            tailChunksRequired = 3,
        )
        var now = 0L
        tracker.reset(now)

        now += 500L
        assertEquals(EndpointStopAction.Continue, tracker.onPartial("打开", definite = false, nowMs = now))

        now += 800L
        assertEquals(EndpointStopAction.Continue, tracker.onPartial("打开设置", definite = true, nowMs = now))

        now += 1500L
        assertEquals(EndpointStopAction.Continue, tracker.onPartial("打开设置", definite = true, nowMs = now))

        now += 600L
        assertEquals(EndpointStopAction.Continue, tracker.onPartial("打开设置", definite = true, nowMs = now))

        now += 700L
        assertEquals(EndpointStopAction.Continue, tracker.onPartial("打开设置", definite = true, nowMs = now))

        now += 200L
        assertEquals(EndpointStopAction.Stop, tracker.onPartial("打开设置", definite = true, nowMs = now))
    }

    @Test
    fun newPartialAfterDefinite_resetsStopPending() {
        val tracker = AsrEndpointStopTracker(
            debounceAfterDefiniteMs = 1000L,
            minRecordBeforeStopMs = 500L,
            tailChunksRequired = 2,
        )
        var now = 0L
        tracker.reset(now)

        now += 1000L
        tracker.onPartial("帮我打开", definite = true, nowMs = now)

        now += 1200L
        assertEquals(EndpointStopAction.Continue, tracker.onPartial("帮我打开微信", definite = false, nowMs = now))

        now += 1500L
        tracker.onPartial("帮我打开微信", definite = true, nowMs = now)
        now += 200L
        assertEquals(EndpointStopAction.Stop, tracker.onPartial("帮我打开微信", definite = true, nowMs = now))
    }
}
