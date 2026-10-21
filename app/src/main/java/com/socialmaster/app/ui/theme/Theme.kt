package com.socialmaster.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Black and mint. Dark is the default: the app lives next to a camera, and the record
 * screen is mostly a video surface.
 *
 * Mint is the single accent. The secondary tone is a deeper teal from the same family so
 * the two waveforms (backing and take) stay tellable apart without adding a second hue.
 */
private val Mint = Color(0xFF5FE3B0)
private val MintInk = Color(0xFF00281D)
private val Teal = Color(0xFF3FB99A)
private val TealInk = Color(0xFF002019)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = MintInk,
    primaryContainer = Color(0xFF11402F),
    onPrimaryContainer = Color(0xFFB8F5DD),
    secondary = Teal,
    onSecondary = TealInk,
    secondaryContainer = Color(0xFF123A30),
    onSecondaryContainer = Color(0xFFA6E8D3),
    background = Color(0xFF0A0F10),
    onBackground = Color(0xFFE8F0ED),
    surface = Color(0xFF111718),
    onSurface = Color(0xFFE8F0ED),
    surfaceVariant = Color(0xFF1B2426),
    onSurfaceVariant = Color(0xFF93A5A0),
    outline = Color(0xFF2B3835),
    outlineVariant = Color(0xFF1E282A),
    error = Color(0xFFFF7B72),
    onError = Color(0xFF3B0906),
    errorContainer = Color(0xFF5B1A16),
    onErrorContainer = Color(0xFFFFDAD5)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B9C70),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC6F5E3),
    onPrimaryContainer = Color(0xFF00382A),
    secondary = Color(0xFF0A6E5C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFBCE9DC),
    onSecondaryContainer = Color(0xFF002019),
    background = Color(0xFFF2F6F4),
    onBackground = Color(0xFF101615),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101615),
    surfaceVariant = Color(0xFFE4EBE8),
    onSurfaceVariant = Color(0xFF54625E),
    outline = Color(0xFFBAC6C2),
    outlineVariant = Color(0xFFDCE4E1),
    error = Color(0xFFC4322A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

/** Buttons are rounded rectangles. Material defaults are pills, which read as toys. */
val AppButtonShape = RoundedCornerShape(12.dp)
val AppChipShape = RoundedCornerShape(12.dp)
val AppCardShape = RoundedCornerShape(20.dp)

/** The record button is always this red, in both themes: users expect it. */
val RecordRed = Color(0xFFE5484D)

private val AppTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.6.sp)
    )
}

@Composable
fun SocialMasterTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = AppChipShape,
            medium = AppCardShape,
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(32.dp)
        ),
        typography = AppTypography,
        content = content
    )
}
