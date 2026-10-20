package com.socialmaster.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF63E6BE),
    onPrimary = Color(0xFF08221A),
    secondary = Color(0xFFF2B66D),
    background = Color(0xFF101619),
    surface = Color(0xFF192226),
    surfaceVariant = Color(0xFF26343A),
    onSurface = Color(0xFFE7EFED),
    onSurfaceVariant = Color(0xFF9AADA9),
    error = Color(0xFFFF6B6B)
)

@Composable
fun SocialMasterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
