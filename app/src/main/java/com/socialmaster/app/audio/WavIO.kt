package com.socialmaster.app.audio

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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

    fun write(file: File, pcm: Pcm) {
        val dataSize = pcm.samples.size * 2
        val byteRate = pcm.sampleRate * pcm.channels * 2
        val out = FileOutputStream(file)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()); header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()); header.putInt(16)
        header.putShort(1); header.putShort(pcm.channels.toShort())
        header.putInt(pcm.sampleRate); header.putInt(byteRate)
        header.putShort((pcm.channels * 2).toShort()); header.putShort(16)
        header.put("data".toByteArray()); header.putInt(dataSize)
        out.write(header.array())
        val buf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm.samples) buf.putShort(s)
        out.write(buf.array())
        out.close()
    }
}
