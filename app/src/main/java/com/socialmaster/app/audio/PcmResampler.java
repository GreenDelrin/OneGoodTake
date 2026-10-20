package com.socialmaster.app.audio;

/** Windowed-sinc sample-rate conversion for offline PCM mixing. */
public final class PcmResampler {
    private static final int RADIUS = 16;

    private PcmResampler() { }

    public static short[] resample(short[] input, int channels, int inputRate, int outputRate) {
        if (channels < 1 || inputRate < 1 || outputRate < 1 || input.length % channels != 0)
            throw new IllegalArgumentException("Invalid PCM format");
        if (inputRate == outputRate) return input.clone();
        int inFrames = input.length / channels;
        int outFrames = (int) Math.round((double) inFrames * outputRate / inputRate);
        short[] output = new short[outFrames * channels];
        double cutoff = Math.min(1.0, (double) outputRate / inputRate);
        int phaseCount = outputRate / gcd(inputRate, outputRate);
        double[][] kernels = new double[phaseCount][RADIUS * 2];
        for (int phase = 0; phase < phaseCount; phase++) {
            double fraction = (double) phase / phaseCount;
            for (int tap = 0; tap < RADIUS * 2; tap++) {
                double distance = fraction - (tap - RADIUS + 1);
                double normalized = distance / RADIUS;
                if (Math.abs(normalized) >= 1.0) continue;
                double window = 0.42 + 0.5 * Math.cos(Math.PI * normalized)
                        + 0.08 * Math.cos(2.0 * Math.PI * normalized);
                double x = distance * cutoff;
                double sinc = x == 0.0 ? 1.0 : Math.sin(Math.PI * x) / (Math.PI * x);
                kernels[phase][tap] = cutoff * sinc * window;
            }
        }
        for (int frame = 0; frame < outFrames; frame++) {
            long sourcePosition = (long) frame * inputRate;
            int centerFrame = (int) (sourcePosition / outputRate);
            long remainder = sourcePosition % outputRate;
            int phase = (int) (remainder * phaseCount / outputRate);
            int first = centerFrame - RADIUS + 1;
            for (int channel = 0; channel < channels; channel++) {
                double sum = 0.0;
                double weightSum = 0.0;
                for (int tap = 0; tap < RADIUS * 2; tap++) {
                    int sourceFrame = first + tap;
                    if (sourceFrame < 0 || sourceFrame >= inFrames) continue;
                    double weight = kernels[phase][tap];
                    sum += input[sourceFrame * channels + channel] * weight;
                    weightSum += weight;
                }
                double sample = weightSum == 0.0 ? 0.0 : sum / weightSum;
                output[frame * channels + channel] = (short) Math.max(-32768, Math.min(32767, Math.round(sample)));
            }
        }
        return output;
    }

    private static int gcd(int a, int b) {
        while (b != 0) { int remainder = a % b; a = b; b = remainder; }
        return a;
    }
}
