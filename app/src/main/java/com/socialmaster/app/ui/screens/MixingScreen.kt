package com.socialmaster.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.socialmaster.app.audio.Track
import com.socialmaster.app.ui.VideoPreview
import com.socialmaster.app.ui.VideoPreviewController
import com.socialmaster.app.ui.Waveform
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixingScreen(
    tracks: List<Track>,
    trackWaveforms: Map<String, List<Float>>,
    videoFile: File?,
    videoController: VideoPreviewController,
    isExporting: Boolean,
    canExport: Boolean,
    targetLufs: Float,
    isPreviewPlaying: Boolean,
    isPreviewPreparing: Boolean,
    previewPositionMs: Long,
    previewDurationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    status: String,
    onTrimChanged: (Long, Long) -> Unit,
    onTargetLufsChanged: (Float) -> Unit,
    onTrackChange: (Track) -> Unit,
    onTogglePreview: () -> Unit,
    onExport: () -> Unit
) {
    var editingTrackId by remember { mutableStateOf<String?>(null) }
    var targetMenuOpen by remember { mutableStateOf(false) }
    val editingTrack = tracks.firstOrNull { it.id == editingTrackId }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        VideoPreview(
            videoFile = videoFile,
            audioFile = null,
            modifier = Modifier.fillMaxWidth(),
            controller = videoController,
            showPlaybackControls = false,
            trimStartMs = trimStartMs,
            trimEndMs = trimEndMs,
            onTrimChanged = onTrimChanged
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("PREVIEW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Text("${time(previewPositionMs)} / ${time(previewDurationMs)} · video + live audio", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = onTogglePreview, enabled = tracks.isNotEmpty() && !isExporting) {
                Text(if (isPreviewPreparing) "Loading…" else if (isPreviewPlaying) "Stop" else "Play preview")
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(tracks, key = { it.id }) { track ->
                TrackLane(
                    track = track,
                    peaks = trackWaveforms[track.id].orEmpty(),
                    progress = if (previewDurationMs > 0L) (previewPositionMs.toFloat() / previewDurationMs).coerceIn(0f, 1f) else 0f,
                    enabled = !isExporting,
                    onTrackChange = onTrackChange,
                    onEditEffects = { editingTrackId = track.id }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
            if (tracks.isEmpty()) item {
                Text("Finish a take to see your audio tracks here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { targetMenuOpen = true }, enabled = !isExporting) {
                    Text("${targetLufs.toInt()} LUFS  ▾")
                }
                DropdownMenu(expanded = targetMenuOpen, onDismissRequest = { targetMenuOpen = false }) {
                    listOf(-16f, -14f, -12f, -10f).forEach { target ->
                        DropdownMenuItem(
                            text = { Text("${target.toInt()} LUFS") },
                            onClick = { targetMenuOpen = false; onTargetLufsChanged(target) }
                        )
                    }
                }
            }
            Button(onClick = onExport, enabled = canExport && !isExporting, modifier = Modifier.weight(1f)) {
                Text(if (isExporting) "Exporting…" else "Export")
            }
        }
    }

    if (editingTrack != null) {
        ModalBottomSheet(onDismissRequest = { editingTrackId = null }) {
            TrackEffectsSheet(editingTrack, onTrackChange, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun TrackLane(
    track: Track,
    peaks: List<Float>,
    progress: Float,
    enabled: Boolean,
    onTrackChange: (Track) -> Unit,
    onEditEffects: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (track.id == "backing") "BACKING" else "TAKE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onTrackChange(track.copy(muted = !track.muted)) }, enabled = enabled) {
                Text(if (track.muted) "MUTED" else "MUTE")
            }
            TextButton(onClick = onEditEffects, enabled = enabled) { Text("FX") }
        }
        if (peaks.isNotEmpty()) {
            Waveform(peaks, if (track.id == "backing") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary, Modifier.fillMaxWidth().height(44.dp), progress)
        } else {
            Box(Modifier.fillMaxWidth().height(44.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.CenterStart) {
                Text("Waveform unavailable", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TrackEffectsSheet(track: Track, onTrackChange: (Track) -> Unit, modifier: Modifier = Modifier) {
    var volume by remember(track.id) { mutableFloatStateOf(track.volumeDb) }
    var pan by remember(track.id) { mutableFloatStateOf(track.pan) }
    var low by remember(track.id) { mutableFloatStateOf(track.eqLowDb) }
    var mid by remember(track.id) { mutableFloatStateOf(track.eqMidDb) }
    var high by remember(track.id) { mutableFloatStateOf(track.eqHighDb) }
    var reverb by remember(track.id) { mutableFloatStateOf(track.reverbSend) }
    var compressor by remember(track.id) { mutableFloatStateOf(track.compressorAmount) }
    fun update() = onTrackChange(track.copy(
        volumeDb = volume, pan = pan, eqLowDb = low, eqMidDb = mid,
        eqHighDb = high, reverbSend = reverb, compressorAmount = compressor
    ))

    Column(modifier.padding(bottom = 24.dp)) {
        Text(track.name, style = MaterialTheme.typography.titleLarge)
        Text("Changes are heard in Live Audio.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SliderRow("LEVEL", "${volume.toInt()} dB", volume, -36f..6f, { volume = it; update() })
        SliderRow("PAN", if (pan < -0.05f) "L" else if (pan > 0.05f) "R" else "C", pan, -1f..1f, { pan = it; update() })
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            EffectSlider("LOW", low, { low = it; update() }, Modifier.weight(1f))
            EffectSlider("MID", mid, { mid = it; update() }, Modifier.weight(1f))
            EffectSlider("HIGH", high, { high = it; update() }, Modifier.weight(1f))
        }
        SliderRow("COMP", "${(compressor * 100).toInt()}%", compressor, 0f..1f, { compressor = it; update() })
        SliderRow("REVERB", "${(reverb * 100).toInt()}%", reverb, 0f..1f, { reverb = it; update() })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Solo this track", modifier = Modifier.weight(1f))
            Switch(checked = track.solo, onCheckedChange = { onTrackChange(track.copy(solo = it)) })
        }
    }
}

@Composable
private fun SliderRow(label: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(62.dp))
        Slider(value, onChange, Modifier.weight(1f), valueRange = range)
        Text(valueLabel, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(42.dp), maxLines = 1)
    }
}

@Composable
private fun EffectSlider(label: String, value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 2.dp)) {
        Text("$label ${value.toInt()}dB", style = MaterialTheme.typography.labelSmall, maxLines = 1)
        Slider(value, onValueChange, valueRange = -12f..12f)
    }
}

private fun time(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1_000 % 60)