package com.onegoodtake.app.audio;

public final class PcmFloatChannelMapperSelfTest {
    public static void main(String[] args) {
        short[] stereo = PcmFloatChannelMapper.select(
                new float[] { -1f, 1f, 0.5f, -0.5f, 0.25f, 0.75f },
                3,
                PcmChannelMapper.STEREO);
        equal(new short[] { Short.MIN_VALUE, Short.MAX_VALUE, -16383, 8192 }, stereo);

        short[] secondInput = PcmFloatChannelMapper.select(
                new float[] { 0.25f, -0.75f, 1f, 0f },
                2,
                PcmChannelMapper.RIGHT);
        equal(new short[] { -24575, 0 }, secondInput);

        fails(() -> PcmFloatChannelMapper.select(new float[] { 0f }, 1, PcmChannelMapper.RIGHT));
        fails(() -> PcmFloatChannelMapper.select(new float[] { 0f, 0f }, 0, PcmChannelMapper.LEFT));
        fails(() -> PcmFloatChannelMapper.select(new float[] { 0f, 0f, 0f }, 2, PcmChannelMapper.LEFT));
        System.out.println("PcmFloatChannelMapperSelfTest passed");
    }

    private static void equal(short[] expected, short[] actual) {
        if (expected.length != actual.length) throw new AssertionError("different sample counts");
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) throw new AssertionError("sample " + i + ": expected " + expected[i] + " got " + actual[i]);
        }
    }

    private static void fails(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("expected IllegalArgumentException");
    }
}
