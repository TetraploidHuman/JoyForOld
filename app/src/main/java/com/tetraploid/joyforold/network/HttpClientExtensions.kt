package com.tetraploid.joyforold.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import java.io.File
import java.io.OutputStream

suspend fun HttpClient.downloadToFile(url: String, destination: File, userAgent: String? = null) {
    val response = get(url) {
        userAgent?.let { header("User-Agent", it) }
    }
    if (!response.status.isSuccess()) {
        error("下载失败：HTTP ${response.status.value}")
    }
    destination.parentFile?.mkdirs()
    destination.writeBytes(response.readRawBytes())
}

suspend fun HttpClient.downloadToStream(url: String, output: OutputStream) {
    val response = get(url)
    if (!response.status.isSuccess()) {
        error("下载失败：HTTP ${response.status.value}")
    }
    output.write(response.readRawBytes())
}

suspend fun HttpClient.getText(url: String, userAgent: String? = null): String {
    val response = get(url) {
        userAgent?.let { header("User-Agent", it) }
    }
    if (!response.status.isSuccess()) {
        error("HTTP ${response.status.value}")
    }
    return response.bodyAsText()
}
