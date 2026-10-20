package com.socialmaster.app.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object Exporter {
    /** Mux the camera's video-only track with the final mono/stereo WAV audio. */
    fun export(videoFile: File, audioWav: File, out: File, syncOffsetMs: Int = 0): Boolean {
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
            extractor.selectTrack(videoTrack)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputVideoTrack = muxer.addTrack(extractor.getTrackFormat(videoTrack))

            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", pcm.sampleRate, pcm.channels)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, 2)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 192000)
            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm")
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val outputAudioTrack = muxer.addTrack(encoder.outputFormat)
            muxer.start()
            muxerStarted = true

            copyVideoTrack(extractor, muxer, outputVideoTrack)
            encodeAudio(pcm, encoder, muxer, outputAudioTrack)
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

    private fun copyVideoTrack(extractor: MediaExtractor, muxer: MediaMuxer, outputTrack: Int) {
        val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) return
            info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
            buffer.position(0); buffer.limit(size)
            muxer.writeSampleData(outputTrack, buffer, info)
            extractor.advance()
        }
    }

    private fun encodeAudio(pcm: WavIO.Pcm, encoder: MediaCodec, muxer: MediaMuxer, outputTrack: Int) {
        val info = MediaCodec.BufferInfo()
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
                    if (alignedSamples <= 0) {
                        encoder.queueInputBuffer(inputIndex, 0, 0,
                            (samplesWritten / pcm.channels * 1_000_000L) / pcm.sampleRate,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        repeat(alignedSamples) { input.putShort(pcm.samples[samplesWritten + it]) }
                        val pts = (samplesWritten / pcm.channels * 1_000_000L) / pcm.sampleRate
                        encoder.queueInputBuffer(inputIndex, 0, alignedSamples * 2, pts, 0)
                        samplesWritten += alignedSamples
                    }
                }
            }
            val outputIndex = encoder.dequeueOutputBuffer(info, 10_000)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                outputIndex >= 0 -> {
                    val output = encoder.getOutputBuffer(outputIndex)!!
                    if (info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        muxer.writeSampleData(outputTrack, output, info)
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
    }
}
