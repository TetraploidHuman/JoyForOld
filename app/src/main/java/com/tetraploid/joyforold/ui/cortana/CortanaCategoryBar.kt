package com.tetraploid.joyforold.ui.cortana

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Window
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.tetraploid.joyforold.ui.theme.CortanaColors
import com.tetraploid.joyforold.ui.theme.JoyTextSizes

enum class CortanaSearchCategory(val label: String, val prefix: String) {
    Apps("应用", "打开应用 "),
    Documents("文档", "搜索文档 "),
    Web("网页", "搜索网页 "),
}

@Composable
fun CortanaCategoryBar(
    selected: CortanaSearchCategory?,
    onCategoryClick: (CortanaSearchCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "快捷搜索",
            color = CortanaColors.OnBackgroundMuted,
            fontSize = JoyTextSizes.Caption,
            lineHeight = JoyTextSizes.CaptionLineHeight,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CortanaSearchCategory.entries.forEach { category ->
                CategoryTile(
                    label = category.label,
                    icon = category.icon(),
                    selected = selected == category,
                    onClick = { onCategoryClick(category) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CategoryTile(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(84.dp)
            .background(
                if (selected) CortanaColors.SurfaceElevated else CortanaColors.Surface,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) CortanaColors.Accent else CortanaColors.OnBackground,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = label,
            color = if (selected) CortanaColors.AccentMuted else CortanaColors.OnBackgroundSecondary,
            fontSize = JoyTextSizes.Caption,
        )
    }
}

private fun CortanaSearchCategory.icon(): ImageVector = when (this) {
    CortanaSearchCategory.Apps -> Icons.Outlined.Window
    CortanaSearchCategory.Documents -> Icons.Outlined.Description
    CortanaSearchCategory.Web -> Icons.Outlined.Language
}

@Composable
fun CortanaExpandHintBar(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(CortanaColors.SurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "还可以帮您做这些",
            color = CortanaColors.OnBackgroundSecondary,
            fontSize = JoyTextSizes.Caption,
            lineHeight = JoyTextSizes.CaptionLineHeight,
        )
        ExpandChevronIcon(
            expanded = expanded,
            contentDescription = if (expanded) "收起提示" else "展开提示",
        )
    }
}

@Composable
fun CortanaBottomDock(
    command: String,
    onCommandChange: (String) -> Unit,
    onMicClick: () -> Unit,
    onCategoryClick: (CortanaSearchCategory) -> Unit,
    selectedCategory: CortanaSearchCategory?,
    isListening: Boolean,
    enabled: Boolean,
    expandedHints: Boolean,
    onExpandHints: () -> Unit,
    extraSuggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
    onSendClick: () -> Unit,
    onCancelClick: () -> Unit,
    canExecute: Boolean,
    isRunning: Boolean = false,
    voiceBusy: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CortanaColors.Background),
    ) {
        if (expandedHints && extraSuggestions.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                extraSuggestions.forEach { suggestion ->
                    Text(
                        text = suggestion,
                        color = CortanaColors.OnBackgroundSecondary,
                        fontSize = JoyTextSizes.BodySecondary,
                        lineHeight = JoyTextSizes.BodyLineHeight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSuggestionClick(suggestion) }
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }

        CortanaExpandHintBar(
            expanded = expandedHints,
            onClick = onExpandHints,
        )

        CortanaCategoryBar(
            selected = selectedCategory,
            onCategoryClick = onCategoryClick,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        CortanaSearchBar(
            value = command,
            onValueChange = onCommandChange,
            onMicClick = onMicClick,
            onSendClick = onSendClick,
            onCancelClick = onCancelClick,
            isListening = isListening,
            isRunning = isRunning,
            voiceBusy = voiceBusy,
            enabled = enabled,
            canExecute = canExecute,
            placeholder = when (selectedCategory) {
                CortanaSearchCategory.Apps -> "搜索应用…"
                CortanaSearchCategory.Documents -> "搜索文档…"
                CortanaSearchCategory.Web -> "搜索网页…"
                null -> "点这里输入，或按麦克风说话"
            },
        )
    }
}
