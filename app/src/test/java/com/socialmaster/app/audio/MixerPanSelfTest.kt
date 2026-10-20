package com.socialmaster.app.audio

import java.io.File

object MixerPanSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val source = File.createTempFile("mixer-pan-source", ".wav")
        val output = File.createTempFile("mixer-pan-output", ".wav")
        try {
            WavIO.write(source, WavIO.Pcm(48_000, 2, ShortArray(200) { 1_000 }))
            Mixer.mix(listOf(Track("take", "Take", "file://${source.absolutePath}", pan = 1f)), output)
            val result = checkNotNull(WavIO.read(output))
            check(result.samples[0] == 0.toShort())
            check(result.samples[1] == 1_000.toShort())
            println("MixerPanSelfTest passed")
        } finally {
            source.delete(); output.delete()
        }
    }
}
