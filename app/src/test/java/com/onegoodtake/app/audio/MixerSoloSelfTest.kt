package com.onegoodtake.app.audio

import java.io.File

object MixerSoloSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val first = File.createTempFile("mixer-solo-first", ".wav")
        val second = File.createTempFile("mixer-solo-second", ".wav")
        val output = File.createTempFile("mixer-solo-output", ".wav")
        try {
            WavIO.write(first, WavIO.Pcm(48_000, 1, ShortArray(480) { 1_000 }))
            WavIO.write(second, WavIO.Pcm(48_000, 1, ShortArray(480) { 2_000 }))
            Mixer.mix(listOf(
                Track("first", "First", "file://${first.absolutePath}", solo = true),
                Track("second", "Second", "file://${second.absolutePath}")
            ), output)
            val result = checkNotNull(WavIO.read(output))
            check(result.samples[0] == 1_000.toShort() && result.samples[1] == 1_000.toShort())
            println("MixerSoloSelfTest passed")
        } finally { first.delete(); second.delete(); output.delete() }
    }
}
