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
    onDisambiguationSelect: (String) -> Unit = {},
    onUndo: () -> Unit = {},
    onDismissUndo: () -> Unit = {},
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
                onDisambiguationSelect = onDisambiguationSelect,
                onUndo = onUndo,
                onDismissUndo = onDismissUndo,
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
    onDisambiguationSelect: (String) -> Unit = {},
    onUndo: () -> Unit = {},
    onDismissUndo: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    ConversationCardItem(
        card = card,
        isListening = isListening,
        speechText = speechText,
        onBinaryConfirm = onBinaryConfirm,
        onBinaryCancel = onBinaryCancel,
        onDismissConfirm = onDismissConfirm,
        onDisambiguationSelect = onDisambiguationSelect,
        onUndo = onUndo,
        onDismissUndo = onDismissUndo,
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
    onDisambiguationSelect: (String) -> Unit,
    onUndo: () -> Unit,
    onDismissUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedDetails by rememberSaveable(card.id) { mutableStateOf(false) }

    val background = when (card.kind) {
        ConversationCardKind.Confirm -> CortanaColors.SurfaceElevated
        ConversationCardKind.Disambiguation, ConversationCardKind.Preview -> CortanaColors.SurfaceElevated
        ConversationCardKind.Undo -> CortanaColors.Surface
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

        if (card.kind == ConversationCardKind.Disambiguation) {
            card.bullets.forEachIndexed { index, label ->
                val intentId = card.optionIds.getOrNull(index).orEmpty()
                OutlinedButton(
                    onClick = { if (intentId.isNotBlank()) onDisambiguationSelect(intentId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(label, color = CortanaColors.AccentMuted)
                }
            }
        } else {
            card.bullets.forEach { line ->
                Text(
                    text = line,
                    color = CortanaColors.OnBackgroundSecondary,
                    fontSize = 14.sp,
                )
            }
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
            val (confirmLabel, cancelLabel) = when (card.kind) {
                ConversationCardKind.Preview -> "确认执行" to "取消"
                ConversationCardKind.Undo -> "撤销" to "不用了"
                else -> "确认" to "取消"
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = if (card.kind == ConversationCardKind.Undo) onUndo else onBinaryConfirm,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(confirmLabel, color = CortanaColors.AccentMuted)
                }
                OutlinedButton(
                    onClick = if (card.kind == ConversationCardKind.Undo) onDismissUndo else onBinaryCancel,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(cancelLabel, color = CortanaColors.Error)
                }
            }
            if (card.kind != ConversationCardKind.Undo) {
                TextButton(onClick = onDismissConfirm) {
                    Text("取消本次确认", color = CortanaColors.OnBackgroundSecondary)
                }
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
    ConversationCardKind.Disambiguation -> CortanaColors.Accent
    ConversationCardKind.Preview -> CortanaColors.Accent
    ConversationCardKind.Undo -> CortanaColors.OnBackgroundMuted
}
