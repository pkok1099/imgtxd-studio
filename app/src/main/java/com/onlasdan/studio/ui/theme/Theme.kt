package com.onlasdan.studio.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

val NoirBlushShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * ImgToolTheme - Enforces the "Noir & Blush" dark-only aesthetic.
 * Dynamic color and light mode are strictly disabled.
 */
@Composable
fun ImgToolTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NoirBlushColorScheme,
        typography = NoirBlushTypography,
        shapes = NoirBlushShapes,
        content = content
    )
}
