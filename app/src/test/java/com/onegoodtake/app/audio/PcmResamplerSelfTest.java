package com.onegoodtake.app.audio;

public final class PcmResamplerSelfTest {
    public static void main(String[] args) {
        short[] mono = new short[4410];
        java.util.Arrays.fill(mono, (short) 12000);
        short[] up = PcmResampler.resample(mono, 1, 44100, 48000);
        if (up.length != 4800) throw new AssertionError("duration changed: " + up.length);
        for (int i = 40; i < up.length - 40; i++)
            if (Math.abs(up[i] - 12000) > 4) throw new AssertionError("constant level changed: " + up[i]);

        short[] stereo = { 1000, -2000, 1000, -2000, 1000, -2000, 1000, -2000 };
        short[] sameRate = PcmResampler.resample(stereo, 2, 48000, 48000);
        if (!java.util.Arrays.equals(stereo, sameRate)) throw new AssertionError("same-rate stereo changed");
        System.out.println("PcmResamplerSelfTest passed");
    }
}
