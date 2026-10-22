package com.onegoodtake.app.audio;

public final class PcmLevelAnalyzerSelfTest {
    public static void main(String[] args) {
        short[] frames = { 10, 20, 30, 40, 50, 60, 70, 80, 0, 0, 0, 0, 0, 0, 1500, 0 };
        int[] peaks = PcmLevelAnalyzer.peaks(frames, 8);
        if (peaks.length != 8 || peaks[0] != 10 || peaks[1] != 20 || peaks[6] != 1500 || peaks[7] != 80)
            throw new AssertionError(java.util.Arrays.toString(peaks));
        System.out.println("PcmLevelAnalyzerSelfTest passed");
    }
}
