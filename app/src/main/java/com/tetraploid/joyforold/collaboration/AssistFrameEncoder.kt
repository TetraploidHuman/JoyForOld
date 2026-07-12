package com.tetraploid.joyforold.collaboration

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.tetraploid.joyforold.agent.PageScreenshotCapture
import java.io.ByteArrayOutputStream

data class AssistFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val format: String,
    val hash: Long,
    val seq: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AssistFrame
        return bytes.contentEquals(other.bytes) &&
            width == other.width &&
            height == other.height &&
            format == other.format &&
            hash == other.hash &&
            seq == other.seq
    }

    override fun hashCode(): Int = bytes.contentHashCode()
}

object AssistFrameEncoder {
    private const val TAG = "JoyForOld/AssistFrame"
    const val MAX_WIDTH_PX = 540
    /** 推帧循环间隔；与截图节流对齐，局域网环境下约 5.5 FPS。 */
    const val FRAME_LOOP_INTERVAL_MS = 180L
    private const val CAPTURE_INTERVAL_MS = FRAME_LOOP_INTERVAL_MS
    private const val WEBP_QUALITY = 82
    private const val JPEG_QUALITY = 78
    private const val HEARTBEAT_MS = 2_000L

    @Volatile
    private var lastCaptureAtMs = 0L

    @Volatile
    private var lastHash = 0L

    @Volatile
    private var lastPushAtMs = 0L

    @Volatile
    private var seq = 0

    suspend fun captureIfNeeded(
        service: AccessibilityService,
        force: Boolean = false,
    ): AssistFrame? {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastCaptureAtMs < CAPTURE_INTERVAL_MS) return null

        val bitmap = captureBitmap(service, force) ?: return null
        lastCaptureAtMs = now
        var scaled: Bitmap? = null
        return try {
            scaled = scaleDown(bitmap, MAX_WIDTH_PX)
            val webpBytes = encodeWebp(scaled)
            val jpegBytes = encodeJpeg(scaled)
            val (bytes, format) = chooseSmaller(webpBytes, jpegBytes)
            val hash = dHash(scaled)
            val duplicate = hash == lastHash
            val heartbeatDue = now - lastPushAtMs >= HEARTBEAT_MS
            if (!force && duplicate && !heartbeatDue) {
                null
            } else {
                lastHash = hash
                lastPushAtMs = now
                seq += 1
                AssistFrame(
                    bytes = bytes,
                    width = scaled.width,
                    height = scaled.height,
                    format = format,
                    hash = hash,
                    seq = seq,
                )
            }
        } finally {
            if (scaled != null && scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun decodeToBitmap(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private suspend fun captureBitmap(service: AccessibilityService, force: Boolean): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "takeScreenshot 需要 Android 11+")
            return null
        }
        return PageScreenshotCapture.captureBitmapForAssist(
            service = service,
            minIntervalMs = CAPTURE_INTERVAL_MS,
            force = force,
        )
    }

    private fun scaleDown(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val ratio = maxWidth.toFloat() / bitmap.width.toFloat()
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, maxWidth, targetHeight, true)
    }

    private fun encodeWebp(bitmap: Bitmap): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val stream = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, stream)
        return if (ok) stream.toByteArray() else null
    }

    private fun encodeJpeg(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return stream.toByteArray()
    }

    private fun chooseSmaller(webp: ByteArray?, jpeg: ByteArray): Pair<ByteArray, String> {
        if (webp == null || webp.size >= jpeg.size) return jpeg to "jpeg"
        return webp to "webp"
    }

    private fun dHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = small.getPixel(x, y)
                val right = small.getPixel(x + 1, y)
                val leftLum = luminance(left)
                val rightLum = luminance(right)
                if (leftLum > rightLum) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        if (small !== bitmap && !small.isRecycled) small.recycle()
        return hash
    }

    private fun luminance(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 30 + g * 59 + b * 11) / 100
    }
}
