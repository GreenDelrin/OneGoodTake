package com.socialmaster.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack

object OutputRouter {
    fun pick(context: Context, preferredId: Int? = null, avoidSpeaker: Boolean = false): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
        preferredId?.let { id -> outs.firstOrNull { it.id == id }?.let { return it } }
        val order = mutableListOf(
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET
        )
        if (!avoidSpeaker) order += AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        for (type in order) outs.firstOrNull { it.type == type }?.let { return it }
        return null
    }

    /**
     * Routes [track] to the best output device and returns the device it was aimed at.
     * setPreferredDevice() fails when the device cannot take the stream; forcing it anyway leaves
     * the track with no output at all, so in that case the system default is kept instead.
     */
    fun apply(track: AudioTrack, context: Context, preferredId: Int? = null, avoidSpeaker: Boolean = false): AudioDeviceInfo? {
        val target = pick(context, preferredId, avoidSpeaker)
        if (target != null && !track.setPreferredDevice(target)) return null
        return target
    }

    /** Describes the device a track is really playing through. */
    fun describe(track: AudioTrack): String {
        val device = track.routedDevice ?: return "no output device (check media volume and routing)"
        return "${device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "output"} · ${track.sampleRate} Hz · ${track.channelCount} ch"
    }

    fun describe(device: AudioDeviceInfo?): String =
        device?.let { "${it.productName?.toString()?.takeIf { name -> name.isNotBlank() } ?: "output"} · id ${it.id}" }
            ?: "system default"
}
