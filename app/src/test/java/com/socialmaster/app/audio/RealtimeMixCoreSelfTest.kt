package com.socialmaster.app.audio

object RealtimeMixCoreSelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val source = MemorySource("backing", FloatArray(12) { if (it % 2 == 0) 12_000f else -12_000f })
        val track = Track("backing", "Backing", "memory://backing")
        val core = RealtimeMixCore(listOf(source), listOf(track))
        val block = ShortArray(4)

        check(core.render(block, 2) == 2)
        check(block.contentEquals(shortArrayOf(12_000, -12_000, 12_000, -12_000)))

        core.updateTracks(listOf(track.copy(volumeDb = -12f)))
        check(core.render(block, 2) == 2)
        check(kotlin.math.abs(block[0].toInt()) in 2_900..3_100)

        core.updateTracks(listOf(track.copy(muted = true)))
        check(core.render(block, 2) == 2)
        check(block.all { it == 0.toShort() })
        check(core.render(block, 2) == 0)

        val sine = FloatArray(4_096) { index ->
            (4_000.0 * kotlin.math.sin(2.0 * Math.PI * 100.0 * (index / 2) / RealtimeMixCore.SAMPLE_RATE)).toFloat()
        }
        val eqTrack = Track("tone", "Tone", "memory://tone")
        val eqCore = RealtimeMixCore(listOf(MemorySource("tone", sine)), listOf(eqTrack))
        val before = ShortArray(512 * 2)
        val after = ShortArray(512 * 2)
        check(eqCore.render(before, 512) == 512)
        eqCore.updateTracks(listOf(eqTrack.copy(eqLowDb = 12f)))
        check(eqCore.render(after, 512) == 512)
        val beforeRms = kotlin.math.sqrt(before.sumOf { it.toDouble() * it } / before.size)
        val afterRms = kotlin.math.sqrt(after.sumOf { it.toDouble() * it } / after.size)
        check(afterRms > beforeRms * 1.5) { "Live EQ change was not audible: $beforeRms -> $afterRms" }
        println("RealtimeMixCoreSelfTest passed")
    }

    private class MemorySource(override val id: String, private val samples: FloatArray) : RealtimeMixSource {
        private var frame = 0
        override val durationFrames: Long get() = (samples.size / 2).toLong()

        override fun readFrame(out: FloatArray): Boolean {
            if (frame >= samples.size / 2) return false
            out[0] = samples[frame * 2]
            out[1] = samples[frame * 2 + 1]
            frame++
            return true
        }

        override fun close() = Unit
    }
}
