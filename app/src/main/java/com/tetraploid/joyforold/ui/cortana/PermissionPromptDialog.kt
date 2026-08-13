package com.tetraploid.joyforold.ui.cortana

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.tetraploid.joyforold.ui.theme.CortanaColors
import com.tetraploid.joyforold.ui.theme.JoyTextSizes

enum class PermissionDialogKind {
    RecordAudio,
    ReadContacts,
    Accessibility,
}

data class PermissionDialogRequest(
    val kind: PermissionDialogKind,
    val title: String,
    val message: String,
)

@Composable
fun PermissionPromptDialog(
    request: PermissionDialogRequest?,
    onConfirm: (PermissionDialogKind) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = request ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = current.title,
                color = CortanaColors.OnBackground,
                fontSize = JoyTextSizes.TitleCompact,
                lineHeight = JoyTextSizes.TitleLineHeight,
            )
        },
        text = {
            Text(
                text = current.message,
                color = CortanaColors.OnBackgroundSecondary,
                fontSize = JoyTextSizes.Body,
                lineHeight = JoyTextSizes.BodyLineHeight,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(current.kind) }) {
                Text(
                    text = when (current.kind) {
                        PermissionDialogKind.Accessibility -> "去打开"
                        else -> "允许"
                    },
                    color = CortanaColors.Accent,
                    fontSize = JoyTextSizes.Label,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "暂不",
                    color = CortanaColors.OnBackgroundMuted,
                    fontSize = JoyTextSizes.Label,
                )
            }
        },
        containerColor = CortanaColors.SurfaceElevated,
        tonalElevation = 0.dp,
    )
}
