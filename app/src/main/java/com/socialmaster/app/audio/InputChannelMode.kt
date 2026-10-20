package com.socialmaster.app.audio

enum class InputChannelMode(val label: String, val channelSelection: Int, val outputChannels: Int) {
    INPUT_1_MONO("Input 1 · Mono", PcmChannelMapper.LEFT, 1),
    INPUT_2_MONO("Input 2 · Mono", PcmChannelMapper.RIGHT, 1),
    INPUTS_1_2_STEREO("Inputs 1 + 2 · Stereo", PcmChannelMapper.STEREO, 2)
}

data class InputLevels(val left: Float, val right: Float, val channelPeaks: List<Float> = listOf(left, right))
