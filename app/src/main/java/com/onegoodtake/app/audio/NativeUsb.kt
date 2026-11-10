package com.onegoodtake.app.audio

object NativeUsb {
    init { System.loadLibrary("usbaudio") }
    external fun captureInterface(descriptors: ByteArray): Int
    /** [sampleRate] is a preference; the device's real rate is negotiated. [minChannels] steers the alt setting. */
    external fun start(fd: Int, descriptors: ByteArray, sampleRate: Int, minChannels: Int): String
    external fun stop()
    external fun channels(): Int
    external fun sampleRate(): Int                            // the rate the device actually runs at
    external fun read(buf: FloatArray, maxFrames: Int): Int   // interleaved, returns frames
    external fun peaks(out: FloatArray)                       // peak per channel since last call
    external fun stats(): LongArray                           // frames, overruns, usbErrors
}
