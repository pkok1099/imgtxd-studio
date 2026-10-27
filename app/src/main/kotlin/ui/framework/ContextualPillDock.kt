package com.onlasdan.studio.ui.framework

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

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

    val displayItems = items.take(5)
    val density = LocalDensity.current
    var itemOffsets by remember { mutableStateOf(mapOf<Int, Float>()) }
    var itemWidths by remember { mutableStateOf(mapOf<Int, Float>()) }

    val selectedIndex = displayItems.indexOfFirst { it.isSelected }

    val targetIndicatorOffset = if (selectedIndex >= 0) itemOffsets[selectedIndex] ?: 0f else 0f
    val targetIndicatorWidth = if (selectedIndex >= 0) itemWidths[selectedIndex] ?: 0f else 0f

    val animatedIndicatorOffset by animateFloatAsState(
        targetValue = targetIndicatorOffset,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "dockIndicatorOffset"
    )

    val animatedIndicatorWidth by animateFloatAsState(
        targetValue = targetIndicatorWidth,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "dockIndicatorWidth"
    )

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
            Box(
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .wrapContentWidth()
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                // Sliding indicator pill behind selected item
                if (selectedIndex >= 0 && animatedIndicatorWidth > 0f) {
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(x = animatedIndicatorOffset.roundToInt(), y = 0) }
                            .width(with(density) { animatedIndicatorWidth.toDp() })
                            .height(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                    )
                }

                Row(
                    modifier = Modifier.wrapContentWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    displayItems.forEachIndexed { index, item ->
                        DockItemButton(
                            item = item,
                            hasSlidingIndicator = (selectedIndex >= 0),
                            modifier = Modifier.onGloballyPositioned { coords ->
                                val bounds = coords.boundsInParent()
                                if (itemOffsets[index] != bounds.left || itemWidths[index] != bounds.width) {
                                    itemOffsets = itemOffsets + (index to bounds.left)
                                    itemWidths = itemWidths + (index to bounds.width)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DockItemButton(
    item: ContextualDockItem,
    hasSlidingIndicator: Boolean = false,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    val containerColor by animateColorAsState(
        targetValue = if (item.isSelected && !hasSlidingIndicator) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
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
        modifier = modifier
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
