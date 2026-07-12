package com.tetraploid.joyforold.agent

import android.content.Context
import java.io.File

class VisionDebugStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun debugDir(): File {
        val dir = File(appContext.getExternalFilesDir(null), DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listFrames(): List<VisionDebugFrame> {
        val dir = debugDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles { file ->
            file.isFile && file.name.endsWith(".jpg", ignoreCase = true)
        }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_FRAMES)
            ?.mapNotNull { file -> file.toFrame() }
            .orEmpty()
    }

    fun clearAll() {
        debugDir().listFiles()?.forEach { it.delete() }
    }

    private fun File.toFrame(): VisionDebugFrame? {
        val name = nameWithoutExtension
        val parts = name.split("_")
        if (parts.size < 3) return null
        val stepNo = parts[0].removePrefix("s").toIntOrNull() ?: 0
        val kind = parts[1]
        val detail = parts.drop(2).joinToString("_")
        val label = when (kind) {
            "llm" -> "步骤$stepNo · 发给 AI 的截图"
            "tap" -> "步骤$stepNo · AI 要点 tap ($detail)"
            "send" -> "步骤$stepNo · AI 要点 send ($detail)"
            else -> "步骤$stepNo · $kind $detail"
        }
        return VisionDebugFrame(
            id = name,
            filePath = absolutePath,
            stepNo = stepNo,
            kind = kind,
            label = label,
            timestampMs = lastModified(),
        )
    }

    companion object {
        private const val PREFS = "joy_vision_debug"
        private const val KEY_ENABLED = "enabled"
        private const val DIR_NAME = "agent_vision_debug"
        const val MAX_FRAMES = 40
    }
}
