package com.socialmaster.app.audio

object NativeUsb {
    init { System.loadLibrary("usbaudio") }
    external fun captureInterface(descriptors: ByteArray): Int
    external fun start(fd: Int, descriptors: ByteArray, sampleRate: Int): String
    external fun stop()
    external fun channels(): Int
    external fun read(buf: FloatArray, maxFrames: Int): Int   // interleaved, returns frames
    external fun peaks(out: FloatArray)                       // peak per channel since last call
    external fun stats(): LongArray                           // frames, overruns, usbErrors
}
