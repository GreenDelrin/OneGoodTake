package com.socialmaster.app.audio

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

object Dsp {
    fun gain(samples: ShortArray, db: Float): ShortArray {
        val lin = Math.pow(10.0, (db / 20.0)).toFloat()
        return ShortArray(samples.size) { i -> (samples[i] * lin).toInt().coerceIn(-32768, 32767).toShort() }
    }

    fun eq(samples: ShortArray, sr: Int, lowDb: Float, midDb: Float, highDb: Float): ShortArray {
        var x = samples.map { it.toFloat() }.toFloatArray()
        x = biquad(x, sr, 0, 200f, 0.7f, lowDb)
        x = biquad(x, sr, 1, 1000f, 1.0f, midDb)
        x = biquad(x, sr, 2, 6000f, 0.7f, highDb)
        return ShortArray(x.size) { i -> x[i].toInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun biquad(input: FloatArray, sr: Int, type: Int, freq: Float, q: Float, gainDb: Float): FloatArray {
        if (gainDb == 0f) return input
        val A = Math.pow(10.0, (gainDb / 40.0)).toFloat()
        val w0 = 2 * Math.PI * freq / sr
        val cosw = Math.cos(w0).toFloat(); val sinw = Math.sin(w0).toFloat()
        val alpha = sinw / (2 * q)
        val b0: Float; val b1: Float; val b2: Float; val a0: Float; val a1: Float; val a2: Float
        when (type) {
            0 -> {
                val sqA = (2 * Math.sqrt(A.toDouble()) * alpha).toFloat()
                b0 = A * ((A + 1) - (A - 1) * cosw + sqA)
                b1 = 2 * A * ((A - 1) - (A + 1) * cosw)
                b2 = A * ((A + 1) - (A - 1) * cosw - sqA)
                a0 = (A + 1) + (A - 1) * cosw + sqA
                a1 = -2 * ((A - 1) + (A + 1) * cosw)
                a2 = (A + 1) + (A - 1) * cosw - sqA
            }
            2 -> {
                val sqA = (2 * Math.sqrt(A.toDouble()) * alpha).toFloat()
                b0 = A * ((A + 1) + (A - 1) * cosw + sqA)
                b1 = -2 * A * ((A - 1) + (A + 1) * cosw)
                b2 = A * ((A + 1) + (A - 1) * cosw - sqA)
                a0 = (A + 1) - (A - 1) * cosw + sqA
                a1 = 2 * ((A - 1) - (A + 1) * cosw)
                a2 = (A + 1) - (A - 1) * cosw - sqA
            }
            else -> {
                b0 = 1 + alpha * A; b1 = -2 * cosw; b2 = 1 - alpha * A
                a0 = 1 + alpha / A; a1 = -2 * cosw; a2 = 1 - alpha / A
            }
        }
        val out = FloatArray(input.size)
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        for (n in input.indices) {
            val x = input[n]
            val y = (b0 / a0) * x + (b1 / a0) * x1 + (b2 / a0) * x2 - (a1 / a0) * y1 - (a2 / a0) * y2
            out[n] = y
            x2 = x1; x1 = x; y2 = y1; y1 = y
        }
        return out
    }

    // FDN reverb (4-line) with Hadamard feedback + lowpass damping
    fun reverb(samples: ShortArray, sr: Int, mix: Float): ShortArray {
        if (mix <= 0f) return samples
        val delays = intArrayOf((sr*0.0297).toInt(), (sr*0.0371).toInt(), (sr*0.0411).toInt(), (sr*0.0437).toInt())
        val lines = Array(delays.size) { FloatArray(delays[it]) }
        val idx = IntArray(delays.size)
        val damp = 0.3f
        val fb = 0.5f * (1 - damp)
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            // read delayed
            val dv = FloatArray(delays.size) { c -> lines[c][idx[c]] }
            // Hadamard mix
            val mixed0 = (dv[0] + dv[1] + dv[2] + dv[3]) * 0.5f
            val mixed1 = (dv[0] - dv[1] + dv[2] - dv[3]) * 0.5f
            val mixed2 = (dv[0] + dv[1] - dv[2] - dv[3]) * 0.5f
            val mixed3 = (dv[0] - dv[1] - dv[2] + dv[3]) * 0.5f
            val m = floatArrayOf(mixed0, mixed1, mixed2, mixed3)
            val input = samples[i].toFloat()
            for (c in delays.indices) {
                lines[c][idx[c]] = input * (if (c == 0) 1f else 0f) + m[c] * fb
                idx[c] = (idx[c] + 1) % delays[c]
            }
            val wet = (dv[0] + dv[1] + dv[2] + dv[3]) * 0.25f
            out[i] = (samples[i] * (1 - mix) + wet * mix).coerceIn(-32768f, 32767f)
        }
        return ShortArray(out.size) { i -> out[i].toInt().toShort() }
    }

    // Offline lookahead sample-peak limiter with linked stereo gain.
    fun limiter(samples: ShortArray, thresholdDb: Float): ShortArray {
        val threshold = Math.pow(10.0, (thresholdDb / 20.0)).toFloat()
        val channels = 2
        val frames = samples.size / channels
        val lookaheadFrames = 240 // 5 ms at the 48 kHz mix rate
        val output = ShortArray(samples.size)
        var gain = 1f
        for (frame in 0 until frames) {
            var futurePeak = 0f
            val end = minOf(frames, frame + lookaheadFrames + 1)
            for (future in frame until end) {
                for (channel in 0 until channels) {
                    futurePeak = maxOf(futurePeak, kotlin.math.abs(samples[future * channels + channel] / 32768f))
                }
            }
            val target = if (futurePeak > threshold) threshold / futurePeak else 1f
            gain = if (target < gain) target else minOf(target, gain + 0.001f)
            for (channel in 0 until channels) {
                val i = frame * channels + channel
                output[i] = (samples[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        if (samples.size % channels != 0) output[output.lastIndex] = samples.last()
        return output
    }

    private data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    /** BS.1770 K-weighting plus 400 ms blocks, 75% overlap, and absolute/relative gates. */
    fun lufs(samples: ShortArray, sr: Int = 44100, channels: Int = 1): Float {
        if (channels !in 1..2 || sr <= 0 || samples.size % channels != 0) return -70f
        val frames = samples.size / channels
        val filtered = Array(channels) { channel ->
            var x = FloatArray(frames) { i -> samples[i * channels + channel] / 32768f }
            x = applyFilter(x, highShelf(sr))
            applyFilter(x, highPass(sr))
        }
        val block = (sr * 0.4).toInt()
        val hop = (sr * 0.1).toInt().coerceAtLeast(1)
        if (frames < block) return -70f
        val energies = mutableListOf<Double>()
        var start = 0
        while (start + block <= frames) {
            var energy = 0.0
            for (channel in 0 until channels) {
                var channelEnergy = 0.0
                for (i in start until start + block) {
                    val value = filtered[channel][i].toDouble()
                    channelEnergy += value * value
                }
                energy += channelEnergy / block
            }
            if (-0.691 + 10.0 * log10(energy.coerceAtLeast(1e-12)) > -70.0) energies.add(energy)
            start += hop
        }
        if (energies.isEmpty()) return -70f
        val preliminary = energies.average()
        val relativeGate = 10.0.pow(((-0.691 + 10.0 * log10(preliminary)) - 10.0 + 0.691) / 10.0)
        val gated = energies.filter { it >= relativeGate }
        if (gated.isEmpty()) return -70f
        return (-0.691 + 10.0 * log10(gated.average())).toFloat()
    }

    private fun highShelf(sr: Int): Biquad {
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
        return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private fun highPass(sr: Int): Biquad {
        val w = 2.0 * Math.PI * 38.13547087602444 / sr
        val c = cos(w); val alpha = sin(w) / (2.0 * 0.5003270373238773)
        val a0 = 1 + alpha
        return Biquad((1 + c) / (2 * a0), -(1 + c) / a0, (1 + c) / (2 * a0), -2 * c / a0, (1 - alpha) / a0)
    }

    private fun applyFilter(input: FloatArray, filter: Biquad): FloatArray {
        val output = FloatArray(input.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in input.indices) {
            val x = input[i].toDouble()
            val y = filter.b0 * x + filter.b1 * x1 + filter.b2 * x2 - filter.a1 * y1 - filter.a2 * y2
            output[i] = y.toFloat()
            x2 = x1; x1 = x; y2 = y1; y1 = y
        }
        return output
    }

    private fun log10(value: Double) = ln(value) / ln(10.0)
    private fun Double.pow(exponent: Double) = Math.pow(this, exponent)
}
