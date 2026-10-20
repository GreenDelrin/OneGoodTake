package com.socialmaster.app.audio

import java.io.File

object Mixer {
    /** Mix tracks to 48 kHz stereo; mono sources are centered. */
    fun mix(tracks: List<Track>, out: File): Float {
        val pcms = tracks.filterNot { it.muted }.mapNotNull { t ->
            val f = File(t.sourceUri.removePrefix("file://"))
            WavIO.read(f)?.let { pcm ->
                val skip = (pcm.sampleRate.toLong() * t.sourceOffsetMs.coerceAtLeast(0L) / 1000L * pcm.channels)
                    .coerceAtMost(pcm.samples.size.toLong()).toInt()
                if (skip >= pcm.samples.size) null
                else WavIO.Pcm(pcm.sampleRate, pcm.channels, pcm.samples.copyOfRange(skip, pcm.samples.size)) to t
            }
        }
        require(pcms.isNotEmpty()) { "No readable PCM audio tracks were found" }
        val sr = 48000
        val prepared = pcms.map { (pcm, track) ->
            val stereo = when (pcm.channels) {
                1 -> ShortArray(pcm.samples.size * 2).also { outSamples ->
                    for (i in pcm.samples.indices) { outSamples[i * 2] = pcm.samples[i]; outSamples[i * 2 + 1] = pcm.samples[i] }
                }
                2 -> pcm.samples
                else -> throw IllegalArgumentException("Only mono/stereo tracks are supported")
            }
            PcmResampler.resample(stereo, 2, pcm.sampleRate, sr) to track
        }
        val maxFrames = prepared.maxOf { it.first.size / 2 }
        val left = FloatArray(maxFrames)
        val right = FloatArray(maxFrames)
        for ((converted, t) in prepared) {
            val l = ShortArray(converted.size / 2) { converted[it * 2] }
            val r = ShortArray(converted.size / 2) { converted[it * 2 + 1] }
            val eqL = Dsp.eq(l, sr, t.eqLowDb, t.eqMidDb, t.eqHighDb)
            val eqR = Dsp.eq(r, sr, t.eqLowDb, t.eqMidDb, t.eqHighDb)
            val linked = ShortArray(converted.size)
            for (i in eqL.indices) { linked[i * 2] = eqL[i]; linked[i * 2 + 1] = eqR[i] }
            val compressed = PcmCompressor.compressStereo(linked, sr, t.compressorAmount)
            val compressedL = ShortArray(compressed.size / 2) { compressed[it * 2] }
            val compressedR = ShortArray(compressed.size / 2) { compressed[it * 2 + 1] }
            val wetL = Dsp.reverb(compressedL, sr, t.reverbSend)
            val wetR = Dsp.reverb(compressedR, sr, t.reverbSend)
            val gain = Math.pow(10.0, (t.volumeDb / 20.0)).toFloat()
            for (i in wetL.indices) { left[i] += wetL[i] * gain; right[i] += wetR[i] * gain }
        }
        var peak = 0f
        for (i in 0 until maxFrames) peak = maxOf(peak, kotlin.math.abs(left[i]), kotlin.math.abs(right[i]))
        val scale = if (peak > 32767f) 32767f / peak else 1f
        val outSamples = ShortArray(maxFrames * 2)
        for (i in 0 until maxFrames) {
            outSamples[i * 2] = (left[i] * scale).toInt().coerceIn(-32768, 32767).toShort()
            outSamples[i * 2 + 1] = (right[i] * scale).toInt().coerceIn(-32768, 32767).toShort()
        }
        WavIO.write(out, WavIO.Pcm(sr, 2, outSamples))
        return Dsp.lufs(outSamples, sr, 2)
    }
}
