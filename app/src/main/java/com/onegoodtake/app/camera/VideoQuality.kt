package com.onegoodtake.app.camera

import androidx.camera.video.Quality

/** Recording resolution choices. UHD is only offered when the camera reports support. */
enum class VideoQuality(val label: String, val cameraQuality: Quality) {
    HD_720("720p", Quality.HD),
    FHD_1080("1080p", Quality.FHD),
    UHD_4K("4K", Quality.UHD)
}