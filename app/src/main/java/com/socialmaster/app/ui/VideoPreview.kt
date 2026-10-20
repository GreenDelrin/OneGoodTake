package com.socialmaster.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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

@Composable
fun VideoPreview(videoFile: File?, audioFile: File?, modifier: Modifier = Modifier, onBuildAudio: (() -> Unit)? = null) {
    val context = LocalContext.current
    if (videoFile == null) {
        Box(modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
            Text("Record a take to preview it", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val video = remember(videoFile, audioFile) {
        ExoPlayer.Builder(context).build().apply { volume = 0f }
    }
    val audio = remember(videoFile, audioFile) {
        audioFile?.let { ExoPlayer.Builder(context).build() }
    }
    var playing by remember(videoFile, audioFile) { mutableStateOf(false) }
    var position by remember(videoFile, audioFile) { mutableLongStateOf(0L) }
    var duration by remember(videoFile, audioFile) { mutableLongStateOf(0L) }
    var playbackError by remember(videoFile, audioFile) { mutableStateOf<String?>(null) }
    var queuedPlay by remember(videoFile) { mutableStateOf(false) }

    DisposableEffect(video, audio) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = error.message ?: "The preview player could not read this media file."
                playing = false
            }
        }
        video.addListener(listener)
        audio?.addListener(listener)
        video.setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
        video.prepare()
        audio?.setMediaItem(MediaItem.fromUri(Uri.fromFile(audioFile)))
        audio?.prepare()
        onDispose {
            video.removeListener(listener)
            audio?.removeListener(listener)
            video.release()
            audio?.release()
        }
    }
    LaunchedEffect(video, audio) {
        while (true) {
            position = video.currentPosition.coerceAtLeast(0L)
            duration = listOf(video.duration, audio?.duration ?: video.duration)
                .filter { it > 0L }.minOrNull() ?: 0L
            val shouldPlay = video.isPlaying && (audio?.isPlaying ?: true)
            if (playing != shouldPlay) playing = shouldPlay
            if (duration > 0 && position >= duration) {
                video.pause(); audio?.pause(); playing = false
            }
            delay(80)
        }
    }
    LaunchedEffect(video, audio, queuedPlay) {
        if (audio != null && queuedPlay) {
            video.seekTo(position); audio.seekTo(position)
            video.play(); audio.play()
            playing = true
            queuedPlay = false
        }
    }

    Column(modifier.fillMaxWidth()) {
        AndroidView(
            factory = { PlayerView(it).apply { player = video; useController = false } },
            update = { it.player = video },
            modifier = Modifier.fillMaxWidth().height(190.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                if (audioFile == null && onBuildAudio != null) {
                    playbackError = null
                    queuedPlay = true
                    onBuildAudio()
                } else if (playing) {
                    video.pause(); audio?.pause(); playing = false
                } else {
                    playbackError = null
                    val start = if (duration > 0 && position >= duration) 0L else position.coerceAtLeast(0L)
                    video.seekTo(start); audio?.seekTo(start)
                    video.play(); audio?.play(); playing = true
                }
            }, enabled = audioFile != null || onBuildAudio != null) {
                Text(if (audioFile == null) "Build + play" else if (playing) "Ⅱ" else "▶")
            }
            Text("${formatTime(position)} / ${formatTime(duration)}", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { fraction ->
                    val seek = (duration * fraction).toLong()
                    video.seekTo(seek); audio?.seekTo(seek); position = seek
                },
                modifier = Modifier.weight(1f),
                enabled = duration > 0
            )
        }
        if (audioFile == null && onBuildAudio == null) Text("Apply a mix to enable synchronized audio/video playback", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        playbackError?.let { Text("Preview error: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    }
}

private fun formatTime(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1_000 % 60)
