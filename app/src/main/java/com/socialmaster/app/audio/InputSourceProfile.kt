package com.socialmaster.app.audio

enum class InputSourceProfile(val label: String, val audioSource: Int) {
    RAW("Raw · UNPROCESSED", android.media.MediaRecorder.AudioSource.UNPROCESSED),
    COMPATIBLE("Compatible · MIC", android.media.MediaRecorder.AudioSource.MIC)
}
