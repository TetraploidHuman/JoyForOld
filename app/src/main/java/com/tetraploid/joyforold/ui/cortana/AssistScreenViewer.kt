package com.tetraploid.joyforold.ui.cortana

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tetraploid.joyforold.ui.theme.CortanaColors
import kotlin.math.abs

@Composable
fun AssistScreenViewer(
    frameBytes: ByteArray?,
    onTapNormalized: (Int, Int) -> Unit,
    onSwipeNormalized: (x1: Int, y1: Int, x2: Int, y2: Int) -> Unit,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val bitmap = remember(frameBytes) {
        frameBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val scrollBlocker = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = available
        }
    }
    val sizeModifier = if (fullscreen) {
        Modifier.fillMaxSize()
    } else {
        Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp, max = 520.dp)
    }

    Box(
        modifier = modifier
            .then(sizeModifier)
            .background(if (fullscreen) CortanaColors.Background else CortanaColors.Surface)
            .nestedScroll(scrollBlocker)
            .onSizeChanged { viewSize = it }
            .pointerInput(bitmap, viewSize, touchSlop) {
                if (bitmap == null || viewSize.width == 0 || viewSize.height == 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var totalDrag = Offset.Zero
                    var pointerId = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (!change.pressed) break
                        val delta = change.position - change.previousPosition
                        totalDrag += delta
                        change.consume()
                    }
                    val imageRect = fittedImageRect(
                        viewWidth = viewSize.width,
                        viewHeight = viewSize.height,
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                    )
                    if (!imageRect.contains(down.position)) return@awaitEachGesture
                    fun toNorm(offset: Offset): Pair<Int, Int> {
                        val xNorm = ((offset.x - imageRect.left) / imageRect.width * 1000f)
                            .toInt().coerceIn(0, 1000)
                        val yNorm = ((offset.y - imageRect.top) / imageRect.height * 1000f)
                            .toInt().coerceIn(0, 1000)
                        return xNorm to yNorm
                    }
                    if (totalDrag.getDistance() < touchSlop) {
                        val (xNorm, yNorm) = toNorm(down.position)
                        onTapNormalized(xNorm, yNorm)
                    } else if (abs(totalDrag.y) >= abs(totalDrag.x)) {
                        val start = toNorm(down.position)
                        val end = toNorm(down.position + totalDrag)
                        onSwipeNormalized(start.first, start.second, end.first, end.second)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "老人屏幕",
                modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text("等待老人端屏幕画面...", color = CortanaColors.OnBackgroundMuted)
        }
    }
}

fun fittedImageRect(
    viewWidth: Int,
    viewHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
): Rect {
    if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
        return Rect.Zero
    }
    val viewAspect = viewWidth.toFloat() / viewHeight
    val imageAspect = imageWidth.toFloat() / imageHeight
    return if (imageAspect > viewAspect) {
        val displayHeight = viewWidth / imageAspect
        val top = (viewHeight - displayHeight) / 2f
        Rect(0f, top, viewWidth.toFloat(), top + displayHeight)
    } else {
        val displayWidth = viewHeight * imageAspect
        val left = (viewWidth - displayWidth) / 2f
        Rect(left, 0f, left + displayWidth, viewHeight.toFloat())
    }
}
