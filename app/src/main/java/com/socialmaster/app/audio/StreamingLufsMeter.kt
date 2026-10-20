package com.socialmaster.app.audio

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Incremental BS.1770 meter for block-based WAV rendering. */
internal class StreamingLufsMeter(private val sampleRate: Int, private val channels: Int) {
    private val blockFrames = (sampleRate * 0.4).toInt()
    private val hopFrames = (sampleRate * 0.1).toInt().coerceAtLeast(1)
    private val filters = Array(channels) { arrayOf(Filter(highShelf(sampleRate)), Filter(highPass(sampleRate))) }
    private val energyRing = Array(channels) { DoubleArray(blockFrames) }
    private val channelSums = DoubleArray(channels)
    private val blockEnergies = mutableListOf<Double>()
    private var frames = 0L

    init {
        require(sampleRate > 0 && channels in 1..2)
    }

    fun add(samples: ShortArray, offsetSamples: Int = 0, sampleCount: Int = samples.size - offsetSamples) {
        require(offsetSamples >= 0 && sampleCount >= 0 && offsetSamples + sampleCount <= samples.size)
        require(sampleCount % channels == 0) { "PCM chunk must contain complete frames" }
        for (sampleOffset in offsetSamples until offsetSamples + sampleCount step channels) {
            val slot = (frames % blockFrames).toInt()
            for (channel in 0 until channels) {
                val input = samples[sampleOffset + channel] / 32768.0
                val filtered = filters[channel][1].process(filters[channel][0].process(input))
                val energy = filtered * filtered
                if (frames >= blockFrames) channelSums[channel] -= energyRing[channel][slot]
                energyRing[channel][slot] = energy
                channelSums[channel] += energy
            }
            frames++
            if (frames >= blockFrames && (frames - blockFrames) % hopFrames == 0L) {
                val energy = channelSums.sum() / blockFrames
                if (toLufs(energy) > -70.0) blockEnergies += energy
            }
        }
    }

    fun lufs(): Float {
        if (frames < blockFrames || blockEnergies.isEmpty()) return -70f
        val preliminary = blockEnergies.average()
        val relativeGate = 10.0.pow(((-0.691 + 10.0 * log10(preliminary)) - 10.0 + 0.691) / 10.0)
        val gated = blockEnergies.filter { it >= relativeGate }
        return if (gated.isEmpty()) -70f else toLufs(gated.average()).toFloat()
    }

    private fun toLufs(energy: Double) = -0.691 + 10.0 * log10(energy.coerceAtLeast(1e-12))

    private data class Coefficients(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    private class Filter(private val c: Coefficients) {
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun process(x: Double): Double {
            val y = c.b0 * x + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    private fun highShelf(sr: Int): Coefficients {
        val frequency = 1681.974450955533
        val gain = 3.999843853973347
        val slope = 1.0
        val a = 10.0.pow(gain / 40.0)
        val w = 2.0 * Math.PI * frequency / sr
        val c = cos(w); val s = sin(w)
        val alpha = s / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
        val beta = 2.0 * sqrt(a) * alpha
        val b0 = a * ((a + 1) + (a - 1) * c + beta)
        val b1 = -2 * a * ((a - 1) + (a + 1) * c)
        val b2 = a * ((a + 1) + (a - 1) * c - beta)
        val a0 = (a + 1) - (a - 1) * c + beta
        val a1 = 2 * ((a - 1) - (a + 1) * c)
        val a2 = (a + 1) - (a - 1) * c - beta
        return Coefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun highPass(sr: Int): Coefficients {
        val w = 2.0 * Math.PI * 38.13547087602444 / sr
        val c = cos(w); val alpha = sin(w) / (2.0 * 0.5003270373238773)
        val a0 = 1 + alpha
        return Coefficients((1 + c) / (2 * a0), -(1 + c) / a0, (1 + c) / (2 * a0), -2 * c / a0, (1 - alpha) / a0)
    }

    private fun log10(value: Double) = ln(value) / ln(10.0)
}
