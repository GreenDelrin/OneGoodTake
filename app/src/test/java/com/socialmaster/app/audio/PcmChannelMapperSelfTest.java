package com.socialmaster.app.audio;

public final class PcmChannelMapperSelfTest {
    public static void main(String[] args) {
        short[] interleaved = { 11, 21, 12, 22 };
        equal(new short[] { 11, 12 }, PcmChannelMapper.select(interleaved, 2, PcmChannelMapper.LEFT));
        equal(new short[] { 21, 22 }, PcmChannelMapper.select(interleaved, 2, PcmChannelMapper.RIGHT));
        equal(interleaved, PcmChannelMapper.select(interleaved, 2, PcmChannelMapper.STEREO));
        short[] eightChannelFrame = { 1, 2, 3, 4, 5, 6, 7, 8 };
        equal(new short[] { 1 }, PcmChannelMapper.select(eightChannelFrame, 8, PcmChannelMapper.LEFT));
        equal(new short[] { 2 }, PcmChannelMapper.select(eightChannelFrame, 8, PcmChannelMapper.RIGHT));
        equal(new short[] { 1, 2 }, PcmChannelMapper.select(eightChannelFrame, 8, PcmChannelMapper.STEREO));
        try {
            PcmChannelMapper.select(new short[] { 11, 12 }, 1, PcmChannelMapper.STEREO);
            throw new AssertionError("mono source must not be labeled stereo");
        } catch (IllegalArgumentException expected) { }
        try {
            PcmChannelMapper.select(new short[] { 11, 12 }, 1, PcmChannelMapper.RIGHT);
            throw new AssertionError("mono source must not claim a right input");
        } catch (IllegalArgumentException expected) { }
        System.out.println("PcmChannelMapperSelfTest passed");
    }

    private static void equal(short[] expected, short[] actual) {
        if (!java.util.Arrays.equals(expected, actual))
            throw new AssertionError(java.util.Arrays.toString(actual));
    }
}
