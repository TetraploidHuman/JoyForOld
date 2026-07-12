package com.tetraploid.joyforold.agent

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VisionTapAnnotatorTest {
    @Test
    fun parseNormalizedCoords_accepts_comma_separated_values() {
        assertEquals(450 to 950, VisionTapAnnotator.parseNormalizedCoords("450,950"))
        assertEquals(480 to 190, VisionTapAnnotator.parseNormalizedCoords("480,190"))
    }

    @Test
    fun parseNormalizedCoords_rejects_out_of_range() {
        assertEquals(null, VisionTapAnnotator.parseNormalizedCoords("1200,500"))
        assertEquals(null, VisionTapAnnotator.parseNormalizedCoords("abc"))
    }

    @Test
    fun annotateTap_places_marker_at_normalized_position() {
        val bitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val annotated = VisionTapAnnotator.annotateTap(bitmap, xNorm = 450, yNorm = 950, label = "tap")
        val x = (450 / 1000f * annotated.width).toInt().coerceIn(0, annotated.width - 1)
        val y = (950 / 1000f * annotated.height).toInt().coerceIn(0, annotated.height - 1)
        val pixel = annotated.getPixel(x, y)
        assertTrue(Color.red(pixel) > 200)
        assertNotNull(VisionTapAnnotator.encodeJpegBase64(annotated))
    }
}
