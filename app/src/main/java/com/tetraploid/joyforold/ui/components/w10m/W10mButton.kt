package com.tetraploid.joyforold.ui.components.w10m

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tetraploid.joyforold.ui.theme.CortanaColors

enum class W10mButtonStyle {
    /** Solid accent fill — primary CTA. */
    Primary,
    /** Thin border, transparent fill — secondary actions. */
    Secondary,
    /** Text-only accent — links / tertiary. */
    Subtle,
}

/**
 * Flat, sharp-cornered Windows 10 Mobile style button.
 */
@Composable
fun W10mButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: W10mButtonStyle = W10mButtonStyle.Secondary,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RectangleShape
    val label: @Composable () -> Unit = {
        Text(
            text = text,
            fontSize = W10mTokens.ButtonLabelSize,
            fontWeight = FontWeight.Normal,
        )
    }
    val contentPadding = PaddingValues(
        horizontal = W10mTokens.ButtonHorizontalPadding,
        vertical = W10mTokens.ButtonVerticalPadding,
    )
    val sizedModifier = modifier
        .defaultMinSize(minHeight = W10mTokens.ButtonMinHeight)
        .heightIn(min = W10mTokens.ButtonMinHeight)

    when (style) {
        W10mButtonStyle.Primary -> {
            val container = when {
                !enabled -> CortanaColors.SurfaceElevated
                pressed -> CortanaColors.AccentMuted
                else -> CortanaColors.Accent
            }
            val content = when {
                !enabled -> CortanaColors.OnBackgroundMuted
                else -> Color.White
            }
            Button(
                onClick = onClick,
                modifier = sizedModifier,
                enabled = enabled,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = container,
                    contentColor = content,
                    disabledContainerColor = CortanaColors.SurfaceElevated,
                    disabledContentColor = CortanaColors.OnBackgroundMuted,
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 0.dp,
                    pressedElevation = 0.dp,
                    disabledElevation = 0.dp,
                ),
                contentPadding = contentPadding,
                interactionSource = interaction,
                content = { label() },
            )
        }
        W10mButtonStyle.Secondary -> {
            val borderColor = when {
                !enabled -> CortanaColors.Divider
                pressed -> CortanaColors.Accent
                else -> CortanaColors.OnBackgroundSecondary
            }
            val content = when {
                !enabled -> CortanaColors.OnBackgroundMuted
                pressed -> CortanaColors.Accent
                else -> CortanaColors.OnBackground
            }
            OutlinedButton(
                onClick = onClick,
                modifier = sizedModifier,
                enabled = enabled,
                shape = shape,
                border = BorderStroke(W10mTokens.ButtonBorderWidth, borderColor),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (pressed && enabled) {
                        CortanaColors.Accent.copy(alpha = 0.12f)
                    } else {
                        Color.Transparent
                    },
                    contentColor = content,
                    disabledContentColor = CortanaColors.OnBackgroundMuted,
                ),
                contentPadding = contentPadding,
                interactionSource = interaction,
                content = { label() },
            )
        }
        W10mButtonStyle.Subtle -> {
            TextButton(
                onClick = onClick,
                modifier = sizedModifier,
                enabled = enabled,
                shape = RoundedCornerShape(W10mTokens.CornerRadius),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (pressed) CortanaColors.AccentGlow else CortanaColors.Accent,
                    disabledContentColor = CortanaColors.OnBackgroundMuted,
                ),
                contentPadding = contentPadding,
                interactionSource = interaction,
                content = { label() },
            )
        }
    }
}
