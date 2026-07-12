package com.tetraploid.joyforold.offline.nlu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object OfflineNluModelManager {
    private const val ASSET_DIR = "nlu"
    private const val MODEL_FILE = "intent_classifier.onnx"
    private const val LABELS_FILE = "intent_labels.json"
    private const val CONFIG_FILE = "encoder_config.json"
    private const val VOCAB_FILE = "vocab.txt"

    @Volatile
    private var classifier: OnnxIntentClassifier? = null

    fun isReady(context: Context): Boolean = resolveModelFile(context).exists()

    suspend fun getClassifier(context: Context): OnnxIntentClassifier? {
        classifier?.let { return it }
        return withContext(Dispatchers.IO) {
            synchronized(this@OfflineNluModelManager) {
                classifier?.let { return@withContext it }
                classifier = loadClassifier(context.applicationContext)
                classifier
            }
        }
    }

    fun reset() {
        synchronized(this) {
            classifier?.close()
            classifier = null
        }
    }

    private fun loadClassifier(appContext: Context): OnnxIntentClassifier? {
        val modelFile = ensureModelFile(appContext) ?: return null
        val labels = readAssetText(appContext, "$ASSET_DIR/$LABELS_FILE") ?: return null
        val config = readAssetText(appContext, "$ASSET_DIR/$CONFIG_FILE") ?: return null
        val bytes = runCatching { modelFile.readBytes() }.getOrNull() ?: return null
        val vocabLines = readAssetLines(appContext, "$ASSET_DIR/$VOCAB_FILE")
        return OnnxIntentClassifier.create(bytes, labels, config, vocabLines)
    }

    private fun ensureModelFile(context: Context): File? {
        val target = resolveModelFile(context)
        if (target.exists() && target.length() > 0L) return target
        return runCatching {
            context.assets.open("$ASSET_DIR/$MODEL_FILE").use { input ->
                target.parentFile?.mkdirs()
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target
        }.getOrNull()
    }

    private fun resolveModelFile(context: Context): File {
        return File(context.filesDir, "$ASSET_DIR/$MODEL_FILE")
    }

    private fun readAssetText(context: Context, assetPath: String): String? {
        return runCatching {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    private fun readAssetLines(context: Context, assetPath: String): List<String>? {
        return runCatching {
            context.assets.open(assetPath).bufferedReader().readLines()
        }.getOrNull()
    }
}
