package com.socialmaster.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.socialmaster.app.audio.BackingLibraryEntry

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
        title = { Text("Backing tracks") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Button(onClick = onAddFromPhone, modifier = Modifier.fillMaxWidth()) { Text("Choose audio from phone") }
                Spacer(Modifier.height(14.dp))
                Text("FAVORITES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                if (favorites.isEmpty()) Text("Star a recent track to keep it here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                favorites.forEach { entry -> LibraryRow(entry, onSelect, onFavoriteChanged) }
                Spacer(Modifier.height(12.dp))
                Text("RECENT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                if (recent.isEmpty()) Text("Tracks you choose from your phone will appear here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
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
        IconButton(
            onClick = { onFavoriteChanged(entry, !entry.favorite) },
            modifier = Modifier.semantics { contentDescription = if (entry.favorite) "Remove from favorites" else "Add to favorites" }
        ) {
            Text(if (entry.favorite) "★" else "☆", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
        }
    }
}
