package com.tetraploid.joyforold.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * 在发给 LLM 的截图上标注归一化 tap 坐标（0~1000），便于调试视觉 Agent。
 */
object VisionTapAnnotator {
    fun parseNormalizedCoords(targetText: String?): Pair<Int, Int>? {
        val raw = targetText?.trim().orEmpty()
        if (raw.isBlank()) return null
        val parts = raw.split(',', '，', ' ').map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val x = parts[0].toIntOrNull() ?: return null
        val y = parts[1].toIntOrNull() ?: return null
        if (x !in 0..1000 || y !in 0..1000) return null
        return x to y
    }

    fun decodeBase64Jpeg(base64: String): Bitmap? = runCatching {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    fun annotateTap(
        source: Bitmap,
        xNorm: Int,
        yNorm: Int,
        label: String,
    ): Bitmap {
        val mutable = if (source.config == Bitmap.Config.ARGB_8888 && source.isMutable) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }
        val canvas = Canvas(mutable)
        val x = xNorm / 1000f * mutable.width
        val y = yNorm / 1000f * mutable.height
        val stroke = max(4f, mutable.width / 160f)
        val radius = max(20f, mutable.width / 28f)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            this.strokeWidth = stroke
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(72, 255, 0, 0)
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            textSize = max(26f, mutable.width / 22f)
            isFakeBoldText = true
            setShadowLayer(6f, 2f, 2f, Color.BLACK)
        }

        canvas.drawCircle(x, y, radius, fillPaint)
        canvas.drawCircle(x, y, radius, ringPaint)
        canvas.drawLine(x - radius * 1.4f, y, x + radius * 1.4f, y, ringPaint)
        canvas.drawLine(x, y - radius * 1.4f, x, y + radius * 1.4f, ringPaint)

        val textY = min(mutable.height - 8f, max(textPaint.textSize + 8f, y - radius - 12f))
        canvas.drawText(label, 12f, textY, textPaint)
        canvas.drawText("($xNorm,$yNorm)", 12f, textY + textPaint.textSize + 6f, textPaint)
        return mutable
    }

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 90): Boolean = runCatching {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        true
    }.getOrDefault(false)

    fun encodeJpegBase64(bitmap: Bitmap, quality: Int = 90): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }
}
