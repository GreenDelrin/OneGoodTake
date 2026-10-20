package com.socialmaster.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.socialmaster.app.audio.Project
import com.socialmaster.app.ui.VideoPreview

@Composable
fun MasteringScreen(
    project: Project,
    videoFile: java.io.File?,
    previewAudioFile: java.io.File?,
    measuredLufs: Float,
    onMeasure: () -> Unit,
    onAutoMaster: () -> Unit,
    onMasterSettingsChanged: () -> Unit,
    onExport: () -> Unit,
    isMastering: Boolean,
    canExport: Boolean,
    status: String,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Master", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text("Set delivery loudness, then export the video", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        VideoPreview(videoFile, previewAudioFile, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("INTEGRATED LOUDNESS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${"%.1f".format(measuredLufs)} LUFS", style = MaterialTheme.typography.headlineSmall)
            }
            Text("TARGET  ${project.master.targetLufs.toInt()} LUFS", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(8.dp))
        MasterSlider("MAKEUP GAIN", project.master.makeupGainDb, -12f..12f) { project.master.makeupGainDb = it; onMasterSettingsChanged() }
        MasterSlider("LIMITER CEILING", project.master.limiterThresholdDb, -6f..0f) { project.master.limiterThresholdDb = it; onMasterSettingsChanged() }
        MasterSlider("SYNC OFFSET", project.syncOffsetMs.toFloat(), -200f..200f) { project.syncOffsetMs = it.toInt(); onMasterSettingsChanged() }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.weight(0.8f)) { Text("← Mix") }
            Button(onClick = onMeasure, enabled = previewAudioFile != null && !isMastering, modifier = Modifier.weight(1f)) { Text("Measure") }
            Button(onClick = onAutoMaster, enabled = previewAudioFile != null && !isMastering, modifier = Modifier.weight(1.3f)) { Text(if (isMastering) "Working…" else "Auto-master") }
        }
        Spacer(Modifier.height(6.dp))
        Button(onClick = onExport, enabled = canExport && !isMastering, modifier = Modifier.fillMaxWidth()) { Text("Export video  ↗") }
    }
}

@Composable
private fun MasterSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    var sliderValue by remember(label, value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(128.dp))
        Slider(sliderValue, { sliderValue = it; onChange(it) }, Modifier.weight(1f), valueRange = range)
        Text(if (label == "SYNC OFFSET") "${sliderValue.toInt()} ms" else "${"%.1f".format(sliderValue)} dB", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(58.dp))
    }
}
