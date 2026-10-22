package com.onegoodtake.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Starts the audio output before the first real stream.
 *
 * Some interfaces (a Scarlett on a Samsung phone, for one) keep their output asleep
 * until they receive actual samples through the device the app is playing to, and the
 * sound only appears once something else has played a second or two. The warm-up has
 * to travel through the same device as the real output and has to carry real samples,
 * not silence, so the interface wakes up.
 */
object AudioWarmUp {
    /** Quiet enough to be inaudible, loud enough to be a real signal. */
    private const val TONE_AMPLITUDE = 220.0
    private const val TONE_HZ = 440.0
    private const val TONE_MS = 220

    fun warm(context: Context, sampleRate: Int = 48000) {
        var track: AudioTrack? = null
        try {
            val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) return
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build())
                .setBufferSizeInBytes(maxOf(minimum * 2, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) return
            // The warm-up has to go out of the same device the app will play through.
            OutputRouter.apply(track, context)
            track.play()
            val frames = sampleRate * TONE_MS / 1000
            val buffer = ShortArray(frames * 2)
            for (i in 0 until frames) {
                val value = (sin(2.0 * PI * TONE_HZ * i / sampleRate) * TONE_AMPLITUDE).toInt().toShort()
                buffer[i * 2] = value
                buffer[i * 2 + 1] = value
            }
            var written = 0
            while (written < buffer.size) {
                val count = track.write(buffer, written, buffer.size - written, AudioTrack.WRITE_BLOCKING)
                if (count <= 0) break
                written += count
            }
            // Let the device settle before the stream is torn down.
            Thread.sleep(120)
        } catch (_: Exception) {
            // A warm-up that fails is not worth reporting.
        } finally {
            try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) { }
        }
    }

    /** True when the sound leaves through a USB interface, which is the case that needs warming. */
    fun outputIsUsb(context: Context): Boolean {
        val device = OutputRouter.pick(context) ?: return false
        return device.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE ||
            device.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
            device.type == android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY
    }
}
