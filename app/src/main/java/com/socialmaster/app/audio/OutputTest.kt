package com.socialmaster.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a short tone through the same output path the app uses and reports where it went,
 * so a silent monitoring chain can be diagnosed without recording a take.
 */
object OutputTest {
    private const val TONE_HZ = 1000.0
    private const val TONE_MS = 1200

    fun play(context: Context, onResult: (String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread({
            var track: AudioTrack? = null
            var message: String
            try {
                val rate = 48000
                val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                if (minimum <= 0) {
                    message = "Android could not open an audio output for the test tone."
                } else {
                    track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                        .setBufferSizeInBytes(maxOf(minimum * 2, 8192))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    if (track.state != AudioTrack.STATE_INITIALIZED) {
                        message = "Android could not initialize the test tone output."
                    } else {
                        OutputRouter.apply(track, context)
                        track.play()
                        val frames = rate * TONE_MS / 1000
                        val buffer = ShortArray(frames * 2)
                        for (i in 0 until frames) {
                            val value = (sin(2.0 * PI * TONE_HZ * i / rate) * 10000.0).toInt().toShort()
                            buffer[i * 2] = value
                            buffer[i * 2 + 1] = value
                        }
                        var written = 0
                        while (written < buffer.size) {
                            val count = track.write(buffer, written, buffer.size - written, AudioTrack.WRITE_BLOCKING)
                            if (count <= 0) break
                            written += count
                        }
                        Thread.sleep(200)
                        message = "Test tone played · out: ${OutputRouter.describe(track)}"
                    }
                }
            } catch (e: Exception) {
                message = "Test tone failed: ${e.message ?: "unknown audio error"}"
            } finally {
                try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) { }
            }
            main.post { onResult(message) }
        }, "SocialMasterOutputTest").start()
    }
}
