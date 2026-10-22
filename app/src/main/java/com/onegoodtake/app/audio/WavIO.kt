package com.onegoodtake.app.audio

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavIO {
    data class Pcm(val sampleRate: Int, val channels: Int, val samples: ShortArray)

    fun read(file: File): Pcm? {
        return try {
            val bytes = file.readBytes()
            // parse RIFF header
            var i = 0
            fun str(off: Int, len: Int) = String(bytes, off, len)
            if (str(0, 4) != "RIFF" || str(8, 4) != "WAVE") return null
            i = 12
            var sampleRate = 44100; var channels = 1; var bitsPerSample = 16; var formatCode = 0
            var dataStart = 0; var dataSize = 0
            while (i + 8 <= bytes.size) {
                val chunkId = str(i, 4); val chunkSize = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (chunkId == "fmt ") {
                    formatCode = ByteBuffer.wrap(bytes, i + 8, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                    channels = ByteBuffer.wrap(bytes, i + 10, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                    sampleRate = ByteBuffer.wrap(bytes, i + 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    bitsPerSample = ByteBuffer.wrap(bytes, i + 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                } else if (chunkId == "data") {
                    dataStart = i + 8; dataSize = chunkSize; break
                }
                i += 8 + chunkSize
                if (chunkSize % 2 == 1) i++
            }
            if (formatCode != 1 || bitsPerSample != 16 || channels !in 1..2 || sampleRate <= 0 || dataStart == 0 || dataSize < 0 || dataStart + dataSize > bytes.size) return null
            val numSamples = dataSize / 2
            val samples = ShortArray(numSamples)
            val buf = ByteBuffer.wrap(bytes, dataStart, dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (k in 0 until numSamples) samples[k] = buf.short
            Pcm(sampleRate, channels, samples)
        } catch (e: Exception) { null }
    }

    /** Read a compact peak overview without loading the entire recording into memory. */
    fun peaks(file: File, count: Int): List<Float>? {
        if (count <= 0) return null
        return try {
            RandomAccessFile(file, "r").use { wav ->
                if (wav.length() < 12) return null
                val header = ByteArray(12); wav.readFully(header)
                if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" || String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return null
                var format = 0; var channels = 0; var bits = 0; var dataOffset = -1L; var dataBytes = 0L
                while (wav.filePointer + 8 <= wav.length()) {
                    val id = ByteArray(4); wav.readFully(id)
                    val size = readUnsignedIntLE(wav)
                    val chunkStart = wav.filePointer
                    if (size > wav.length() - chunkStart) return null
                    when (String(id, Charsets.US_ASCII)) {
                        "fmt " -> {
                            if (size < 16) return null
                            format = readUnsignedShortLE(wav)
                            channels = readUnsignedShortLE(wav)
                            readUnsignedIntLE(wav)
                            wav.seek(chunkStart + 12)
                            readUnsignedShortLE(wav)
                            bits = readUnsignedShortLE(wav)
                        }
                        "data" -> { dataOffset = chunkStart; dataBytes = size }
                    }
                    wav.seek(chunkStart + size + (size and 1L))
                    if (dataOffset >= 0 && format != 0) break
                }
                val frameBytes = channels * 2
                if (format != 1 || channels !in 1..2 || bits != 16 || dataOffset < 0 || dataBytes % frameBytes != 0L) return null
                val frames = dataBytes / frameBytes
                val out = FloatArray(count)
                wav.seek(dataOffset)
                val chunk = ByteArray(64 * 1024)
                var remaining = dataBytes
                var frame = 0L
                while (remaining > 0L) {
                    val requested = minOf(chunk.size.toLong(), remaining).toInt()
                    val aligned = requested - requested % frameBytes
                    if (aligned <= 0) break
                    wav.readFully(chunk, 0, aligned)
                    for (byteOffset in 0 until aligned step frameBytes) {
                        val left = ((chunk[byteOffset].toInt() and 0xff) or (chunk[byteOffset + 1].toInt() shl 8)).toShort().toInt()
                        val right = if (channels == 1) left else
                            ((chunk[byteOffset + 2].toInt() and 0xff) or (chunk[byteOffset + 3].toInt() shl 8)).toShort().toInt()
                        val bucket = (frame * count / frames).toInt().coerceAtMost(count - 1)
                        val peak = maxOf(kotlin.math.abs(left), kotlin.math.abs(right)) / 32768f
                        out[bucket] = maxOf(out[bucket], peak)
                        frame++
                    }
                    remaining -= aligned
                }
                out.toList()
            }
        } catch (_: Exception) { null }
    }

    fun durationMs(file: File): Long? = try {
        RandomAccessFile(file, "r").use { wav ->
            if (wav.length() < 12) return null
            val header = ByteArray(12); wav.readFully(header)
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" || String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            var format = 0; var channels = 0; var rate = 0; var bits = 0; var dataBytes = -1L
            while (wav.filePointer + 8 <= wav.length()) {
                val id = ByteArray(4); wav.readFully(id)
                val size = readUnsignedIntLE(wav)
                val chunkStart = wav.filePointer
                if (size > wav.length() - chunkStart) return null
                when (String(id, Charsets.US_ASCII)) {
                    "fmt " -> {
                        if (size < 16) return null
                        format = readUnsignedShortLE(wav)
                        channels = readUnsignedShortLE(wav)
                        rate = readUnsignedIntLE(wav).toInt()
                        wav.seek(chunkStart + 12)
                        readUnsignedShortLE(wav)
                        bits = readUnsignedShortLE(wav)
                    }
                    "data" -> dataBytes = size
                }
                wav.seek(chunkStart + size + (size and 1L))
                if (dataBytes >= 0 && format != 0) break
            }
            val frameBytes = channels * 2
            if (format != 1 || channels !in 1..2 || bits != 16 || rate <= 0 || dataBytes < 0 || dataBytes % frameBytes != 0L) return null
            dataBytes / frameBytes * 1000L / rate
        }
    } catch (_: Exception) { null }

    fun write(file: File, pcm: Pcm) {
        StreamingWriter(file, pcm.sampleRate, pcm.channels).use { it.write(pcm.samples) }
    }

    class StreamingWriter(file: File, private val sampleRate: Int, private val channels: Int) : java.io.Closeable {
        private val output = RandomAccessFile(file, "rw")
        private val buffer = ByteArray(32 * 1024)
        private var dataBytes = 0L
        private var closed = false

        init {
            require(sampleRate > 0 && channels in 1..2)
            output.setLength(0)
            output.write("RIFF".toByteArray(Charsets.US_ASCII)); writeIntLE(output, 0)
            output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII)); writeIntLE(output, 16)
            writeShortLE(output, 1); writeShortLE(output, channels)
            writeIntLE(output, sampleRate); writeIntLE(output, sampleRate * channels * 2)
            writeShortLE(output, channels * 2); writeShortLE(output, 16)
            output.write("data".toByteArray(Charsets.US_ASCII)); writeIntLE(output, 0)
        }

        fun write(samples: ShortArray, offset: Int = 0, count: Int = samples.size - offset) {
            require(!closed && offset >= 0 && count >= 0 && offset + count <= samples.size && count % channels == 0)
            require(dataBytes + count.toLong() * 2 <= 0x7fffffffL - 36) { "WAV exceeds the RIFF size limit" }
            var index = offset
            val end = offset + count
            while (index < end) {
                val sampleCount = minOf(end - index, buffer.size / 2)
                for (i in 0 until sampleCount) {
                    val sample = samples[index + i].toInt()
                    buffer[i * 2] = sample.toByte()
                    buffer[i * 2 + 1] = (sample shr 8).toByte()
                }
                output.write(buffer, 0, sampleCount * 2)
                dataBytes += sampleCount.toLong() * 2
                index += sampleCount
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            output.seek(4); writeIntLE(output, (36 + dataBytes).toInt())
            output.seek(40); writeIntLE(output, dataBytes.toInt())
            output.close()
        }
    }

    private fun writeIntLE(file: RandomAccessFile, value: Int) {
        file.write(value and 0xff); file.write(value ushr 8 and 0xff)
        file.write(value ushr 16 and 0xff); file.write(value ushr 24 and 0xff)
    }

    private fun writeShortLE(file: RandomAccessFile, value: Int) {
        file.write(value and 0xff); file.write(value ushr 8 and 0xff)
    }

    private fun readUnsignedShortLE(file: RandomAccessFile) = file.readUnsignedByte() or (file.readUnsignedByte() shl 8)
    private fun readUnsignedIntLE(file: RandomAccessFile) =
        file.readUnsignedByte().toLong() or (file.readUnsignedByte().toLong() shl 8) or
            (file.readUnsignedByte().toLong() shl 16) or (file.readUnsignedByte().toLong() shl 24)
}
