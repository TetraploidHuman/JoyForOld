package com.tetraploid.joyforold.collaboration

import java.net.URI

object AssistWsUrlResolver {
    /**
     * 服务端默认可能返回 127.0.0.1；真机应优先使用 App 本地配置的 WS 地址。
     */
    fun resolve(serverWsUrl: String?, localWsUrl: String, localHttpUrl: String = ""): String {
        val server = AssistEndpointUrls.normalizeWsBase(
            raw = serverWsUrl.orEmpty(),
            httpBase = localHttpUrl,
        )
        val local = AssistEndpointUrls.normalizeWsBase(
            raw = localWsUrl,
            httpBase = localHttpUrl,
        )
        if (server.isBlank()) return local
        if (local.isNotBlank() && isLoopbackUrl(server) && !isLoopbackUrl(local)) {
            return local
        }
        return server
    }

    private fun isLoopbackUrl(url: String): Boolean {
        return runCatching {
            val host = URI(url.replace("ws://", "http://").replace("wss://", "https://")).host
                ?.lowercase()
                .orEmpty()
            host in setOf("127.0.0.1", "localhost", "0.0.0.0", "::1")
        }.getOrDefault(
            url.contains("127.0.0.1", ignoreCase = true) ||
                url.contains("localhost", ignoreCase = true),
        )
    }
}
