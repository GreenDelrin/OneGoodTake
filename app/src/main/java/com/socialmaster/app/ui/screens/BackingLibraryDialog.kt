package com.socialmaster.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.socialmaster.app.audio.BackingLibraryEntry
import com.socialmaster.app.ui.components.AppButton
import com.socialmaster.app.ui.components.AppIconButton
import com.socialmaster.app.ui.components.SectionLabel

@Composable
fun BackingLibraryDialog(
    entries: List<BackingLibraryEntry>,
    onAddFromPhone: () -> Unit,
    onSelect: (BackingLibraryEntry) -> Unit,
    onFavoriteChanged: (BackingLibraryEntry, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val favorites = entries.filter { it.favorite }
    val recent = entries.filterNot { it.favorite }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backing tracks", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                AppButton("Choose audio from phone", onAddFromPhone, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                SectionLabel("FAVORITES")
                if (favorites.isEmpty()) {
                    Text(
                        "Star a recent track to keep it here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                favorites.forEach { entry -> LibraryRow(entry, onSelect, onFavoriteChanged) }
                Spacer(Modifier.height(14.dp))
                SectionLabel("RECENT")
                if (recent.isEmpty()) {
                    Text(
                        "Tracks you choose from your phone will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                recent.forEach { entry -> LibraryRow(entry, onSelect, onFavoriteChanged) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun LibraryRow(
    entry: BackingLibraryEntry,
    onSelect: (BackingLibraryEntry) -> Unit,
    onFavoriteChanged: (BackingLibraryEntry, Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { onSelect(entry) }, modifier = Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AppIconButton(
            if (entry.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            if (entry.favorite) "Remove from favorites" else "Add to favorites",
            { onFavoriteChanged(entry, !entry.favorite) },
            tint = if (entry.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
