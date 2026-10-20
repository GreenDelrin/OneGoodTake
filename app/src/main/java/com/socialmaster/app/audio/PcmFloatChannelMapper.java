package com.socialmaster.app.audio;

/** Selects the first one or two channels from interleaved USB float PCM. */
public final class PcmFloatChannelMapper {
    private PcmFloatChannelMapper() { }

    public static short[] select(float[] input, int inputChannels, int selection) {
        if (inputChannels < 1 || input.length % inputChannels != 0)
            throw new IllegalArgumentException("PCM samples do not match the channel count");
        int outputChannels = selection == PcmChannelMapper.STEREO ? 2 : 1;
        if (selection != PcmChannelMapper.STEREO && selection != PcmChannelMapper.LEFT && selection != PcmChannelMapper.RIGHT)
            throw new IllegalArgumentException("Unknown channel selection");
        if (inputChannels < outputChannels || (selection == PcmChannelMapper.RIGHT && inputChannels < 2))
            throw new IllegalArgumentException(selection == PcmChannelMapper.STEREO
                    ? "Stereo requires two captured channels" : "Selected input channel is not available");

        int frames = input.length / inputChannels;
        short[] output = new short[frames * outputChannels];
        for (int frame = 0; frame < frames; frame++) {
            for (int channel = 0; channel < outputChannels; channel++) {
                int sourceChannel = selection == PcmChannelMapper.RIGHT ? 1 : channel;
                float sample = input[frame * inputChannels + sourceChannel];
                output[frame * outputChannels + channel] = sample <= -1f ? Short.MIN_VALUE
                        : sample >= 1f ? Short.MAX_VALUE : (short) Math.round(sample * Short.MAX_VALUE);
            }
        }
        return output;
    }
}
