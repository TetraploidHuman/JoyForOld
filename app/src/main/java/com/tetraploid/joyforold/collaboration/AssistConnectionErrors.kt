package com.tetraploid.joyforold.collaboration

object AssistConnectionErrors {
    fun formatHttpFailure(t: Throwable, httpBase: String): String {
        val msg = t.message.orEmpty()
        val url = AssistEndpointUrls.normalizeHttpBase(httpBase)
        if (msg.contains("Failed to connect", ignoreCase = true) ||
            msg.contains("ENETUNREACH", ignoreCase = true) ||
            msg.contains("Network is unreachable", ignoreCase = true) ||
            msg.contains("EHOSTUNREACH", ignoreCase = true)
        ) {
            return buildString {
                append("无法连接协助服务器 $url。")
                append("请检查：①PC 上 assist-server 是否在运行；")
                append("②手机与 PC 在同一 WiFi；")
                append("③Windows 防火墙是否放行 8787 端口；")
                append("④协作页 HTTP 应为 http://192.168.1.47:8787")
            }
        }
        if (msg.contains("timeout", ignoreCase = true) || msg.contains("timed out", ignoreCase = true)) {
            return "连接协助服务器超时（$url），请检查网络后重试"
        }
        if (msg.contains("Cleartext", ignoreCase = true)) {
            return "协助服务器需使用 http:// 明文地址（$url）"
        }
        return msg.ifBlank { "协助服务器请求失败（$url）" }
    }

    fun formatWsFailure(message: String, wsBase: String, httpBase: String): String {
        val msg = message
        val ws = AssistEndpointUrls.normalizeWsBase(raw = wsBase, httpBase = httpBase)
        if (msg.contains("Failed to connect", ignoreCase = true) ||
            msg.contains("ENETUNREACH", ignoreCase = true) ||
            msg.contains("Network is unreachable", ignoreCase = true)
        ) {
            return buildString {
                append("WebSocket 无法连接 $ws。")
                append("请确认 assist-server 已启动，且 PUBLIC_WS_URL=ws://192.168.1.47:8787/ws")
            }
        }
        return msg.ifBlank { "WebSocket 连接失败（$ws）" }
    }
}
