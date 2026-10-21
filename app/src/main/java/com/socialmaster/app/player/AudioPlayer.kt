package com.socialmaster.app.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioDeviceInfo
import android.media.AudioTrack
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.socialmaster.app.audio.OutputRouter
import kotlin.math.max

/** Thin wrapper around ExoPlayer for backing-track playback and mix preview. */
class AudioPlayer(private val context: Context) {
    private var player: ExoPlayer? = null
    private var warmedUp = false
    private var outputDevice: AudioDeviceInfo? = null

    private fun ensure(): ExoPlayer {
        if (player == null) player = ExoPlayer.Builder(context).build()
        return player!!
    }

    /** Warm up the audio system on Samsung devices where AudioTrack needs initialization. */
    fun warmUp() {
        if (warmedUp) return
        warmedUp = true
        try {
            val sampleRate = 44100
            val channelMask = AudioFormat.CHANNEL_OUT_STEREO
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (bufferSize <= 0) return
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.play()
                // Play a very short silent clip to warm up the audio system
                val silence = ShortArray(sampleRate / 10) // 100ms of silence
                track.write(silence, 0, silence.size)
                Thread.sleep(50)
                track.stop()
                track.release()
            }
        } catch (_: Exception) {
            // Ignore warmup failures
        }
    }

    /** Play a local file from a timeline position. */
    fun play(file: java.io.File, loop: Boolean = false, startPositionMs: Long = 0L) {
        val p = ensure()
        // Keep the backing track on the same device the monitor uses, otherwise it leaves through
        // the phone while the interface stays silent. Re-applying it on a prepared player makes
        // Media3 re-resolve the tracks, so only do it when the device actually changed.
        val target = OutputRouter.pick(context)
        if (target != null && target.id != outputDevice?.id) {
            p.setPreferredAudioDevice(target)
            outputDevice = target
        }
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.repeatMode = if (loop) ExoPlayer.REPEAT_MODE_ALL else ExoPlayer.REPEAT_MODE_OFF
        p.volume = 1.0f
        p.prepare()
        if (startPositionMs > 0L) p.seekTo(startPositionMs)
        p.play()
    }

    /** Where the backing track is being sent. */
    fun routeDescription(): String = "Backing out: ${OutputRouter.describe(outputDevice)}"

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
