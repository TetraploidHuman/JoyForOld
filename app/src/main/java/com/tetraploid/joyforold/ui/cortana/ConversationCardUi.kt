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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tetraploid.joyforold.agent.ConversationCard
import com.tetraploid.joyforold.agent.ConversationCardKind
import com.tetraploid.joyforold.ui.theme.CortanaColorPalette
import com.tetraploid.joyforold.ui.theme.CortanaColors
import com.tetraploid.joyforold.ui.theme.JoyTextSizes
import com.tetraploid.joyforold.ui.theme.LocalCortanaColors

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
    cardSpacing: Dp = 10.dp,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(cardSpacing),
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
    val palette = LocalCortanaColors.current

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
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = card.title,
            color = kindTitleColor(card.kind, palette),
            fontSize = JoyTextSizes.Caption,
            lineHeight = JoyTextSizes.CaptionLineHeight,
        )
        if (card.body.isNotBlank()) {
            Text(
                text = card.body,
                color = CortanaColors.OnBackground,
                fontSize = JoyTextSizes.Body,
                lineHeight = JoyTextSizes.BodyLineHeight,
            )
        }

        if (card.kind == ConversationCardKind.Disambiguation) {
            card.bullets.forEachIndexed { index, label ->
                val intentId = card.optionIds.getOrNull(index).orEmpty()
                OutlinedButton(
                    onClick = { if (intentId.isNotBlank()) onDisambiguationSelect(intentId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        label,
                        color = CortanaColors.AccentMuted,
                        fontSize = JoyTextSizes.Label,
                    )
                }
            }
            Text(
                text = if (isListening) {
                    "我在听。您可以说「第一个」，或直接说名字。"
                } else {
                    "请说话，或点上面的一项。"
                },
                color = CortanaColors.OnBackgroundMuted,
                fontSize = JoyTextSizes.Caption,
                lineHeight = JoyTextSizes.CaptionLineHeight,
            )
            if (speechText.isNotBlank()) {
                Text(
                    text = "听到了：$speechText",
                    color = CortanaColors.AccentMuted,
                    fontSize = JoyTextSizes.Caption,
                    lineHeight = JoyTextSizes.CaptionLineHeight,
                )
            }
        } else {
            card.bullets.forEach { line ->
                Text(
                    text = line,
                    color = CortanaColors.OnBackgroundSecondary,
                    fontSize = JoyTextSizes.BodySecondary,
                    lineHeight = JoyTextSizes.BodyLineHeight,
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
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    card.detailBullets.forEach { line ->
                        Text(
                            text = line,
                            color = CortanaColors.OnBackgroundMuted,
                            fontSize = JoyTextSizes.Hint,
                            lineHeight = JoyTextSizes.CaptionLineHeight,
                        )
                    }
                }
            }
        }

        if (card.kind == ConversationCardKind.Confirm && !card.showBinaryActions) {
            Text(
                text = if (isListening) {
                    "我在听。说完后稍停一下，就会继续。"
                } else {
                    "请说话回复，或直接输入文字。"
                },
                color = CortanaColors.OnBackgroundMuted,
                fontSize = JoyTextSizes.Caption,
                lineHeight = JoyTextSizes.CaptionLineHeight,
            )
            if (speechText.isNotBlank()) {
                Text(
                    text = "听到了：$speechText",
                    color = CortanaColors.AccentMuted,
                    fontSize = JoyTextSizes.Caption,
                    lineHeight = JoyTextSizes.CaptionLineHeight,
                )
            }
            TextButton(onClick = onDismissConfirm) {
                Text("取消", color = CortanaColors.OnBackgroundSecondary, fontSize = JoyTextSizes.Label)
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
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = if (card.kind == ConversationCardKind.Undo) onUndo else onBinaryConfirm,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(confirmLabel, color = CortanaColors.AccentMuted, fontSize = JoyTextSizes.Label)
                }
                OutlinedButton(
                    onClick = if (card.kind == ConversationCardKind.Undo) onDismissUndo else onBinaryCancel,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(cancelLabel, color = CortanaColors.Error, fontSize = JoyTextSizes.Label)
                }
            }
            if (card.kind != ConversationCardKind.Undo) {
                TextButton(onClick = onDismissConfirm) {
                    Text(
                        "先不确认了",
                        color = CortanaColors.OnBackgroundSecondary,
                        fontSize = JoyTextSizes.Caption,
                    )
                }
            }
        }
    }
}

private fun kindTitleColor(kind: ConversationCardKind, colors: CortanaColorPalette) = when (kind) {
    ConversationCardKind.User -> colors.accentMuted
    ConversationCardKind.Assistant -> colors.accentMuted
    ConversationCardKind.Plan -> colors.onBackgroundMuted
    ConversationCardKind.Progress -> colors.accent
    ConversationCardKind.Info -> colors.accentMuted
    ConversationCardKind.Confirm -> colors.accent
    ConversationCardKind.Disambiguation -> colors.accent
    ConversationCardKind.Preview -> colors.accent
    ConversationCardKind.Undo -> colors.onBackgroundMuted
}
