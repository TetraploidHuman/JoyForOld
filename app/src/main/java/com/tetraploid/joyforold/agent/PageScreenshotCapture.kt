package com.tetraploid.joyforold.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 无障碍树不可读时，用 AccessibilityService 截屏供多模态模型观察。
 */
object PageScreenshotCapture {
    const val JPEG_QUALITY = 72
    const val MAX_WIDTH_PX = 720
    const val ASSIST_MIN_INTERVAL_MS = 180L
    private const val TAG = "JoyForOld/Vision"
    private const val MIN_INTERVAL_MS = 1_200L
    private const val RETRY_DELAY_MS = 1_300L

    @Volatile
    private var lastCaptureAtMs = 0L

    @Volatile
    private var assistLastCaptureAtMs = 0L

    @Volatile
    private var cachedBase64: String? = null

    private val captureMutex = Mutex()

    fun invalidateCache() {
        cachedBase64 = null
        lastCaptureAtMs = 0L
    }

    suspend fun captureBase64Jpeg(
        service: AccessibilityService,
        forceFresh: Boolean = false,
    ): String? = captureMutex.withLock {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "takeScreenshot 需要 Android 11+")
            return@withLock cachedBase64
        }

        if (forceFresh) {
            invalidateCache()
        }

        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastCaptureAtMs
        if (sinceLast in 1 until MIN_INTERVAL_MS) {
            delay(MIN_INTERVAL_MS - sinceLast)
        }

        val cached = cachedBase64
        if (!forceFresh && !cached.isNullOrBlank() && SystemClock.elapsedRealtime() - lastCaptureAtMs < MIN_INTERVAL_MS) {
            return@withLock cached
        }

        var result = captureBase64JpegApi30(service)
        if (result.isNullOrBlank()) {
            delay(RETRY_DELAY_MS)
            result = captureBase64JpegApi30(service)
        }

        if (!result.isNullOrBlank()) {
            cachedBase64 = result
            lastCaptureAtMs = SystemClock.elapsedRealtime()
            Log.i(TAG, "screenshot ok (${result.length} chars base64)")
            return@withLock result
        }

        Log.w(TAG, "screenshot failed; ${if (cached.isNullOrBlank()) "no cache" else "reuse cache"}")
        cached
    }

    /** 远程协助推帧：更短间隔、直接返回 Bitmap，不走 Agent 截图缓存。 */
    suspend fun captureBitmapForAssist(
        service: AccessibilityService,
        minIntervalMs: Long = ASSIST_MIN_INTERVAL_MS,
        force: Boolean = false,
    ): Bitmap? = captureMutex.withLock {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "takeScreenshot 需要 Android 11+")
            return@withLock null
        }
        if (!force) {
            val now = SystemClock.elapsedRealtime()
            val sinceLast = now - assistLastCaptureAtMs
            if (sinceLast in 1 until minIntervalMs) {
                delay(minIntervalMs - sinceLast)
            }
        }
        val bitmap = captureBitmapApi30(service)
        if (bitmap != null) {
            assistLastCaptureAtMs = SystemClock.elapsedRealtime()
        }
        bitmap
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureBase64JpegApi30(service: AccessibilityService): String? {
        val bitmap = captureBitmapApi30(service) ?: return null
        return try {
            encodeJpegBase64(bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureBitmapApi30(service: AccessibilityService): Bitmap? =
        suspendCoroutine { continuation ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace,
                            )
                            if (bitmap == null) {
                                Log.w(TAG, "screenshot bitmap null")
                                continuation.resume(null)
                                return
                            }
                            continuation.resume(bitmap)
                        } catch (error: Exception) {
                            Log.w(TAG, "screenshot bitmap error: ${error.message}")
                            continuation.resume(null)
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot onFailure code=$errorCode")
                        continuation.resume(null)
                    }
                },
            )
        }

    internal fun encodeJpegBase64(bitmap: Bitmap): String {
        val scaled = scaleDown(bitmap, MAX_WIDTH_PX)
        val recycleScaled = scaled !== bitmap
        return try {
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (recycleScaled && !scaled.isRecycled) scaled.recycle()
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / bitmap.width.toFloat()
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, maxWidth, targetHeight, true)
    }

    internal fun clearCacheForTests() {
        invalidateCache()
    }
}
