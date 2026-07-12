package com.tetraploid.joyforold.collaboration

object AssistEndpointUrls {
    fun normalizeHttpBase(raw: String, default: String = ""): String {
        var url = raw.trim().ifBlank { default.trim() }
        if (url.isBlank()) return ""
        url = url.trimStart('/')
        if (url.startsWith("//")) {
            url = "http:$url"
        }
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "http://$url"
        }
        url = url.replace(Regex("^http:///+", RegexOption.IGNORE_CASE), "http://")
        url = url.replace(Regex("^https:///+", RegexOption.IGNORE_CASE), "https://")
        return url.trimEnd('/')
    }

    fun normalizeWsBase(raw: String, httpBase: String, default: String = ""): String {
        var url = raw.trim().ifBlank { default.trim() }
        if (url.isBlank() && httpBase.isNotBlank()) {
            url = httpBase.replace(Regex("^http", RegexOption.IGNORE_CASE), "ws")
        }
        url = url.trimStart('/')
        if (url.startsWith("//")) {
            url = "ws:$url"
        }
        url = when {
            url.startsWith("ws://", ignoreCase = true) || url.startsWith("wss://", ignoreCase = true) -> url
            url.startsWith("http://", ignoreCase = true) ->
                url.replace(Regex("^http", RegexOption.IGNORE_CASE), "ws")
            url.startsWith("https://", ignoreCase = true) ->
                url.replace(Regex("^https", RegexOption.IGNORE_CASE), "wss")
            else -> "ws://$url"
        }
        url = url.replace(Regex("^ws:///+", RegexOption.IGNORE_CASE), "ws://")
        url = url.replace(Regex("^wss:///+", RegexOption.IGNORE_CASE), "wss://")
        url = url.trimEnd('/')
        if (!url.endsWith("/ws", ignoreCase = true)) {
            url += "/ws"
        }
        return url
    }
}
