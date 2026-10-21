package com.socialmaster.app.ui.screens

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.socialmaster.app.camera.RecordingController
import com.socialmaster.app.ui.Waveform
import com.socialmaster.app.ui.components.AppButton
import com.socialmaster.app.ui.components.AppIconButton
import com.socialmaster.app.ui.theme.AppButtonShape
import com.socialmaster.app.ui.theme.AppCardShape
import com.socialmaster.app.ui.theme.RecordRed
import java.io.File

@Composable
fun RecordingScreen(
    controller: RecordingController,
    isRecording: Boolean,
    isFinalizing: Boolean,
    isStartingTake: Boolean,
    cameraLabel: String,
    onCycleCamera: () -> Unit,
    onStartRecording: (File) -> Unit,
    onStopRecording: () -> Unit,
    backingName: String?,
    backingPeaks: List<Float>,
    backingProgress: Float,
    backingPositionMs: Long,
    backingDurationMs: Long,
    backingPlaying: Boolean,
    onPreviewBacking: () -> Unit,
    onRewindBacking: () -> Unit,
    onScrubBacking: (Float) -> Unit,
    onLoadBacking: () -> Unit,
    isLoadingBacking: Boolean,
    backingLoadProgress: Float,
    livePeaks: List<Float>,
    interfaceLevel: Float,
    outputRoute: String,
    status: String,
    outputDir: File,
    onOpenSetup: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val orientation = LocalConfiguration.current.orientation
    // The camera records 16:9 in sensor space, so the upright frame is 9:16 in portrait.
    val frameAspect = if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 16f / 9f else 9f / 16f
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // Letterbox instead of cropping so the preview shows the frame that will be recorded.
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    LaunchedEffect(Unit) { controller.bind(lifecycleOwner, previewView) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            if (isRecording) {
                SocialSafeAreaGuide(
                    Modifier.fillMaxSize(),
                    frameAspect,
                    showSafeArea = orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
                )
            }
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
                    .clip(AppCardShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("SOCIALMASTER v1.4", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (isRecording) "RECORDING" else if (isFinalizing) "SAVING TAKE" else "READY TO RECORD",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isRecording) RecordRed else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(cameraLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(end = 4.dp))
                AppIconButton(
                    Icons.Filled.Cameraswitch, "Switch camera", onCycleCamera,
                    enabled = !isRecording && !isFinalizing && !isStartingTake
                )
                AppIconButton(
                    Icons.Filled.Settings, "Recording setup", onOpenSetup,
                    enabled = !isRecording && !isFinalizing && !isStartingTake
                )
            }
            if (isLoadingBacking) {
                Column(
                    Modifier.align(Alignment.Center).fillMaxWidth(0.76f)
                        .clip(AppCardShape).background(MaterialTheme.colorScheme.surface)
                        .padding(18.dp)
                ) {
                    Text("Preparing backing track", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(10.dp))
                    if (backingLoadProgress > 0f) LinearProgressIndicator(progress = { backingLoadProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Replacing the previous track…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (isRecording) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)
                        .clip(AppCardShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("BACKING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text(backingName ?: "No backing track", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        AppIconButton(Icons.Filled.Replay, "Restart backing track", onRewindBacking, enabled = backingDurationMs > 0L)
                        AppIconButton(
                            if (backingPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (backingPlaying) "Pause backing" else "Play backing",
                            onPreviewBacking, enabled = backingPeaks.isNotEmpty()
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${time(backingPositionMs)} / ${time(backingDurationMs)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
                        if (backingPeaks.isNotEmpty()) {
                            Waveform(
                                peaks = backingPeaks,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                                progress = backingProgress
                            )
                        } else Spacer(Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("TAKE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.width(42.dp))
                        Waveform(livePeaks, MaterialTheme.colorScheme.secondary, Modifier.weight(1f), 1f)
                        Spacer(Modifier.width(10.dp)); Meter(interfaceLevel)
                    }
                    if (outputRoute.isNotBlank()) {
                        Text(outputRoute, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        AppButton(
                            if (isFinalizing) "SAVING TAKE…" else "STOP RECORDING",
                            onStopRecording,
                            enabled = !isFinalizing,
                            containerColor = RecordRed,
                            contentColor = Color.White
                        )
                    }
                }
            }
        }

        if (!isRecording) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
                    .background(MaterialTheme.colorScheme.surface, AppCardShape).padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("BACKING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(8.dp))
                    Text(backingName ?: "Choose a backing track", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    AppIconButton(Icons.Filled.Replay, "Restart backing track", onRewindBacking, enabled = backingDurationMs > 0L)
                    AppIconButton(
                        if (backingPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (backingPlaying) "Pause backing" else "Play backing",
                        onPreviewBacking, enabled = backingPeaks.isNotEmpty() && !isLoadingBacking
                    )
                    AppIconButton(Icons.Filled.Add, "Load backing track", onLoadBacking, enabled = !isLoadingBacking && !isRecording && !isStartingTake)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("WAVE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(38.dp))
                    if (backingPeaks.isNotEmpty()) {
                        Waveform(
                            peaks = backingPeaks,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            progress = backingProgress,
                            onSeek = onScrubBacking
                        )
                    } else {
                        Box(
                            Modifier.weight(1f).height(44.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text("No backing track loaded", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${time(backingPositionMs)} / ${time(backingDurationMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        Modifier.size(64.dp).background(RecordRed, CircleShape)
                            .clickable(enabled = !isFinalizing && !isStartingTake && !isLoadingBacking) {
                                if (isRecording) onStopRecording()
                                else onStartRecording(File(outputDir, "video_${System.currentTimeMillis()}.mp4"))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isRecording) {
                            Box(Modifier.size(22.dp).background(Color.White, RoundedCornerShape(5.dp)))
                        } else {
                            Box(Modifier.size(21.dp).background(Color.White, CircleShape))
                        }
                    }
                    Text(
                        if (isRecording) "STOP TAKE" else if (isStartingTake) "STARTING…" else if (isFinalizing) "SAVING…" else "RECORD",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
                if (outputRoute.isNotBlank()) {
                    Text(outputRoute, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (status.isNotBlank()) {
                    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Meter(level: Float) {
    LinearProgressIndicator(progress = { level.coerceIn(0f, 1f) }, modifier = Modifier.width(68.dp).height(5.dp))
}

@Composable
private fun SocialSafeAreaGuide(modifier: Modifier = Modifier, frameAspect: Float, showSafeArea: Boolean) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            // The guide has to follow the recorded frame, not the screen: the preview is letterboxed,
            // so on a wide tablet the frame is much narrower than the view.
            val frameWidth: Float
            val frameHeight: Float
            if (size.width / size.height > frameAspect) {
                frameHeight = size.height
                frameWidth = size.height * frameAspect
            } else {
                frameWidth = size.width
                frameHeight = size.width / frameAspect
            }
            val frameLeft = (size.width - frameWidth) / 2f
            val frameTop = (size.height - frameHeight) / 2f
            if (!showSafeArea) return@Canvas
            // TikTok and Instagram Reels cover the right hand column with their buttons and the
            // bottom with the caption and music, so that strip of the frame is not safe to stand in.
            val left = frameLeft + frameWidth * 0.08f
            val right = frameLeft + frameWidth * 0.70f
            val top = frameTop + frameHeight * 0.12f
            val bottom = frameTop + frameHeight * 0.72f
            val shade = Color.Black.copy(alpha = 0.22f)
            drawRect(shade, topLeft = androidx.compose.ui.geometry.Offset(frameLeft, frameTop), size = androidx.compose.ui.geometry.Size(frameWidth, top - frameTop))
            drawRect(shade, topLeft = androidx.compose.ui.geometry.Offset(frameLeft, bottom), size = androidx.compose.ui.geometry.Size(frameWidth, frameTop + frameHeight - bottom))
            drawRect(shade, topLeft = androidx.compose.ui.geometry.Offset(frameLeft, top), size = androidx.compose.ui.geometry.Size(left - frameLeft, bottom - top))
            drawRect(shade, topLeft = androidx.compose.ui.geometry.Offset(right, top), size = androidx.compose.ui.geometry.Size(frameLeft + frameWidth - right, bottom - top))
            drawRect(
                Color.White.copy(alpha = 0.82f),
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())))
            )
        }
        if (showSafeArea) {
            Text(
                "SAFE AREA - KEEP CONTENT HERE",
                Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 58.dp),
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun time(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1_000 % 60)
