package com.onegoodtake.app.ui

import android.net.Uri
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.min

class VideoPreviewController {
    @Volatile private var player: ExoPlayer? = null
    fun playAt(positionMs: Long) { player?.seekTo(positionMs.coerceAtLeast(0L)); player?.play() }
    fun pause() { player?.pause() }
    fun positionMs(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
    fun durationMs(): Long = player?.duration?.takeIf { it > 0L } ?: 0L
    internal fun attach(value: ExoPlayer) { player = value }
    internal fun detach(value: ExoPlayer) { if (player === value) player = null }
}

@Composable
fun VideoPreview(
    videoFile: File?, audioFile: File?, modifier: Modifier = Modifier,
    onBuildAudio: (() -> Unit)? = null,
    controller: VideoPreviewController? = null,
    showPlaybackControls: Boolean = true,
    trimStartMs: Long = 0L,
    trimEndMs: Long = 0L,
    onTrimChanged: ((Long, Long) -> Unit)? = null
) {
    val context = LocalContext.current
    if (videoFile == null) {
        Box(modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
            Text("Record a take to preview it", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    if (!videoFile.isFile) {
        Box(modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
            Text("Recorded video is missing", color = MaterialTheme.colorScheme.error)
        }
        return
    }
    val video = remember(videoFile, audioFile) {
        runCatching { ExoPlayer.Builder(context).build().apply { volume = 0f } }.getOrNull()
    }
    val audio = remember(videoFile, audioFile) {
        audioFile?.let { runCatching { ExoPlayer.Builder(context).build() }.getOrNull() }
    }
    if (video == null) {
        Box(modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
            Text("Video preview could not be opened", color = MaterialTheme.colorScheme.error)
        }
        return
    }
    var playing by remember(videoFile, audioFile) { mutableStateOf(false) }
    var position by remember(videoFile, audioFile, trimStartMs) { mutableLongStateOf(trimStartMs) }
    var duration by remember(videoFile, audioFile) { mutableLongStateOf(0L) }
    var playbackError by remember(videoFile, audioFile) { mutableStateOf<String?>(null) }
    var queuedPlay by remember(videoFile) { mutableStateOf(false) }
    val clipStart = trimStartMs.coerceAtLeast(0L).coerceAtMost(duration)
    val clipEnd = min(duration, if (trimEndMs > clipStart) trimEndMs else duration)
    val clipDuration = (clipEnd - clipStart).coerceAtLeast(0L)
    var trimRange by remember(videoFile, duration, trimStartMs, trimEndMs) {
        mutableStateOf(
            (trimStartMs.coerceIn(0L, duration).toFloat())..
                (if (trimEndMs > trimStartMs) trimEndMs.coerceIn(0L, duration).toFloat() else duration.toFloat())
        )
    }

    DisposableEffect(video, audio, controller) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = error.message ?: "The preview player could not read this media file."
                playing = false
            }
        }
        controller?.attach(video)
        video.addListener(listener)
        audio?.addListener(listener)
        try {
            video.setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
            video.prepare()
            audioFile?.let { file -> audio?.setMediaItem(MediaItem.fromUri(Uri.fromFile(file))) }
            audio?.prepare()
            video.seekTo(trimStartMs)
        } catch (e: Exception) {
            playbackError = e.message ?: "The video preview could not be prepared."
        }
        onDispose {
            video.removeListener(listener)
            audio?.removeListener(listener)
            controller?.detach(video)
            video.release()
            audio?.release()
        }
    }
    LaunchedEffect(video, audio, trimStartMs, trimEndMs) {
        while (true) {
            duration = video.duration.coerceAtLeast(0L)
            val start = trimStartMs.coerceIn(0L, duration)
            val end = min(duration, if (trimEndMs > start) trimEndMs else duration)
            if (video.isPlaying && video.currentPosition < start) {
                video.seekTo(start)
                audio?.seekTo(0L)
            }
            position = video.currentPosition.coerceIn(start, end.coerceAtLeast(start))
            val shouldPlay = video.isPlaying && (audio?.isPlaying ?: true)
            if (playing != shouldPlay) playing = shouldPlay
            if (end > start && position >= end) {
                video.pause(); audio?.pause(); playing = false
            }
            delay(80)
        }
    }
    LaunchedEffect(video, audio, queuedPlay) {
        if (audio != null && queuedPlay) {
            val start = position.coerceIn(clipStart, clipEnd)
            video.seekTo(start); audio.seekTo((start - clipStart).coerceAtLeast(0L))
            video.play(); audio.play()
            playing = true
            queuedPlay = false
        }
    }

    Column(modifier.fillMaxWidth()) {
        AndroidView(
            factory = { viewContext ->
                runCatching { PlayerView(viewContext).apply { player = video; useController = false } }
                    .getOrElse { FrameLayout(viewContext).apply { addView(TextView(viewContext).apply { text = "Video preview unavailable" }) } }
            },
            update = { view -> if (view is PlayerView) view.player = video },
            modifier = Modifier.fillMaxWidth().height(190.dp)
        )
        if (showPlaybackControls) Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                if (audioFile == null && onBuildAudio != null) {
                    playbackError = null
                    queuedPlay = true
                    onBuildAudio()
                } else if (playing) {
                    video.pause(); audio?.pause(); playing = false
                } else {
                    playbackError = null
                    val start = if (clipDuration > 0 && position >= clipEnd) clipStart else position.coerceAtLeast(clipStart)
                    video.seekTo(start); audio?.seekTo((start - clipStart).coerceAtLeast(0L))
                    video.play(); audio?.play(); playing = true
                }
            }, enabled = audioFile != null || onBuildAudio != null) {
                Text(if (audioFile == null) "Build + play" else if (playing) "Ⅱ" else "▶")
            }
            Text("${formatTime(position)} / ${formatTime(clipEnd)}", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = if (clipDuration > 0L) ((position - clipStart).toFloat() / clipDuration).coerceIn(0f, 1f) else 0f,
                onValueChange = { fraction ->
                    val seek = clipStart + (clipDuration * fraction).toLong()
                    video.seekTo(seek); audio?.seekTo((seek - clipStart).coerceAtLeast(0L)); position = seek
                },
                modifier = Modifier.weight(1f),
                enabled = clipDuration > 0L
            )
        }
        if (onTrimChanged != null && duration > 500L) {
            val rangeStart = trimRange.start.coerceIn(0f, duration.toFloat() - 500f)
            val rangeEnd = trimRange.endInclusive.coerceIn(rangeStart + 500f, duration.toFloat())
            Text("TRIM  ${formatTime(rangeStart.toLong())} — ${formatTime(rangeEnd.toLong())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            RangeSlider(
                value = rangeStart..rangeEnd,
                onValueChange = { range ->
                    val start = range.start.coerceIn(0f, duration.toFloat() - 500f)
                    val end = range.endInclusive.coerceIn(start + 500f, duration.toFloat())
                    trimRange = start..end
                },
                onValueChangeFinished = { onTrimChanged(trimRange.start.toLong(), trimRange.endInclusive.toLong()) },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (audioFile == null && onBuildAudio == null && showPlaybackControls) Text("Apply a mix to enable synchronized audio/video playback", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        playbackError?.let { Text("Preview error: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    }
}

private fun formatTime(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1_000 % 60)
