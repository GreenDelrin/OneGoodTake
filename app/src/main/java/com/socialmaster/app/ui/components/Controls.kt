package com.socialmaster.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.socialmaster.app.ui.theme.AppButtonShape

/**
 * One icon style for the whole app: a filled glyph in a rounded square chip.
 * The chip is the same shape everywhere so the camera, play, rewind and add buttons
 * read as one family.
 */
@Composable
fun AppIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onVideoSurface: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    val chip = if (onVideoSurface) Color.Black.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceVariant
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.padding(2.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = tint,
            disabledContentColor = tint.copy(alpha = 0.35f)
        )
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (enabled) chip else chip.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(21.dp))
        }
    }
}

/** Filled primary button with the app shape. */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = AppButtonShape,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor)
    ) { Text(text) }
}

/** Quiet label used for the section headings. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.secondary,
        modifier = modifier
    )
}
