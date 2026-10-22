package com.onegoodtake.app.audio;

/** Stereo-linked feed-forward compressor for offline track processing. */
public final class PcmCompressor {
    private PcmCompressor() { }

    public static short[] compressStereo(short[] input, int sampleRate, float amount) {
        if (input.length % 2 != 0 || sampleRate <= 0)
            throw new IllegalArgumentException("Expected interleaved stereo PCM");
        if (amount <= 0f) return input.clone();
        float control = Math.min(1f, amount);
        double threshold = Math.pow(10.0, (-12.0 - 12.0 * control) / 20.0) * 32768.0;
        double ratio = 1.0 + 5.0 * control;
        double attack = Math.exp(-1.0 / (sampleRate * 0.005));
        double release = Math.exp(-1.0 / (sampleRate * 0.100));
        double envelope = 0.0;
        short[] output = new short[input.length];
        for (int frame = 0; frame < input.length / 2; frame++) {
            int left = input[frame * 2];
            int right = input[frame * 2 + 1];
            double level = Math.max(Math.abs(left), Math.abs(right));
            double coefficient = level > envelope ? attack : release;
            envelope = coefficient * envelope + (1.0 - coefficient) * level;
            double gain = 1.0;
            if (envelope > threshold) {
                double compressed = threshold + (envelope - threshold) / ratio;
                gain = compressed / envelope;
            }
            output[frame * 2] = (short) Math.max(-32768, Math.min(32767, Math.round(left * gain)));
            output[frame * 2 + 1] = (short) Math.max(-32768, Math.min(32767, Math.round(right * gain)));
        }
        return output;
    }
}
