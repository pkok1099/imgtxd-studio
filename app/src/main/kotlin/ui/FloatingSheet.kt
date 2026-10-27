package com.onlasdan.studio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * Reusable Floating Bottom Sheet component.
 *
 * Uses ModalBottomSheet with transparent container, zero window insets, and no default drag handle.
 * The content is wrapped inside a floating Surface with all four corners rounded (28dp),
 * an outline border, 12dp horizontal and bottom margins (+ navigationBarsPadding), and imePadding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    stackDepth: Int = 0,
    content: @Composable ColumnScope.() -> Unit
) {
    val scaleFactor = (1f - (stackDepth.coerceAtLeast(0) * 0.045f)).coerceIn(0.85f, 1f)
    val verticalShiftDp = (stackDepth.coerceAtLeast(0) * 8).dp

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f)
    ) {
        val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp + verticalShiftDp)
                .navigationBarsPadding()
                .imePadding()
                .heightIn(max = maxHeight)
                .graphicsLayer {
                    scaleX = scaleFactor
                    scaleY = scaleFactor
                },
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Drag handle pill inside the floating surface
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(38.dp)
                            .height(4.5.dp)
                            .background(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape
                            )
                    )
                }

                content()
            }
        }
    }
}
