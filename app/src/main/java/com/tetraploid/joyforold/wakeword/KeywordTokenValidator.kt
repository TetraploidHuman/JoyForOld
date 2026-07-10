package com.tetraploid.joyforold.wakeword

import android.content.Context
import android.util.Log

/**
 * Validates sherpa-onnx keyword lines the same way [text2token] output is checked:
 * every modeling token before score/threshold/label must exist in tokens.txt.
 */
object KeywordTokenValidator {
    private const val LOG_TAG = "KeywordTokenValidator"

    fun validateLine(line: String, modelTokens: Set<String>): Boolean {
        if (modelTokens.isEmpty()) return true
        val tokens = modelingTokens(line)
        return tokens.isNotEmpty() && tokens.all { it in modelTokens }
    }

    fun modelingTokens(line: String): List<String> {
        return line.trim()
            .split(Regex("\\s+"))
            .takeWhile { token ->
                token.isNotBlank() &&
                    !token.startsWith(":") &&
                    !token.startsWith("#") &&
                    !token.startsWith("@")
            }
    }

    fun label(line: String): String? {
        return line.split(Regex("\\s+"))
            .firstOrNull { it.startsWith("@") }
            ?.removePrefix("@")
            ?.ifBlank { null }
    }

    /**
     * Compares encoded tokens against a bundled reference generated offline via sherpa-onnx-cli text2token.
     */
    fun matchesReference(context: Context, encodedLine: String, referenceAssetPath: String): Boolean {
        val reference = runCatching {
            context.assets.open(referenceAssetPath).bufferedReader().use { it.readText().trim() }
        }.getOrNull().orEmpty()
        if (reference.isBlank()) return true
        val encodedTokens = modelingTokens(encodedLine)
        val referenceTokens = modelingTokens(reference)
        return encodedTokens == referenceTokens
    }

    fun logReferenceMismatch(context: Context, encodedLine: String, referenceAssetPath: String, phrase: String) {
        if (matchesReference(context, encodedLine, referenceAssetPath)) return
        Log.w(
            LOG_TAG,
            "text2token reference mismatch for「$phrase」: encoded=${modelingTokens(encodedLine)}",
        )
    }
}
