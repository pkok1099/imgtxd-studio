package com.onlasdan.studio.ui.framework

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class ContextualDockItem(
    val id: String,
    val icon: ImageVector,
    val contentDescription: String,
    val isSelected: Boolean = false,
    val isEnabled: Boolean = true,
    val showDotBadge: Boolean = false,
    val onClick: () -> Unit
)

@Composable
fun ContextualPillDock(
    items: List<ContextualDockItem>,
    modifier: Modifier = Modifier
) {
    val isKeyboardOpen = WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 50.dp
    if (isKeyboardOpen || items.isEmpty()) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            ),
            modifier = Modifier
                .height(64.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = CircleShape,
                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                )
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .wrapContentWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.take(5).forEach { item ->
                    DockItemButton(item = item)
                }
            }
        }
    }
}

@Composable
private fun DockItemButton(item: ContextualDockItem) {
    val interactionSource = remember { MutableInteractionSource() }

    val containerColor by animateColorAsState(
        targetValue = if (item.isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "dockContainerColor"
    )

    val iconColor by animateColorAsState(
        targetValue = when {
            !item.isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            item.isSelected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "dockIconColor"
    )

    val itemSize by animateDpAsState(
        targetValue = if (item.isSelected) 48.dp else 44.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "dockItemSize"
    )

    Box(
        modifier = Modifier
            .size(itemSize)
            .clip(CircleShape)
            .background(containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, radius = 24.dp),
                enabled = item.isEnabled,
                onClick = item.onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.contentDescription,
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )

        // Titik pink kecil sebagai penanda (berkas aktif / ada perubahan belum tersimpan)
        if (item.showDotBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(7.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}
