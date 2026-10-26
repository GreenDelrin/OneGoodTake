package com.onegoodtake.app.audio;

import java.io.File;

public final class AudioPipelineSelfTest {
    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "OneGoodTake-pcm-test");
        if (!dir.exists() && !dir.mkdirs()) throw new AssertionError("could not create temp directory");
        File backingFile = new File(dir, "backing.wav");
        File inputFile = new File(dir, "input.wav");
        File mixFile = new File(dir, "mix.wav");

        short[] mono = new short[44100];
        short[] stereo = new short[96000];
        for (int i = 0; i < mono.length; i++) mono[i] = (short) (Math.sin(i * 0.03) * 6000);
        for (int i = 0; i < stereo.length / 2; i++) {
            stereo[i * 2] = (short) (Math.sin(i * 0.03) * 5000);
            stereo[i * 2 + 1] = (short) (Math.sin(i * 0.031) * 4000);
        }
        WavIO.INSTANCE.write(backingFile, new WavIO.Pcm(44100, 1, mono));
        WavIO.INSTANCE.write(inputFile, new WavIO.Pcm(48000, 2, stereo));
        WavIO.Pcm decoded = WavIO.INSTANCE.read(inputFile);
        if (decoded == null || decoded.getChannels() != 2 || decoded.getSampleRate() != 48000 || decoded.getSamples().length != stereo.length)
            throw new AssertionError("stereo WAV round-trip failed");

        Track backing = new Track("backing", "Backing", "file://" + backingFile.getAbsolutePath(), 250L, -1, 0f, 0f, 0f, 0f, 0f, 0f, 0f, false, false);
        Track input = new Track("input", "Input", "file://" + inputFile.getAbsolutePath(), 0L, -1, 0f, 0f, 0f, 0f, 0f, 0f, 0.5f, false, false);
        float lufs = Mixer.INSTANCE.mix(java.util.Arrays.asList(backing, input), mixFile);
        WavIO.Pcm mixed = WavIO.INSTANCE.read(mixFile);
        if (mixed == null || mixed.getChannels() != 2 || mixed.getSampleRate() != 48000 || mixed.getSamples().length != 96000)
            throw new AssertionError("stereo mixer output format/duration is wrong");
        if (!Float.isFinite(lufs) || lufs <= -70f) throw new AssertionError("mix loudness measurement failed: " + lufs);
        System.out.println("AudioPipelineSelfTest passed");
    }
}
