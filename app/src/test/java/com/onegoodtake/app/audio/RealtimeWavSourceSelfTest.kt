package com.onegoodtake.app.audio

import java.io.File

object RealtimeWavSourceSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val file = File.createTempFile("realtime-wav", ".wav")
        try {
            WavIO.write(file, WavIO.Pcm(24_000, 1, shortArrayOf(0, 1_000, 2_000, 3_000)))
            val source = RealtimeWavSource("backing", file, 0)
            check(source.durationFrames == 8L)
            val actual = FloatArray(8) { frame ->
                val output = FloatArray(2)
                check(source.readFrame(output))
                check(output[0] == output[1])
                output[0]
            }
            check(actual.contentEquals(floatArrayOf(0f, 500f, 1_000f, 1_500f, 2_000f, 2_500f, 3_000f, 3_000f)))
            check(!source.readFrame(FloatArray(2)))
            source.close()
            println("RealtimeWavSourceSelfTest passed")
        } finally {
            file.delete()
        }
    }
}
