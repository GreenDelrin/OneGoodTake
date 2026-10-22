package com.onegoodtake.app.audio

object StreamingLufsMeterSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val samples = ShortArray(48_000 * 2) { index ->
            val frame = index / 2
            (kotlin.math.sin(2.0 * Math.PI * 440.0 * frame / 48_000) * 8_000).toInt().toShort()
        }
        val expected = Dsp.lufs(samples, 48_000, 2)
        val meter = StreamingLufsMeter(48_000, 2)
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(4_096, samples.size - offset)
            meter.add(samples, offset, count)
            offset += count
        }
        check(kotlin.math.abs(meter.lufs() - expected) < 0.05f) { "Streaming LUFS differs: ${meter.lufs()} vs $expected" }
        println("StreamingLufsMeterSelfTest passed")
    }
}
