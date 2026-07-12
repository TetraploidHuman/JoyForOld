package com.tetraploid.joyforold.collaboration

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistEndpointUrlsTest {
    @Test
    fun normalizesHttpWithoutScheme() {
        assertEquals(
            "http://192.168.1.47:8787",
            AssistEndpointUrls.normalizeHttpBase("192.168.1.47:8787"),
        )
    }

    @Test
    fun normalizesHttpWithLeadingSlash() {
        assertEquals(
            "http://192.168.1.47:8787",
            AssistEndpointUrls.normalizeHttpBase("/192.168.1.47:8787"),
        )
    }

    @Test
    fun normalizesWsWithoutScheme() {
        assertEquals(
            "ws://192.168.1.47:8787/ws",
            AssistEndpointUrls.normalizeWsBase(
                raw = "192.168.1.47:8787",
                httpBase = "http://192.168.1.47:8787",
            ),
        )
    }

    @Test
    fun normalizesWsFromHttp() {
        assertEquals(
            "ws://192.168.1.47:8787/ws",
            AssistEndpointUrls.normalizeWsBase(
                raw = "http://192.168.1.47:8787",
                httpBase = "http://192.168.1.47:8787",
            ),
        )
    }

    @Test
    fun normalizesWsWithLeadingSlash() {
        assertEquals(
            "ws://192.168.1.47:8787/ws",
            AssistEndpointUrls.normalizeWsBase(
                raw = "/192.168.1.47:8787",
                httpBase = "http://192.168.1.47:8787",
            ),
        )
    }
}
