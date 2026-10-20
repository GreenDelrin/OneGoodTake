package com.socialmaster.app.audio

data class MasterBus(
    var makeupGainDb: Float = 0f,
    var limiterThresholdDb: Float = -1f,
    var targetLufs: Float = -14f,   // social media loudness target
    var truePeakCeilingDb: Float = -1f
)

data class Project(
    val id: String,
    val title: String,
    val tracks: MutableList<Track> = mutableListOf(),
    val master: MasterBus = MasterBus(),
    var videoUri: String? = null,
    var syncOffsetMs: Int = 0   // audio vs video alignment
)