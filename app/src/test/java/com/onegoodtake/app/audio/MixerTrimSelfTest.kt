package com.onegoodtake.app.audio

import java.io.File

object MixerTrimSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val source = File.createTempFile("mixer-trim-source", ".wav")
        val output = File.createTempFile("mixer-trim-output", ".wav")
        try {
            val samples = ShortArray(480) { it.toShort() }
            WavIO.write(source, WavIO.Pcm(48_000, 1, samples))
            Mixer.mix(listOf(Track("take", "Take", "file://${source.absolutePath}")), output, trimStartMs = 2, trimEndMs = 5)
            val result = checkNotNull(WavIO.read(output))
            check(result.channels == 2 && result.sampleRate == 48_000)
            check(result.samples.size == 144 * 2)
            check(result.samples.first() == samples[96])
            check(result.samples.last() == samples[239])
            println("MixerTrimSelfTest passed")
        } finally {
            source.delete(); output.delete()
        }
    }
}
