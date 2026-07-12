package com.tetraploid.joyforold.agent

data class VisionDebugFrame(
    val id: String,
    val filePath: String,
    val stepNo: Int,
    val kind: String,
    val label: String,
    val timestampMs: Long,
)
