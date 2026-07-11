package com.tetraploid.joyforold.ui.cortana

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetraploid.joyforold.agent.ConversationCard
import com.tetraploid.joyforold.agent.ConversationCardKind
import com.tetraploid.joyforold.ui.theme.CortanaColors

@Composable
fun ConversationCardList(
    cards: List<ConversationCard>,
    isListening: Boolean,
    speechText: String,
    onBinaryConfirm: () -> Unit,
    onBinaryCancel: () -> Unit,
    onDismissConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cards.forEach { card ->
            ConversationCardItem(
                card = card,
                isListening = isListening,
                speechText = speechText,
                onBinaryConfirm = onBinaryConfirm,
                onBinaryCancel = onBinaryCancel,
                onDismissConfirm = onDismissConfirm,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun OverlayInteractionCard(
    card: ConversationCard,
    isListening: Boolean,
    speechText: String,
    onBinaryConfirm: () -> Unit,
    onBinaryCancel: () -> Unit,
    onDismissConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ConversationCardItem(
        card = card,
        isListening = isListening,
        speechText = speechText,
        onBinaryConfirm = onBinaryConfirm,
        onBinaryCancel = onBinaryCancel,
        onDismissConfirm = onDismissConfirm,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun ConversationCardItem(
    card: ConversationCard,
    isListening: Boolean,
    speechText: String,
    onBinaryConfirm: () -> Unit,
    onBinaryCancel: () -> Unit,
    onDismissConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedDetails by rememberSaveable(card.id) { mutableStateOf(false) }

    val background = when (card.kind) {
        ConversationCardKind.Confirm -> CortanaColors.SurfaceElevated
        ConversationCardKind.Info -> CortanaColors.Surface
        ConversationCardKind.Plan -> CortanaColors.Surface
        else -> CortanaColors.Surface
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = card.title,
            color = kindTitleColor(card.kind),
            fontSize = 12.sp,
        )
        if (card.body.isNotBlank()) {
            Text(
                text = card.body,
                color = CortanaColors.OnBackground,
                fontSize = 15.sp,
            )
        }
        card.bullets.forEach { line ->
            Text(
                text = line,
                color = CortanaColors.OnBackgroundSecondary,
                fontSize = 14.sp,
            )
        }

        if (card.kind == ConversationCardKind.Plan && card.detailBullets.isNotEmpty()) {
            ExpandDetailToggle(
                expanded = expandedDetails,
                onClick = { expandedDetails = !expandedDetails },
            )
            if (expandedDetails) {
                Column(
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    card.detailBullets.forEach { line ->
                        Text(
                            text = line,
                            color = CortanaColors.OnBackgroundMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        if (card.kind == ConversationCardKind.Confirm && !card.showBinaryActions) {
            Text(
                text = if (isListening) "正在听您说话，说完停顿即可继续" else "请直接说话或输入回复",
                color = CortanaColors.OnBackgroundMuted,
                fontSize = 13.sp,
            )
            if (speechText.isNotBlank()) {
                Text(
                    text = "识别：$speechText",
                    color = CortanaColors.AccentMuted,
                    fontSize = 13.sp,
                )
            }
            TextButton(onClick = onDismissConfirm) {
                Text("取消", color = CortanaColors.OnBackgroundSecondary)
            }
        }
        if (card.showBinaryActions) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onBinaryConfirm, modifier = Modifier.weight(1f)) {
                    Text("确认 / 发送", color = CortanaColors.AccentMuted)
                }
                OutlinedButton(onClick = onBinaryCancel, modifier = Modifier.weight(1f)) {
                    Text("取消", color = CortanaColors.Error)
                }
            }
            TextButton(onClick = onDismissConfirm) {
                Text("取消本次确认", color = CortanaColors.OnBackgroundSecondary)
            }
        }
    }
}

private fun kindTitleColor(kind: ConversationCardKind) = when (kind) {
    ConversationCardKind.User -> CortanaColors.AccentMuted
    ConversationCardKind.Assistant -> CortanaColors.AccentMuted
    ConversationCardKind.Plan -> CortanaColors.OnBackgroundMuted
    ConversationCardKind.Progress -> CortanaColors.Accent
    ConversationCardKind.Info -> CortanaColors.AccentMuted
    ConversationCardKind.Confirm -> CortanaColors.Accent
}
