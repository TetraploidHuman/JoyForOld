package com.tetraploid.joyforold.ui.cortana

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetraploid.joyforold.ui.theme.CortanaColors

@Composable
fun ExpandDetailToggle(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    collapsedLabel: String = "查看详细步骤",
    expandedLabel: String = "收起详细步骤",
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = if (expanded) expandedLabel else collapsedLabel,
            color = CortanaColors.AccentMuted,
            fontSize = 13.sp,
        )
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) expandedLabel else collapsedLabel,
            tint = CortanaColors.AccentMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
fun ExpandChevronIcon(
    expanded: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
        contentDescription = contentDescription,
        tint = CortanaColors.OnBackgroundMuted,
        modifier = modifier.size(20.dp),
    )
}
