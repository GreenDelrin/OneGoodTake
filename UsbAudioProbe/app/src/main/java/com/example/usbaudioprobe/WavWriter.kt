package com.example.usbaudioprobe

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 32-bit float WAV writer. */
class WavWriter(file: File, private val channels: Int, private val rate: Int) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L

    init { raf.write(header(0)) }

    @Synchronized
    fun write(samples: FloatArray, count: Int) {
        val bb = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(samples, 0, count)
        raf.write(bb.array())
        dataBytes += count * 4L
    }

    @Synchronized
    fun close() {
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.close()
    }

    private fun header(dataLen: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataLen).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(3)            // IEEE float
        b.putShort(channels.toShort()); b.putInt(rate)
        b.putInt(rate * channels * 4); b.putShort((channels * 4).toShort()); b.putShort(32)
        b.put("data".toByteArray()); b.putInt(dataLen.toInt())
        return b.array()
    }
}
