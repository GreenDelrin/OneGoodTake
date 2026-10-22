package com.onegoodtake.app.audio

import java.io.File

object Mastering {
    fun process(input: File, output: File, makeupDb: Float, limiterThreshDb: Float): Float {
        val pcm = WavIO.read(input) ?: return 0f
        var s = Dsp.gain(pcm.samples, makeupDb)
        s = Dsp.limiter(s, limiterThreshDb)
        WavIO.write(output, WavIO.Pcm(pcm.sampleRate, pcm.channels, s))
        return Dsp.lufs(s, pcm.sampleRate, pcm.channels)
    }

    fun auto(project: com.onegoodtake.app.audio.Project? = null, input: File, output: File): Float {
        val pcm = WavIO.read(input) ?: return 0f
        val measured = Dsp.lufs(pcm.samples, pcm.sampleRate, pcm.channels)
        val target = project?.master?.targetLufs ?: -14f
        val gain = (target - measured).coerceIn(-12f, 12f)
        var s = Dsp.gain(pcm.samples, gain)
        val thr = project?.master?.limiterThresholdDb ?: -1f
        s = Dsp.limiter(s, thr)
        WavIO.write(output, WavIO.Pcm(pcm.sampleRate, pcm.channels, s))
        project?.master?.makeupGainDb = gain
        return Dsp.lufs(s, pcm.sampleRate, pcm.channels)
    }
}
