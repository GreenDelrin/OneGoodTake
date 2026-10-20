package com.socialmaster.app.ui.screens

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.socialmaster.app.camera.RecordingController
import com.socialmaster.app.ui.Waveform
import java.io.File

@Composable
fun RecordingScreen(
    controller: RecordingController,
    isRecording: Boolean,
    isFinalizing: Boolean,
    isStartingTake: Boolean,
    useFrontCamera: Boolean,
    onToggleCamera: () -> Unit,
    onStartRecording: (File) -> Unit,
    onStopRecording: () -> Unit,
    backingName: String?,
    backingPeaks: List<Float>,
    backingProgress: Float,
    backingPositionMs: Long,
    backingDurationMs: Long,
    backingPlaying: Boolean,
    onPreviewBacking: () -> Unit,
    onScrubBacking: (Float) -> Unit,
    onLoadBacking: () -> Unit,
    isLoadingBacking: Boolean,
    backingLoadProgress: Float,
    livePeaks: List<Float>,
    interfaceLevel: Float,
    routeInfo: String,
    status: String,
    outputDir: File,
    onOpenSetup: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    LaunchedEffect(Unit) { controller.bind(lifecycleOwner, previewView) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0x990D1417)).padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("SOCIALMASTER", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(if (isRecording) "RECORDING" else if (isFinalizing) "SAVING TAKE" else "READY TO RECORD", style = MaterialTheme.typography.labelSmall, color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (useFrontCamera) "FRONT" else "REAR", style = MaterialTheme.typography.labelSmall, color = Color.White)
                IconAction("⟳", "Flip camera", onToggleCamera, enabled = !isRecording && !isFinalizing && !isStartingTake)
                IconAction("⚙", "Recording setup", onOpenSetup, enabled = !isRecording && !isFinalizing && !isStartingTake)
            }
            if (isLoadingBacking) {
                Column(Modifier.align(Alignment.Center).fillMaxWidth(0.76f).background(Color(0xE61A2428), MaterialTheme.shapes.large).padding(18.dp)) {
                    Text("Preparing backing track", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    if (backingLoadProgress > 0f) LinearProgressIndicator(progress = { backingLoadProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Replacing the previous track…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("BACKING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.width(68.dp))
                Text(backingName ?: "Choose a backing track", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconAction(if (backingPlaying) "Ⅱ" else "▶", if (backingPlaying) "Pause backing" else "Play backing", onPreviewBacking, enabled = backingPeaks.isNotEmpty() && !isLoadingBacking)
                IconAction("＋", "Load backing track", onLoadBacking, enabled = !isLoadingBacking && !isRecording && !isStartingTake)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("BT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
                if (backingPeaks.isNotEmpty()) {
                    Waveform(backingPeaks, MaterialTheme.colorScheme.primary, Modifier.weight(1f), backingProgress, onScrubBacking)
                } else {
                    Box(Modifier.weight(1f).height(48.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.CenterStart) {
                        Text("Tap + to load WAV / MP3", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TAKE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(68.dp))
                Waveform(livePeaks, MaterialTheme.colorScheme.secondary, Modifier.weight(1f), if (isRecording) 1f else backingProgress)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                Text(routeInfo.ifBlank { "Input not tested" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Meter(interfaceLevel)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${time(backingPositionMs)} / ${time(backingDurationMs)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(60.dp).background(
                        if (isRecording) Color(0xFFB63E45) else Color(0xFFEA5A5E), CircleShape
                    ).clickable(enabled = !isFinalizing && !isStartingTake && !isLoadingBacking) {
                        if (isRecording) onStopRecording()
                        else onStartRecording(File(outputDir, "video_${System.currentTimeMillis()}.mp4"))
                    }, contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(if (isRecording) 20.dp else 24.dp).background(Color.White, if (isRecording) MaterialTheme.shapes.small else CircleShape))
                }
                Text(if (isRecording) "STOP TAKE" else if (isStartingTake) "STARTING…" else if (isFinalizing) "SAVING…" else "RECORD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun IconAction(icon: String, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = description }) {
        Text(icon, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Meter(level: Float) {
    LinearProgressIndicator(progress = { level.coerceIn(0f, 1f) }, modifier = Modifier.width(68.dp).height(5.dp))
}

private fun time(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1_000 % 60)
