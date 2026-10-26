package com.onegoodtake.app.audio

import java.io.File

object Mixer {
    /** Stream the selected timeline to a 48 kHz stereo WAV instead of holding every source in memory. */
    @JvmOverloads
    fun mix(tracks: List<Track>, out: File, trimStartMs: Long = 0L, trimEndMs: Long = 0L): Float {
        val soloActive = tracks.any { it.solo && !it.muted }
        val activeTracks = tracks.filter { !it.muted && (!soloActive || it.solo) }
        val sources = mutableListOf<RealtimeMixSource>()
        try {
            for (track in activeTracks) {
                val file = fileFromUri(track.sourceUri) ?: continue
                try { sources += RealtimeWavSource(track.id, file, track.sourceOffsetMs, track.sourceChannel) } catch (_: Exception) { }
            }
            require(sources.isNotEmpty()) { "No readable PCM audio tracks were found" }

            val startFrames = RealtimeMixCore.SAMPLE_RATE.toLong() * trimStartMs.coerceAtLeast(0L) / 1000L
            val endFrames = if (trimEndMs > 0L) RealtimeMixCore.SAMPLE_RATE.toLong() * trimEndMs / 1000L else null
            val maxFrames = endFrames?.let { (it - startFrames).coerceAtLeast(0L) }
            val engine = RealtimeMixCore(sources, activeTracks, maxFrames, startFrames)
            require(engine.durationMs > 0L) { "The selected trim range contains no audio" }

            val meter = StreamingLufsMeter(RealtimeMixCore.SAMPLE_RATE, 2)
            WavIO.StreamingWriter(out, RealtimeMixCore.SAMPLE_RATE, 2).use { writer ->
                val block = ShortArray(512 * 2)
                while (true) {
                    val frames = engine.render(block, 512)
                    if (frames == 0) break
                    val samples = frames * 2
                    writer.write(block, 0, samples)
                    meter.add(block, 0, samples)
                }
            }
            return meter.lufs()
        } finally {
            sources.forEach { try { it.close() } catch (_: Exception) { } }
        }
    }
}
