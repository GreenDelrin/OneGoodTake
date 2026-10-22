package com.onegoodtake.app.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object Exporter {
    private class Chunk(val data: ByteArray, val ptsUs: Long, val flags: Int)

    fun export(
        videoFile: File, audioWav: File, out: File,
        syncOffsetMs: Int = 0, trimStartMs: Long = 0L, trimEndMs: Long = 0L,
        rotationOverride: Int? = null
    ): Boolean {
        val source = WavIO.read(audioWav) ?: return false
        if (source.channels !in 1..2 || source.samples.size % source.channels != 0) return false
        val offsetFrames = source.sampleRate.toLong() * syncOffsetMs / 1000
        val pcm = when {
            offsetFrames == 0L -> source
            offsetFrames > 0 -> {
                val paddingSamples = offsetFrames * source.channels
                if (paddingSamples > Int.MAX_VALUE - source.samples.size) return false
                WavIO.Pcm(source.sampleRate, source.channels,
                    ShortArray(source.samples.size + paddingSamples.toInt()).also {
                        System.arraycopy(source.samples, 0, it, paddingSamples.toInt(), source.samples.size)
                    })
            }
            else -> {
                val skipSamples = (-offsetFrames * source.channels).coerceAtMost(source.samples.size.toLong()).toInt()
                WavIO.Pcm(source.sampleRate, source.channels, source.samples.copyOfRange(skipSamples, source.samples.size))
            }
        }

        val extractor = MediaExtractor()
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        return try {
            extractor.setDataSource(videoFile.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return false
            val videoFormat = extractor.getTrackFormat(videoTrack)
            val sourceDurationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) videoFormat.getLong(MediaFormat.KEY_DURATION) else Long.MAX_VALUE
            val trimStartUs = trimStartMs.coerceAtLeast(0L) * 1000L
            val trimEndUs = if (trimEndMs > 0L) minOf(trimEndMs * 1000L, sourceDurationUs) else sourceDurationUs
            if (trimEndUs <= trimStartUs) return false
            extractor.selectTrack(videoTrack)
            extractor.seekTo(trimStartMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val videoStartUs = extractor.sampleTime
            if (videoStartUs < 0L || videoStartUs >= trimEndUs) return false

            // ---- Rotation ----
            val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = rotationOverride ?: run {
                val detected = try {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(videoFile.absolutePath)
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    } finally { r.release() }
                } catch (_: Exception) { 0 }
                // No rotation metadata but frames are landscape -> assume portrait capture
                if (detected == 0 && width > height) 90 else detected
            }

            // ---- Encode audio FIRST so we know its real output format ----
            val audioStartMs = (videoStartUs - trimStartUs).coerceAtLeast(0L) / 1000L
            val alignedAudio = skipAudioStart(pcm, audioStartMs) ?: return false
            val aacFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", pcm.sampleRate, pcm.channels)
            aacFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, 2) // AAC-LC
            aacFormat.setInteger(MediaFormat.KEY_BIT_RATE, 192000)
            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
            encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val (audioOutFormat, audioChunks) = encodeAudio(alignedAudio, encoder) ?: return false

            // ---- Now set up the muxer: hint -> all tracks -> start ----
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotation)
            val outputVideoTrack = muxer.addTrack(videoFormat)
            val outputAudioTrack = muxer.addTrack(audioOutFormat)
            muxer.start()
            muxerStarted = true

            copyVideoTrack(extractor, muxer, outputVideoTrack, videoStartUs, trimEndUs)

            val info = MediaCodec.BufferInfo()
            for (c in audioChunks) {
                info.set(0, c.data.size, c.ptsUs, c.flags)
                muxer.writeSampleData(outputAudioTrack, ByteBuffer.wrap(c.data), info)
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
            try { encoder?.stop() } catch (_: Exception) { }
            try { encoder?.release() } catch (_: Exception) { }
            try { extractor.release() } catch (_: Exception) { }
        }
    }

    private fun copyVideoTrack(extractor: MediaExtractor, muxer: MediaMuxer, outputTrack: Int, baseTimeUs: Long, endTimeUs: Long) {
        val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            val sampleTime = extractor.sampleTime
            if (size < 0 || sampleTime < 0 || sampleTime >= endTimeUs) return
            info.set(0, size, sampleTime - baseTimeUs, extractor.sampleFlags)
            muxer.writeSampleData(outputTrack, buffer, info)
            extractor.advance()
        }
    }

    private fun skipAudioStart(pcm: WavIO.Pcm, milliseconds: Long): WavIO.Pcm? {
        val frames = pcm.sampleRate.toLong() * milliseconds.coerceAtLeast(0L) / 1000L
        val skip = (frames * pcm.channels).coerceAtMost(pcm.samples.size.toLong()).toInt()
        if (skip >= pcm.samples.size) return null
        return if (skip == 0) pcm else WavIO.Pcm(pcm.sampleRate, pcm.channels, pcm.samples.copyOfRange(skip, pcm.samples.size))
    }

    /** Encodes PCM to AAC, returning the encoder's real output format and all encoded chunks. */
    private fun encodeAudio(pcm: WavIO.Pcm, encoder: MediaCodec): Pair<MediaFormat, List<Chunk>>? {
        val info = MediaCodec.BufferInfo()
        val chunks = ArrayList<Chunk>()
        var outFormat: MediaFormat? = null
        var samplesWritten = 0
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = encoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val input = encoder.getInputBuffer(inputIndex)!!
                    input.clear()
                    val samples = minOf(input.remaining() / 2, pcm.samples.size - samplesWritten)
                    val alignedSamples = samples - samples % pcm.channels
                    val pts = (samplesWritten / pcm.channels * 1_000_000L) / pcm.sampleRate
                    if (alignedSamples <= 0) {
                        encoder.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        repeat(alignedSamples) { input.putShort(pcm.samples[samplesWritten + it]) }
                        encoder.queueInputBuffer(inputIndex, 0, alignedSamples * 2, pts, 0)
                        samplesWritten += alignedSamples
                    }
                }
            }
            val outputIndex = encoder.dequeueOutputBuffer(info, 10_000)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = encoder.outputFormat
                outputIndex >= 0 -> {
                    val output = encoder.getOutputBuffer(outputIndex)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size > 0 && !isConfig) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        output.get(bytes)
                        chunks.add(Chunk(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG.inv()))
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        val format = outFormat ?: return null
        return format to chunks
    }
}
