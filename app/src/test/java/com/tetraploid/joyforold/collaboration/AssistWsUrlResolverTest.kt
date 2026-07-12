package com.tetraploid.joyforold.collaboration

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistWsUrlResolverTest {
    @Test
    fun prefersLocalWhenServerIsLoopback() {
        val resolved = AssistWsUrlResolver.resolve(
            serverWsUrl = "ws://127.0.0.1:8787/ws",
            localWsUrl = "ws://192.168.1.10:8787/ws",
        )
        assertEquals("ws://192.168.1.10:8787/ws", resolved)
    }

    @Test
    fun keepsServerWhenReachable() {
        val resolved = AssistWsUrlResolver.resolve(
            serverWsUrl = "ws://192.168.1.20:8787/ws",
            localWsUrl = "ws://192.168.1.10:8787/ws",
        )
        assertEquals("ws://192.168.1.20:8787/ws", resolved)
    }

    @Test
    fun normalizesMalformedLocalWsUrl() {
        val resolved = AssistWsUrlResolver.resolve(
            serverWsUrl = "ws://127.0.0.1:8787/ws",
            localWsUrl = "/192.168.1.47:8787",
            localHttpUrl = "http://192.168.1.47:8787",
        )
        assertEquals("ws://192.168.1.47:8787/ws", resolved)
    }
}
