package com.tetraploid.joyforold.ui.cortana

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tetraploid.joyforold.ui.theme.CortanaColors

@Composable
fun CortanaOrb(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    active: Boolean = true,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    val outerSpin by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "outerSpin",
    )
    val innerSpin by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "innerSpin",
    )
    val glowAlpha = if (active) 0.32f else 0.14f

    Canvas(modifier = modifier.size(size)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val outerRadius = this.size.minDimension / 2f * 0.88f
        val innerRadius = outerRadius * 0.58f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    CortanaColors.AccentGlow.copy(alpha = glowAlpha),
                    CortanaColors.Accent.copy(alpha = glowAlpha * 0.45f),
                    CortanaColors.Background,
                ),
                center = center,
                radius = outerRadius * 1.55f,
            ),
            radius = outerRadius * 1.55f,
            center = center,
        )

        rotate(outerSpin, center) {
            drawCircle(
                color = CortanaColors.Accent.copy(alpha = if (active) 0.95f else 0.45f),
                radius = outerRadius,
                center = center,
                style = Stroke(width = 5.5.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                color = CortanaColors.AccentGlow.copy(alpha = if (active) 0.85f else 0.35f),
                startAngle = 20f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                size = androidx.compose.ui.geometry.Size(outerRadius * 2, outerRadius * 2),
                style = Stroke(width = 5.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        rotate(innerSpin, center) {
            drawCircle(
                color = CortanaColors.AccentGlow.copy(alpha = if (active) 0.75f else 0.32f),
                radius = innerRadius,
                center = center,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                color = CortanaColors.OnBackground.copy(alpha = if (active) 0.55f else 0.25f),
                startAngle = 200f,
                sweepAngle = 80f,
                useCenter = false,
                topLeft = Offset(center.x - innerRadius, center.y - innerRadius),
                size = androidx.compose.ui.geometry.Size(innerRadius * 2, innerRadius * 2),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        drawCircle(
            color = CortanaColors.Background,
            radius = innerRadius * 0.48f,
            center = center,
        )
    }
}
