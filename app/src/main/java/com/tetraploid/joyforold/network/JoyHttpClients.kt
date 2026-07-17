package com.tetraploid.joyforold.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.TimeUnit

object JoyHttpClients {
    fun default(): HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
                writeTimeout(30, TimeUnit.SECONDS)
            }
        }
    }

    fun llm(): HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(8, TimeUnit.SECONDS)
                readTimeout(25, TimeUnit.SECONDS)
                writeTimeout(10, TimeUnit.SECONDS)
                callTimeout(30, TimeUnit.SECONDS)
            }
        }
    }

    fun longDownload(): HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(120, TimeUnit.SECONDS)
                writeTimeout(120, TimeUnit.SECONDS)
            }
        }
    }

    fun websocket(): HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(0, TimeUnit.MILLISECONDS)
                writeTimeout(30, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
            }
        }
        install(WebSockets) {
            pingIntervalMillis = 30_000
        }
    }

    fun quick(): HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(8, TimeUnit.SECONDS)
                readTimeout(8, TimeUnit.SECONDS)
                writeTimeout(8, TimeUnit.SECONDS)
            }
        }
    }
}
