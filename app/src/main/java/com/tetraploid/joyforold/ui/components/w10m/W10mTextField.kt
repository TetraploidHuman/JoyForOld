package com.tetraploid.joyforold.ui.components.w10m

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetraploid.joyforold.ui.theme.CortanaColors

/**
 * Windows 10 Mobile TextBox: flat field with accent underline on focus.
 */
@Composable
fun W10mTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val underlineColor by animateColorAsState(
        targetValue = when {
            !enabled -> CortanaColors.Divider
            focused -> CortanaColors.Accent
            else -> CortanaColors.OnBackgroundMuted
        },
        animationSpec = tween(durationMillis = 120),
        label = "w10m_underline",
    )
    val underlineThickness = if (focused && enabled) {
        W10mTokens.TextFieldUnderlineFocused
    } else {
        W10mTokens.TextFieldUnderlineIdle
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                color = if (focused && enabled) CortanaColors.Accent else CortanaColors.OnBackgroundMuted,
                fontSize = W10mTokens.CaptionSize,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.4.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = W10mTokens.TextFieldMinHeight)
                .background(CortanaColors.Surface)
                .padding(
                    horizontal = W10mTokens.TextFieldContentPaddingHorizontal,
                    vertical = W10mTokens.TextFieldContentPaddingVertical,
                ),
            enabled = enabled,
            singleLine = singleLine,
            textStyle = TextStyle(
                color = if (enabled) CortanaColors.OnBackground else CortanaColors.OnBackgroundMuted,
                fontSize = W10mTokens.BodySize,
                fontWeight = FontWeight.Normal,
                lineHeight = 22.sp,
            ),
            cursorBrush = SolidColor(CortanaColors.Accent),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interaction,
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (value.isEmpty() && !placeholder.isNullOrBlank()) {
                        Text(
                            text = placeholder,
                            color = CortanaColors.OnBackgroundMuted,
                            fontSize = W10mTokens.BodySize,
                        )
                    }
                    innerTextField()
                }
            },
        )
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(underlineThickness)
                .background(underlineColor),
        )
    }
}
