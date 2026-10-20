package com.socialmaster.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

/** Thin wrapper around ExoPlayer for backing-track playback and mix preview. */
class AudioPlayer(private val context: Context) {
    private var player: ExoPlayer? = null

    private fun ensure(): ExoPlayer {
        if (player == null) player = ExoPlayer.Builder(context).build()
        return player!!
    }

    /** Play a local file from a timeline position. */
    fun play(file: java.io.File, loop: Boolean = false, startPositionMs: Long = 0L) {
        val p = ensure()
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.repeatMode = if (loop) ExoPlayer.REPEAT_MODE_ALL else ExoPlayer.REPEAT_MODE_OFF
        p.volume = 1.0f
        p.prepare()
        if (startPositionMs > 0L) p.seekTo(startPositionMs)
        p.play()
    }

    fun seekTo(positionMs: Long) { player?.seekTo(positionMs.coerceAtLeast(0L)) }
    fun currentPositionMs(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
    fun durationMs(): Long = player?.duration?.takeIf { it > 0L } ?: 0L
    fun isPlaying(): Boolean = player?.isPlaying == true
    fun setVolume(value: Float) { player?.volume = value.coerceIn(0f, 1f) }

    fun pause() { player?.pause() }
    fun resume() { player?.play() }
    fun stop() { player?.stop() }
    fun release() { player?.release(); player = null }
}
