package com.socialmaster.app.audio

import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal interface RealtimeMixSource : Closeable {
    val id: String
    val durationFrames: Long
    fun readFrame(out: FloatArray): Boolean
}

internal class RealtimeMixCore(
    sources: List<RealtimeMixSource>,
    tracks: List<Track>,
    maxDurationFrames: Long? = null,
    startOffsetFrames: Long = 0L
) {
    private val sources = sources.toList()
    private val parameters = AtomicReference(tracks.associate { it.id to it.copy() })
    private val processors = this.sources.associate { it.id to RealtimeTrackProcessor() }
    private val sourceFrames = Array(this.sources.size) { FloatArray(2) }
    private val processedFrames = Array(this.sources.size) { FloatArray(2) }
    private val sourceDurationFrames = this.sources.maxOfOrNull { it.durationFrames } ?: 0L
    private val startOffsetFrames = startOffsetFrames.coerceAtLeast(0L)
    private val durationFrames = min(
        (sourceDurationFrames - this.startOffsetFrames).coerceAtLeast(0L),
        maxDurationFrames ?: Long.MAX_VALUE
    )
    private var positionFrames = 0L
    private var timelineFrame = 0L
    private var masterGain = 1f
    private val discardFrame = ShortArray(2)

    val positionMs: Long get() = positionFrames * 1000L / SAMPLE_RATE
    val durationMs: Long get() = durationFrames * 1000L / SAMPLE_RATE

    init {
        require(this.sources.map { it.id }.toSet().size == this.sources.size) { "Track IDs must be unique" }
    }

    fun updateTracks(tracks: List<Track>) {
        parameters.set(tracks.associate { it.id to it.copy() })
    }

    fun render(output: ShortArray, maxFrames: Int): Int {
        require(maxFrames > 0 && output.size >= maxFrames * 2) { "Output must hold interleaved stereo PCM" }
        if (durationFrames == 0L) return 0

        val snapshot = parameters.get()
        val soloActive = snapshot.values.any { it.solo && !it.muted }
        for (index in sources.indices) {
            val track = snapshot[sources[index].id]
            if (track != null) processors.getValue(sources[index].id).configure(track)
        }
        while (timelineFrame < startOffsetFrames && timelineFrame < sourceDurationFrames) {
            processFrame(snapshot, soloActive, discardFrame, 0)
            timelineFrame++
        }
        val frames = min(maxFrames.toLong(), (durationFrames - positionFrames).coerceAtLeast(0L)).toInt()
        if (frames == 0) return 0
        for (frame in 0 until frames) {
            processFrame(snapshot, soloActive, output, frame * 2)
            timelineFrame++
        }
        positionFrames += frames
        return frames
    }

    private fun processFrame(snapshot: Map<String, Track>, soloActive: Boolean, output: ShortArray, outputOffset: Int) {
        var left = 0f
        var right = 0f
        for (index in sources.indices) {
            val params = snapshot[sources[index].id]
            val input = sourceFrames[index]
            if (!sources[index].readFrame(input)) { input[0] = 0f; input[1] = 0f }
            if (params == null) continue
            processors.getValue(sources[index].id).process(input[0], input[1], processedFrames[index])
            if (!params.muted && (!soloActive || params.solo)) {
                left += processedFrames[index][0]
                right += processedFrames[index][1]
            }
        }
        val peak = max(abs(left), abs(right))
        val targetGain = if (peak > 30_000f) 30_000f / peak else 1f
        masterGain = if (targetGain < masterGain) targetGain else min(targetGain, masterGain + 0.0002f)
        output[outputOffset] = (left * masterGain).toInt().coerceIn(-32768, 32767).toShort()
        output[outputOffset + 1] = (right * masterGain).toInt().coerceIn(-32768, 32767).toShort()
    }

    companion object { const val SAMPLE_RATE = 48_000 }
}

private class RealtimeTrackProcessor {
    private val lowL = RealtimeBiquad(0, 200.0, 0.7, RealtimeMixCore.SAMPLE_RATE)
    private val midL = RealtimeBiquad(1, 1_000.0, 1.0, RealtimeMixCore.SAMPLE_RATE)
    private val highL = RealtimeBiquad(2, 6_000.0, 0.7, RealtimeMixCore.SAMPLE_RATE)
    private val lowR = RealtimeBiquad(0, 200.0, 0.7, RealtimeMixCore.SAMPLE_RATE)
    private val midR = RealtimeBiquad(1, 1_000.0, 1.0, RealtimeMixCore.SAMPLE_RATE)
    private val highR = RealtimeBiquad(2, 6_000.0, 0.7, RealtimeMixCore.SAMPLE_RATE)
    private val reverb = RealtimeReverb(RealtimeMixCore.SAMPLE_RATE)
    private var compressorEnvelope = 0.0
    private var compressorAmount = 0f
    private var compressorThreshold = 0.0
    private var compressorRatio = 1.0
    private var attack = 0.0
    private var release = 0.0
    private var gain = 1f
    private var panLeft = 1f
    private var panRight = 1f
    private var reverbSend = 0f

    fun configure(track: Track) {
        lowL.configure(track.eqLowDb); lowR.configure(track.eqLowDb)
        midL.configure(track.eqMidDb); midR.configure(track.eqMidDb)
        highL.configure(track.eqHighDb); highR.configure(track.eqHighDb)
        val amount = track.compressorAmount.coerceIn(0f, 1f)
        if (amount != compressorAmount) {
            compressorAmount = amount
            compressorThreshold = 10.0.pow((-12.0 - 12.0 * amount) / 20.0) * 32768.0
            compressorRatio = 1.0 + 5.0 * amount
            attack = expCoefficient(0.005)
            release = expCoefficient(0.100)
        }
        gain = 10.0.pow(track.volumeDb.coerceIn(-60f, 6f) / 20.0).toFloat()
        val pan = track.pan.coerceIn(-1f, 1f)
        panLeft = if (pan > 0f) 1f - pan else 1f
        panRight = if (pan < 0f) 1f + pan else 1f
        reverbSend = track.reverbSend.coerceIn(0f, 1f)
    }

    fun process(inputLeft: Float, inputRight: Float, output: FloatArray) {
        var left = highL.process(midL.process(lowL.process(inputLeft)))
        var right = highR.process(midR.process(lowR.process(inputRight)))
        val level = max(abs(left), abs(right)).toDouble()
        val coefficient = if (level > compressorEnvelope) attack else release
        compressorEnvelope = coefficient * compressorEnvelope + (1.0 - coefficient) * level
        if (compressorAmount > 0f && compressorEnvelope > compressorThreshold) {
            val compressed = compressorThreshold + (compressorEnvelope - compressorThreshold) / compressorRatio
            val reduction = (compressed / compressorEnvelope).toFloat()
            left *= reduction; right *= reduction
        }
        reverb.process(left, right, reverbSend, output)
        output[0] *= gain * panLeft
        output[1] *= gain * panRight
    }

    private fun expCoefficient(seconds: Double) = kotlin.math.exp(-1.0 / (RealtimeMixCore.SAMPLE_RATE * seconds))
}

private class RealtimeBiquad(
    private val type: Int,
    private val frequency: Double,
    private val q: Double,
    private val sampleRate: Int
) {
    private var gainDb = Float.NaN
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun configure(gain: Float) {
        if (gain == gainDb) return
        gainDb = gain
        if (gain == 0f) {
            b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
            return
        }
        val a = 10.0.pow(gain / 40.0)
        val w = 2.0 * Math.PI * frequency / sampleRate
        val c = cos(w); val s = sin(w); val alpha = s / (2.0 * q)
        val values = when (type) {
            0 -> {
                val sqA = 2.0 * sqrt(a) * alpha
                doubleArrayOf(
                    a * ((a + 1) - (a - 1) * c + sqA),
                    2 * a * ((a - 1) - (a + 1) * c),
                    a * ((a + 1) - (a - 1) * c - sqA),
                    (a + 1) + (a - 1) * c + sqA,
                    -2 * ((a - 1) + (a + 1) * c),
                    (a + 1) + (a - 1) * c - sqA
                )
            }
            2 -> {
                val sqA = 2.0 * sqrt(a) * alpha
                doubleArrayOf(
                    a * ((a + 1) + (a - 1) * c + sqA),
                    -2 * a * ((a - 1) + (a + 1) * c),
                    a * ((a + 1) + (a - 1) * c - sqA),
                    (a + 1) - (a - 1) * c + sqA,
                    2 * ((a - 1) - (a + 1) * c),
                    (a + 1) - (a - 1) * c - sqA
                )
            }
            else -> doubleArrayOf(
                1 + alpha * a, -2 * c, 1 - alpha * a,
                1 + alpha / a, -2 * c, 1 - alpha / a
            )
        }
        b0 = values[0] / values[3]; b1 = values[1] / values[3]; b2 = values[2] / values[3]
        a1 = values[4] / values[3]; a2 = values[5] / values[3]
    }

    fun process(input: Float): Float {
        val x = input.toDouble()
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y.toFloat()
    }
}

private class RealtimeReverb(sampleRate: Int) {
    // Plate reverb: longer delays, higher density, longer decay, HF damping
    private val delays = intArrayOf(
        (sampleRate * 0.045).toInt(), (sampleRate * 0.063).toInt(),
        (sampleRate * 0.078).toInt(), (sampleRate * 0.092).toInt(),
        (sampleRate * 0.115).toInt(), (sampleRate * 0.137).toInt(),
        (sampleRate * 0.168).toInt(), (sampleRate * 0.197).toInt()
    )
    private val lines = Array(2) { Array(delays.size) { line -> FloatArray(delays[line]) } }
    private val positions = IntArray(delays.size)
    // Plate reverb: longer decay, HF damping
    private val feedback = 0.75f
    private val damp = 0.15f  // HF damping for metal plate character

    fun process(left: Float, right: Float, amount: Float, output: FloatArray) {
        // Read delayed samples
        val l = FloatArray(delays.size) { lines[0][it][positions[it]] }
        val r = FloatArray(delays.size) { lines[1][it][positions[it]] }

        // Hadamard mix for diffusion (8x8 matrix)
        val mixed = FloatArray(delays.size)
        for (i in 0 until 4) {
            val a = l[i]; val b = l[i+4]
            val c = r[i]; val d = r[i+4]
            mixed[i] = (l[i] + l[i+4]) * 0.5f
            mixed[i+4] = (l[i] - l[i+4]) * 0.5f
            mixed[i] += (r[i] + r[i+4]) * 0.25f
            mixed[i+4] += (r[i] - r[i+4]) * 0.25f
        }

        // Write with HF damping in feedback path
        val fb = 0.7f * (1.0f - 0.15f)  // feedback with HF damping
        for (c in 0..1) {
            val lines_c = lines[c]
            for (i in 0 until delays.size) {
                val input = if (c == 0) left else right
                val delayed = lines_c[i][positions[i]]
                lines_c[i][positions[i]] = input + mixed[i] * feedback * (1.0f - 0.15f)  // HF damping in feedback
                positions[i] = (positions[i] + 1) % delays[i]
            }
        }

        // Sum all delayed signals for wet signal
        var wetLeft = 0.0f
        var wetRight = 0.0f
        for (i in 0 until delays.size) {
            wetLeft += lines[0][i][positions[i]]
            wetRight += lines[1][i][positions[i]]
        }
        wetLeft *= 0.125f
        wetRight *= 0.125f

        // Dry signal at 100%, wet mixed in at 'amount'
        output[0] = left + wetLeft * amount
        output[1] = right + wetRight * amount
    }
}