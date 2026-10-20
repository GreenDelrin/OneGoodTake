package com.socialmaster.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.socialmaster.app.audio.Track
import com.socialmaster.app.ui.VideoPreview

@Composable
fun MixingScreen(
    tracks: List<Track>,
    videoFile: java.io.File?,
    mixedFile: java.io.File?,
    isMixing: Boolean,
    status: String,
    onTrackChange: (Track) -> Unit,
    onApplyMix: () -> Unit,
    onGoMaster: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Mix", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text("Balance the backing and your take", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        VideoPreview(videoFile, mixedFile, Modifier.fillMaxWidth(), onBuildAudio = onApplyMix)
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(tracks, key = { it.id }) { track ->
                TrackStrip(track, onTrackChange)
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
            if (tracks.isEmpty()) item {
                Text("Finish a take to see its tracks here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onApplyMix, enabled = tracks.isNotEmpty() && !isMixing, modifier = Modifier.weight(1f)) {
                Text(if (isMixing) "Mixing…" else if (mixedFile == null) "Apply mix" else "Update mix")
            }
            Button(onClick = onGoMaster, enabled = mixedFile != null && !isMixing, modifier = Modifier.weight(1f)) { Text("Master →") }
        }
    }
}

@Composable
private fun TrackStrip(track: Track, onTrackChange: (Track) -> Unit) {
    var vol by remember(track.id) { mutableFloatStateOf(track.volumeDb) }
    var low by remember(track.id) { mutableFloatStateOf(track.eqLowDb) }
    var mid by remember(track.id) { mutableFloatStateOf(track.eqMidDb) }
    var high by remember(track.id) { mutableFloatStateOf(track.eqHighDb) }
    var reverb by remember(track.id) { mutableFloatStateOf(track.reverbSend) }
    var compressor by remember(track.id) { mutableFloatStateOf(track.compressorAmount) }
    fun updateTrack(updated: Track) = onTrackChange(updated.copy(
        volumeDb = vol, eqLowDb = low, eqMidDb = mid, eqHighDb = high,
        reverbSend = reverb, compressorAmount = compressor
    ))
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.name, style = MaterialTheme.typography.titleSmall)
                Text(if (track.id == "backing") "Playback track" else "Recorded input", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = {
                updateTrack(track.copy(muted = !track.muted))
            }) { Text(if (track.muted) "MUTED" else "MUTE") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LEVEL", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp))
            Slider(vol, { vol = it; updateTrack(track.copy(volumeDb = it)) }, Modifier.weight(1f), valueRange = -36f..6f)
            Text("${vol.toInt()} dB", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            EffectSlider("LOW", low, { low = it; updateTrack(track.copy(eqLowDb = it)) })
            EffectSlider("MID", mid, { mid = it; updateTrack(track.copy(eqMidDb = it)) })
            EffectSlider("HIGH", high, { high = it; updateTrack(track.copy(eqHighDb = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("COMP", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(55.dp))
            Slider(compressor, { compressor = it; updateTrack(track.copy(compressorAmount = it)) }, Modifier.weight(1f), valueRange = 0f..1f)
            Text("${(compressor * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(42.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("REVERB", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(55.dp))
            Slider(reverb, { reverb = it; updateTrack(track.copy(reverbSend = it)) }, Modifier.weight(1f), valueRange = 0f..1f)
        }
    }
}

@Composable
private fun RowScope.EffectSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column(Modifier.weight(1f).padding(horizontal = 2.dp)) {
        Text("$label ${value.toInt()}dB", style = MaterialTheme.typography.labelSmall)
        Slider(value, onValueChange, valueRange = -12f..12f)
    }
}
