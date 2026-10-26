package com.onegoodtake.app.audio

/**
 * A single audio track in the project. Backing track and recorded interface
 * audio are both represented as tracks.
 */
data class Track(
    val id: String,
    val name: String,
    val sourceUri: String,        // wav/mp3 file or recorded take
    var sourceOffsetMs: Long = 0L,
    var sourceChannel: Int = -1,  // -1 = all channels; >=0 = read only this channel (mono)
    var volumeDb: Float = 0f,     // -60..+6
    var pan: Float = 0f,         // -1..1
    var eqLowDb: Float = 0f,     // low shelf
    var eqMidDb: Float = 0f,     // peak
    var eqHighDb: Float = 0f,    // high shelf
    var reverbSend: Float = 0f,  // 0..1
    var compressorAmount: Float = 0f, // 0..1
    var muted: Boolean = false,
    var solo: Boolean = false
)
