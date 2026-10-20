package com.socialmaster.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.RandomAccessFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioDecoder {
    /** Copies PCM WAV directly; decodes compressed audio through Android MediaCodec. */
    fun decodeToWav(context: Context, uri: Uri, out: File, onProgress: (Float) -> Unit = {}): Boolean {
        val temp = File(out.parentFile, "decode_${System.currentTimeMillis()}.tmp")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return false
            input.use { stream ->
                val probe = ByteArray(12)
                var read = 0
                while (read < probe.size) {
                    val count = stream.read(probe, read, probe.size - read)
                    if (count < 0) break
                    read += count
                }
                val isWav = read == 12 && String(probe, 0, 4, Charsets.US_ASCII) == "RIFF" && String(probe, 8, 4, Charsets.US_ASCII) == "WAVE"
                if (isWav) {
                    val total = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
                    } ?: -1L
                    temp.outputStream().use { output ->
                        output.write(probe)
                        var copied = probe.size.toLong()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 0.99f))
                        }
                    }
                    if (isPcm16Wav(temp)) {
                        out.parentFile?.mkdirs()
                        temp.copyTo(out, overwrite = true)
                        onProgress(1f)
                        return true
                    }
                }
            }
        } catch (_: Exception) {
            // Not a PCM16 WAV; let MediaExtractor try supported compressed formats.
        } finally {
            temp.delete()
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(trackIndex)
            val sourceFormat = extractor.getTrackFormat(trackIndex)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: return false
            val durationUs = if (sourceFormat.containsKey(MediaFormat.KEY_DURATION)) sourceFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(sourceFormat, null, null, 0)
            codec.start()

            val raw = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sampleRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormatEncoding.PCM_16
            var lastProgress = 0f
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)!!
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val time = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, size, time, 0)
                            extractor.advance()
                            if (durationUs > 0) {
                                val progress = (time.toFloat() / durationUs).coerceIn(0f, 0.99f)
                                if (progress - lastProgress >= 0.03f) { lastProgress = progress; onProgress(progress) }
                            }
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else MediaCodecAudioEncoding.PCM_16BIT
                        encoding = when (pcmEncoding) {
                            MediaCodecAudioEncoding.PCM_FLOAT -> AudioFormatEncoding.PCM_FLOAT
                            MediaCodecAudioEncoding.PCM_8BIT -> AudioFormatEncoding.PCM_8
                            else -> AudioFormatEncoding.PCM_16
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)!!
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        buffer.get(chunk)
                        raw.write(chunk)
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            val samples = when (encoding) {
                AudioFormatEncoding.PCM_16 -> bytesToShorts(raw.toByteArray())
                AudioFormatEncoding.PCM_FLOAT -> floatsToShorts(raw.toByteArray())
                AudioFormatEncoding.PCM_8 -> bytes8ToShorts(raw.toByteArray())
            }
            if (channels !in 1..2 || sampleRate < 8_000 || samples.size % channels != 0) return false
            WavIO.write(out, WavIO.Pcm(sampleRate, channels, samples))
            onProgress(1f)
            true
        } catch (e: Exception) {
            Log.e("SocialMaster", "Backing track decode failed", e)
            false
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { extractor.release() } catch (_: Exception) { }
        }
    }

    private enum class AudioFormatEncoding { PCM_8, PCM_16, PCM_FLOAT }
    private object MediaCodecAudioEncoding {
        const val PCM_8BIT = 3
        const val PCM_16BIT = 2
        const val PCM_FLOAT = 4
    }

    private fun isPcm16Wav(file: File): Boolean = try {
        RandomAccessFile(file, "r").use { wav ->
            if (wav.length() < 44) return false
            val header = ByteArray(12); wav.readFully(header)
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" || String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return false
            var formatCode = 0
            var bits = 0
            var foundData = false
            while (wav.filePointer + 8 <= wav.length()) {
                val id = ByteArray(4); wav.readFully(id)
                val size = Integer.reverseBytes(wav.readInt()).toLong() and 0xffffffffL
                when (String(id, Charsets.US_ASCII)) {
                    "fmt " -> {
                        if (size < 16 || size > wav.length() - wav.filePointer) return false
                        formatCode = java.lang.Short.reverseBytes(wav.readShort()).toInt() and 0xffff
                        wav.skipBytes(12)
                        bits = java.lang.Short.reverseBytes(wav.readShort()).toInt() and 0xffff
                        wav.seek(wav.filePointer + size - 16)
                    }
                    "data" -> { foundData = size <= wav.length() - wav.filePointer; break }
                    else -> wav.seek(wav.filePointer + size + (size and 1L))
                }
            }
            formatCode == 1 && bits == 16 && foundData
        }
    } catch (_: Exception) { false }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.size / 2) { buffer.short }
    }

    private fun floatsToShorts(bytes: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.size / 4) {
            (buffer.float.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
    }

    private fun bytes8ToShorts(bytes: ByteArray): ShortArray = ShortArray(bytes.size) {
        (((bytes[it].toInt() and 0xff) - 128) shl 8).toShort()
    }
}
