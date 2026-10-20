package com.onlasdan.studio.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

val NoirBlushShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp)
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
