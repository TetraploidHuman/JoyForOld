package com.tetraploid.joyforold.overlay

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tetraploid.joyforold.agent.AgentRuntime
import com.tetraploid.joyforold.ui.VoiceConfirmBanner

@Composable
fun VoiceConfirmOverlayContent(onDismiss: () -> Unit) {
    val uiState by AgentRuntime.state.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.waitingForUserConfirm, uiState.confirmPrompt) {
        if (!uiState.waitingForUserConfirm || uiState.confirmPrompt.isNullOrBlank()) {
            onDismiss()
        }
    }

    val prompt = uiState.confirmPrompt ?: return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF5FFFFFF)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "需要您确认",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            VoiceConfirmBanner(
                prompt = prompt,
                isListening = uiState.isListening,
                speechText = uiState.speechText,
                onCancel = { AgentRuntime.clearPendingConfirmUI() },
            )
        }
    }
}
