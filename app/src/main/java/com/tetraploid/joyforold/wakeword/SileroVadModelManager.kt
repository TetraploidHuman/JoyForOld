package com.tetraploid.joyforold.wakeword

import android.content.Context
import android.util.Log
import com.tetraploid.joyforold.network.JoyHttpClients
import com.tetraploid.joyforold.network.downloadToFile
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SileroVadModelManager(
    context: Context,
    private val httpClient: HttpClient = JoyHttpClients.longDownload(),
) {
    private val logTag = "SileroVadModelMgr"
    private val appContext = context.applicationContext
    private val modelFile = File(appContext.filesDir, MODEL_FILE_NAME)

    suspend fun ensureReady(): String = withContext(Dispatchers.IO) {
        ensureReadyBlocking()
    }

    fun ensureReadyBlocking(): String {
        if (modelFile.exists() && modelFile.length() > 0L) {
            return modelFile.absolutePath
        }
        if (copyBundledModel()) {
            return modelFile.absolutePath
        }
        downloadModel()
        return modelFile.absolutePath
    }

    private fun copyBundledModel(): Boolean {
        return runCatching {
            appContext.assets.open(BUNDLED_ASSET_PATH).use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(logTag, "silero_vad copied from assets")
            true
        }.getOrDefault(false)
    }

    private fun downloadModel() {
        runBlocking {
            httpClient.downloadToFile(MODEL_URL, modelFile)
        }
        Log.i(logTag, "silero_vad downloaded to ${modelFile.absolutePath}")
    }

    companion object {
        private const val MODEL_FILE_NAME = "silero_vad.onnx"
        private const val BUNDLED_ASSET_PATH = "wakeword-sherpa/silero_vad.onnx"
        private const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
    }
}
