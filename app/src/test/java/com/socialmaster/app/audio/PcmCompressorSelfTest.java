package com.socialmaster.app.audio;

public final class PcmCompressorSelfTest {
    public static void main(String[] args) {
        short[] input = new short[48000 * 2];
        for (int i = 0; i < input.length / 2; i++) {
            input[i * 2] = 24000;
            input[i * 2 + 1] = 12000;
        }
        short[] compressed = PcmCompressor.compressStereo(input, 48000, 1f);
        int beforePeak = peak(input);
        int afterPeak = peak(java.util.Arrays.copyOfRange(compressed, 1000, compressed.length));
        if (afterPeak >= beforePeak / 2) throw new AssertionError("sustained high level was not compressed: " + afterPeak);
        for (int i = 1000; i < compressed.length; i += 2) {
            if (Math.abs(compressed[i] - 2 * compressed[i + 1]) > 2)
                throw new AssertionError("stereo link changed channel balance");
        }
        System.out.println("PcmCompressorSelfTest passed");
    }

    private static int peak(short[] samples) {
        int peak = 0;
        for (short sample : samples) peak = Math.max(peak, Math.abs((int) sample));
        return peak;
    }
}
