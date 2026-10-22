package com.onegoodtake.app.audio;

/** Peak level per channel for interleaved signed 16-bit PCM. */
public final class PcmLevelAnalyzer {
    private PcmLevelAnalyzer() { }

    public static int[] peaks(short[] samples, int channels) {
        if (channels < 1 || samples.length % channels != 0)
            throw new IllegalArgumentException("PCM data does not match its channel count");
        int[] peaks = new int[channels];
        for (int i = 0; i < samples.length; i++) {
            int peak = Math.abs((int) samples[i]);
            int channel = i % channels;
            if (peak > peaks[channel]) peaks[channel] = peak;
        }
        return peaks;
    }
}
