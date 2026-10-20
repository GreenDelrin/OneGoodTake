package com.socialmaster.app.audio

import java.io.File

object WavPeaksSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val file = File.createTempFile("wav-peaks", ".wav")
        try {
            WavIO.write(file, WavIO.Pcm(1_000, 2, shortArrayOf(0, 100, -200, 300, -400, 200, 10, 20)))
            check(WavIO.durationMs(file) == 4L)
            val peaks = checkNotNull(WavIO.peaks(file, 2))
            check(peaks.size == 2)
            check(kotlin.math.abs(peaks[0] - 300f / 32768f) < 0.00001f)
            check(kotlin.math.abs(peaks[1] - 400f / 32768f) < 0.00001f)
            check(WavIO.peaks(file, 0) == null)
            println("WavPeaksSelfTest passed")
        } finally { file.delete() }
    }
}
