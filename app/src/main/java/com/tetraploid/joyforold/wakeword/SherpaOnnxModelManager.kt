package com.tetraploid.joyforold.wakeword

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class SherpaOnnxModelManager(
    context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
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

    suspend fun preloadModelIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            ensureBaseModelReady()
            true
        }.onFailure {
            Log.w(logTag, "preload model failed: ${it.message}")
        }.getOrDefault(false)
    }

    private fun ensureBaseModelReady() {
        val needsRefresh = !hasCoreFiles() || readInstalledVersion() != MODEL_VERSION
        if (!needsRefresh) return
        Log.i(logTag, "refreshing model files, targetVersion=$MODEL_VERSION")
        if (!copyBundledModelFromAssets()) {
            downloadAndExtractModel()
        }
        writeInstalledVersion(MODEL_VERSION)
    }

    private fun copyBundledModelFromAssets(): Boolean {
        val assets = appContext.assets
        val rootFiles = runCatching { assets.list(BUNDLED_MODEL_ASSET_DIR) }.getOrNull().orEmpty()
        if (rootFiles.isEmpty()) {
            Log.i(logTag, "no bundled wakeword model in assets")
            return false
        }
        val parent = modelDir.parentFile ?: run {
            Log.w(logTag, "modelDir parent missing")
            return false
        }
        val tmpDir = File(parent, "${MODEL_DIR_NAME}.tmp")
        runCatching { tmpDir.deleteRecursively() }
        tmpDir.mkdirs()
        copyAssetDirRecursively(BUNDLED_MODEL_ASSET_DIR, tmpDir)

        // quick sanity: avoid installing a partial copy
        val hasTokens = File(tmpDir, "tokens.txt").exists()
        val hasEncoder = tmpDir.listFiles().orEmpty().any { it.name.startsWith("encoder-") && it.name.endsWith(".onnx") }
        val hasDecoder = tmpDir.listFiles().orEmpty().any { it.name.startsWith("decoder-") && it.name.endsWith(".onnx") }
        val hasJoiner = tmpDir.listFiles().orEmpty().any { it.name.startsWith("joiner-") && it.name.endsWith(".onnx") }
        if (!hasTokens || !hasEncoder || !hasDecoder || !hasJoiner) {
            Log.w(logTag, "bundled model copy incomplete, will fallback to download")
            runCatching { tmpDir.deleteRecursively() }
            return false
        }
        val hasCurrentModel = tmpDir.listFiles().orEmpty().any { file ->
            file.name.contains("encoder-epoch-13") || file.name.contains("zh-en-3M-2025")
        }
        if (!hasCurrentModel) {
            Log.i(logTag, "bundled model is outdated ($MODEL_VERSION), will download")
            runCatching { tmpDir.deleteRecursively() }
            return false
        }

        if (modelDir.exists()) modelDir.deleteRecursively()
        if (!tmpDir.renameTo(modelDir)) {
            Log.w(logTag, "rename tmp model dir failed, will fallback to download")
            runCatching { tmpDir.deleteRecursively() }
            return false
        }
        Log.i(logTag, "wakeword model copied from assets (atomic)")
        return true
    }

    private fun copyAssetDirRecursively(assetPath: String, targetDir: File) {
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            appContext.assets.open(assetPath).use { input ->
                FileOutputStream(targetDir).use { output ->
                    input.copyTo(output)
                }
            }
            return
        }
        targetDir.mkdirs()
        for (name in children) {
            val childAssetPath = "$assetPath/$name"
            val childTarget = File(targetDir, name)
            copyAssetDirRecursively(childAssetPath, childTarget)
        }
    }

    private fun hasCoreFiles(): Boolean {
        if (!modelDir.exists() || !modelDir.isDirectory) return false
        return File(modelDir, "tokens.txt").exists() &&
            modelDir.listFiles().orEmpty().any { it.name.startsWith("encoder-") && it.name.endsWith(".onnx") } &&
            modelDir.listFiles().orEmpty().any { it.name.startsWith("decoder-") && it.name.endsWith(".onnx") } &&
            modelDir.listFiles().orEmpty().any { it.name.startsWith("joiner-") && it.name.endsWith(".onnx") }
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

    private fun downloadAndExtractModel() {
        val parentDir = modelDir.parentFile ?: error("无法访问模型父目录")
        modelDir.mkdirs()
        val archive = File(parentDir, "wakeword-model.tar.bz2")
        val expectedSha256 = fetchExpectedSha256()
        val request = Request.Builder().url(MODEL_URL).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载唤醒模型失败：HTTP ${response.code}")
            val body = response.body ?: error("下载唤醒模型失败：空响应")
            FileOutputStream(archive).use { output ->
                body.byteStream().use { input -> input.copyTo(output) }
            }
        }
        val actualSha256 = sha256Hex(archive)
        if (!expectedSha256.equals(actualSha256, ignoreCase = true)) {
            archive.delete()
            error("下载唤醒模型校验失败：sha256 不匹配（expected=$expectedSha256, actual=$actualSha256）")
        }

        extractTarBz2(archive, parentDir)
        archive.delete()

        val extracted = File(parentDir, MODEL_FOLDER_NAME)
        if (!extracted.exists()) error("解压唤醒模型失败：未找到目录")

        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }
        extracted.renameTo(modelDir)
    }

    private fun fetchExpectedSha256(): String {
        val request = Request.Builder().url(CHECKSUM_URL).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("获取唤醒模型校验信息失败：HTTP ${response.code}")
            val text = response.body?.string().orEmpty()
            val line = text.lineSequence().firstOrNull { it.contains(MODEL_ARCHIVE_NAME) }
                ?: error("校验文件缺少条目：$MODEL_ARCHIVE_NAME")
            // checksum.txt 格式：<sha256> <filename>
            return line.trim().split(Regex("\\s+")).firstOrNull().orEmpty().also {
                if (it.length < 32) error("校验文件格式异常：$line")
            }
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractTarBz2(archive: File, destination: File) {
        FileInputStream(archive).use { fis ->
            BZip2CompressorInputStream(fis).use { bzip ->
                TarArchiveInputStream(bzip).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        val target = File(destination, entry.name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out -> tar.copyTo(out) }
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }
        }
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
        private const val MODEL_FOLDER_NAME = "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"
        private const val MODEL_ARCHIVE_NAME = "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2"
        private const val LEXICON_FILE_NAME = "en.phone"
        private const val DEFAULT_REFERENCE_PHRASE = "Hey,Cortana"
        private const val TEXT2TOKEN_REFERENCE_HEY_CORTANA =
            "wakeword-sherpa/text2token-reference/hey-cortana.txt"
        private const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/" +
                MODEL_ARCHIVE_NAME
        private const val CHECKSUM_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/checksum.txt"
        private const val BUNDLED_MODEL_ASSET_DIR = "wakeword-sherpa"
    }
}

