package com.tetraploid.joyforold.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

object JoyHttpClients {
    fun default(): HttpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 30_000
            endpoint.connectTimeout = 15_000
            endpoint.socketTimeout = 30_000
        }
    }

    fun llm(): HttpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 30_000
            endpoint.connectTimeout = 8_000
            endpoint.socketTimeout = 25_000
        }
    }

    fun longDownload(): HttpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 120_000
            endpoint.connectTimeout = 30_000
            endpoint.socketTimeout = 120_000
        }
    }

    fun websocket(): HttpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 0
            endpoint.connectTimeout = 15_000
            endpoint.socketTimeout = Long.MAX_VALUE
            endpoint.connectAttempts = 5
        }
        install(WebSockets) {
            pingIntervalMillis = 30_000
        }
    }

    fun quick(): HttpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 8_000
            endpoint.connectTimeout = 8_000
            endpoint.socketTimeout = 8_000
        }
    }
}
