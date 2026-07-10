package com.tetraploid.joyforold.wakeword

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SherpaOnnxModelManager(context: Context) {
    private val logTag = "SherpaOnnxModelMgr"
    private val appContext = context.applicationContext
    private val modelDir = File(appContext.filesDir, MODEL_DIR_NAME)
    private val versionFile = File(modelDir, "model.version")

    suspend fun ensureReady(
        keyword: String,
        keywordScore: Float = WakeWordConfigStore.DEFAULT_KEYWORD_SCORE,
        keywordThreshold: Float = WakeWordConfigStore.DEFAULT_KEYWORD_THRESHOLD,
    ): ModelFiles = withContext(Dispatchers.IO) {
        ensureBaseModelReady()
        writeKeywordsFile(keyword, keywordScore, keywordThreshold)
        ModelFiles(
            dir = modelDir,
            encoder = requireFile(
                predicate = { it.startsWith("encoder-") && it.endsWith(".onnx") },
                preferNonQuantized = true,
            ),
            decoder = requireFile(predicate = { it.startsWith("decoder-") && it.endsWith(".onnx") }),
            joiner = requireFile(
                predicate = { it.startsWith("joiner-") && it.endsWith(".onnx") },
                preferNonQuantized = true,
            ),
            tokens = File(modelDir, "tokens.txt"),
            keywords = File(modelDir, "keywords.txt"),
        )
    }

    fun modelHint(): String = modelDir.absolutePath

    fun currentModelVersion(): String = MODEL_VERSION

    private fun ensureBaseModelReady() {
        val needsRefresh = !hasCoreFiles() || readInstalledVersion() != MODEL_VERSION
        if (!needsRefresh) return
        Log.i(logTag, "installing bundled wakeword model, version=$MODEL_VERSION")
        copyBundledModelFromAssets()
        writeInstalledVersion(MODEL_VERSION)
    }

    private fun copyBundledModelFromAssets() {
        val parent = modelDir.parentFile ?: error("无法访问模型父目录")
        val tmpDir = File(parent, "${MODEL_DIR_NAME}.tmp")
        runCatching { tmpDir.deleteRecursively() }
        tmpDir.mkdirs()

        for (assetName in BUNDLED_MODEL_FILES) {
            val assetPath = "$BUNDLED_MODEL_ASSET_DIR/$assetName"
            appContext.assets.open(assetPath).use { input ->
                FileOutputStream(File(tmpDir, assetName)).use { output ->
                    input.copyTo(output)
                }
            }
        }

        if (!hasCoreFilesIn(tmpDir)) {
            tmpDir.deleteRecursively()
            error("内置唤醒模型文件不完整，请重新安装应用")
        }

        if (modelDir.exists()) modelDir.deleteRecursively()
        if (!tmpDir.renameTo(modelDir)) {
            tmpDir.deleteRecursively()
            error("安装内置唤醒模型失败")
        }
        Log.i(logTag, "bundled wakeword model installed from assets")
    }

    private fun hasCoreFiles(): Boolean = hasCoreFilesIn(modelDir)

    private fun hasCoreFilesIn(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        return File(dir, "tokens.txt").exists() &&
            File(dir, LEXICON_FILE_NAME).exists() &&
            dir.listFiles().orEmpty().any { it.name.startsWith("encoder-") && it.name.endsWith(".onnx") } &&
            dir.listFiles().orEmpty().any { it.name.startsWith("decoder-") && it.name.endsWith(".onnx") } &&
            dir.listFiles().orEmpty().any { it.name.startsWith("joiner-") && it.name.endsWith(".onnx") }
    }

    private fun writeKeywordsFile(keyword: String, keywordScore: Float, keywordThreshold: Float) {
        modelDir.mkdirs()
        val modelTokens = loadModelTokens()
        val lexicon = EnglishPhoneLexicon(File(modelDir, LEXICON_FILE_NAME))
        val lines = SherpaKeywordEncoder.encodeKeywordVariants(
            keyword = keyword,
            lexicon = lexicon,
            keywordScore = keywordScore,
            keywordThreshold = keywordThreshold,
        ).filter { SherpaKeywordEncoder.validateTokens(it, modelTokens) }
        require(lines.isNotEmpty()) { "唤醒词编码失败：没有可用 token 变体" }
        val primary = lines.first()
        referenceAssetForPhrase(keyword)?.let { assetPath ->
            KeywordTokenValidator.logReferenceMismatch(appContext, primary, assetPath, keyword)
        }
        File(modelDir, "keywords.txt").writeText(
            lines.joinToString("\n") + "\n",
            Charsets.UTF_8,
        )
        Log.i(logTag, "keywords updated: ${lines.size} variants for $keyword")
    }

    private fun referenceAssetForPhrase(keyword: String): String? {
        return when (keyword.trim().equals(DEFAULT_REFERENCE_PHRASE, ignoreCase = true)) {
            true -> TEXT2TOKEN_REFERENCE_HEY_CORTANA
            else -> null
        }
    }

    private fun loadModelTokens(): Set<String> {
        val tokensFile = File(modelDir, "tokens.txt")
        if (!tokensFile.exists()) return emptySet()
        return tokensFile.readLines()
            .mapNotNull { line -> line.substringBefore(" ").trim().ifBlank { null } }
            .toSet()
    }

    private fun readInstalledVersion(): String? {
        if (!versionFile.exists()) return null
        return versionFile.readText(Charsets.UTF_8).trim().ifBlank { null }
    }

    private fun writeInstalledVersion(version: String) {
        modelDir.mkdirs()
        versionFile.writeText("$version\n", Charsets.UTF_8)
    }

    private fun requireFile(
        predicate: (String) -> Boolean,
        preferNonQuantized: Boolean = false,
    ): File {
        val matches = modelDir.listFiles().orEmpty().filter { predicate(it.name) }
        if (preferNonQuantized) {
            matches.firstOrNull { !it.name.contains(".int8.") }?.let { return it }
        }
        return matches.firstOrNull() ?: error("缺少唤醒模型文件")
    }

    data class ModelFiles(
        val dir: File,
        val encoder: File,
        val decoder: File,
        val joiner: File,
        val tokens: File,
        val keywords: File,
    )

    companion object {
        const val MODEL_VERSION = "kws-zh-en-3M-2025-12-20-v1"
        private const val MODEL_DIR_NAME = "wakeword-sherpa"
        private const val LEXICON_FILE_NAME = "en.phone"
        private const val DEFAULT_REFERENCE_PHRASE = "Hey,Cortana"
        private const val TEXT2TOKEN_REFERENCE_HEY_CORTANA =
            "wakeword-sherpa/text2token-reference/hey-cortana.txt"
        private const val BUNDLED_MODEL_ASSET_DIR = "wakeword-sherpa"
        private val BUNDLED_MODEL_FILES = listOf(
            "encoder-epoch-13-avg-2-chunk-16-left-64.onnx",
            "decoder-epoch-13-avg-2-chunk-16-left-64.onnx",
            "joiner-epoch-13-avg-2-chunk-16-left-64.onnx",
            "tokens.txt",
            "en.phone",
        )
    }
}
