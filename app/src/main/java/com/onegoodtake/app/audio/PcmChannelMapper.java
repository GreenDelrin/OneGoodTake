package com.onegoodtake.app.audio;

import java.util.Arrays;

/** Selects interface channels from interleaved PCM input. */
public final class PcmChannelMapper {
    public static final int STEREO = 0;
    public static final int LEFT = 1;
    public static final int RIGHT = 2;

    private PcmChannelMapper() { }

    public static short[] select(short[] input, int inputChannels, int selection) {
        if (inputChannels < 1 || input.length % inputChannels != 0)
            throw new IllegalArgumentException("PCM samples do not match the channel count");
        int frames = input.length / inputChannels;
        if (selection == STEREO) {
            if (inputChannels < 2)
                throw new IllegalArgumentException("Stereo requires two captured channels");
            if (inputChannels == 2) return Arrays.copyOf(input, input.length);
            short[] stereo = new short[frames * 2];
            for (int i = 0; i < frames; i++) {
                stereo[i * 2] = input[i * inputChannels];
                stereo[i * 2 + 1] = input[i * inputChannels + 1];
            }
            return stereo;
        }
        if (selection != LEFT && selection != RIGHT)
            throw new IllegalArgumentException("Unknown channel selection");
        int channel = selection == LEFT ? 0 : 1;
        if (channel >= inputChannels)
            throw new IllegalArgumentException("Selected input channel is not available");
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++) mono[i] = input[i * inputChannels + channel];
        return mono;
    }
}
