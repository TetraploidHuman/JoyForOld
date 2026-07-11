package com.tetraploid.joyforold.ui.cortana

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetraploid.joyforold.ui.theme.CortanaColors

private val IdleOrbSize = 96.dp
private val CompactOrbSize = 64.dp

private fun lerpDp(start: Dp, end: Dp, fraction: Float): Dp = start + (end - start) * fraction

@Composable
fun CortanaHeroHeader(
    greeting: String,
    orbActive: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress by animateFloatAsState(
        targetValue = if (compact) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "heroProgress",
    )
    val topPadding = lerpDp(28.dp, 16.dp, progress)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(lerpDp(IdleOrbSize + 52.dp, CompactOrbSize + 40.dp, progress)),
    ) {
        val orbSize = lerpDp(IdleOrbSize, CompactOrbSize, progress)
        val centeredOrbX = (maxWidth - orbSize) / 2
        val orbX = lerpDp(centeredOrbX, 0.dp, progress)

        CortanaOrb(
            modifier = Modifier.offset(x = orbX, y = topPadding),
            size = orbSize,
            active = orbActive,
        )

        Text(
            text = greeting,
            color = CortanaColors.AccentMuted,
            fontSize = (18f + (17f - 18f) * progress).sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = topPadding + orbSize + lerpDp(20.dp, 0.dp, progress))
                .alpha(1f - progress),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = topPadding + (orbSize - 18.dp) / 2)
                .alpha(progress),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(CompactOrbSize + 14.dp))
            Text(
                text = greeting,
                color = CortanaColors.AccentMuted,
                fontSize = 17.sp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
