package com.tetraploid.joyforold.wakeword

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SileroVadModelManager(context: Context) {
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
        copyBundledModel()
        return modelFile.absolutePath
    }

    private fun copyBundledModel() {
        appContext.assets.open(BUNDLED_ASSET_PATH).use { input ->
            FileOutputStream(modelFile).use { output ->
                input.copyTo(output)
            }
        }
        require(modelFile.exists() && modelFile.length() > 0L) {
            "内置 Silero VAD 模型缺失，请重新安装应用"
        }
        Log.i(logTag, "silero_vad copied from bundled assets")
    }

    companion object {
        private const val MODEL_FILE_NAME = "silero_vad.onnx"
        private const val BUNDLED_ASSET_PATH = "wakeword-sherpa/silero_vad.onnx"
    }
}
