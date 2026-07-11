package com.tetraploid.joyforold.ui.cortana

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.tetraploid.joyforold.ui.theme.CortanaColors

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
        title = { Text(text = current.title, color = CortanaColors.OnBackground) },
        text = { Text(text = current.message, color = CortanaColors.OnBackgroundSecondary) },
        confirmButton = {
            TextButton(onClick = { onConfirm(current.kind) }) {
                Text(
                    text = when (current.kind) {
                        PermissionDialogKind.Accessibility -> "去设置"
                        else -> "授权"
                    },
                    color = CortanaColors.Accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = CortanaColors.OnBackgroundMuted)
            }
        },
        containerColor = CortanaColors.SurfaceElevated,
    )
}
