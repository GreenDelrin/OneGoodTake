package com.onegoodtake.app.audio;

public final class InputCapturePlanSelfTest {
    public static void main(String[] args) {
        InputCapturePlan input1 = InputCapturePlan.create(PcmChannelMapper.LEFT, true, new int[] { 1, 2, 3 }, true);
        equal(1, input1.channelIndexMask);
        equal(1, input1.captureChannels);
        equal(PcmChannelMapper.LEFT, input1.mapperSelection);

        InputCapturePlan input2 = InputCapturePlan.create(PcmChannelMapper.RIGHT, true, new int[] { 1, 2, 3 }, true);
        equal(2, input2.channelIndexMask);
        equal(1, input2.captureChannels);
        equal(PcmChannelMapper.LEFT, input2.mapperSelection);

        InputCapturePlan stereo = InputCapturePlan.create(PcmChannelMapper.STEREO, true, new int[] { 3 }, true);
        equal(3, stereo.channelIndexMask);
        equal(2, stereo.captureChannels);
        equal(PcmChannelMapper.STEREO, stereo.mapperSelection);

        InputCapturePlan fallback = InputCapturePlan.create(PcmChannelMapper.RIGHT, true, new int[0], true);
        equal(0, fallback.channelIndexMask);
        equal(2, fallback.captureChannels);
        equal(PcmChannelMapper.RIGHT, fallback.mapperSelection);

        InputCapturePlan eightChannelInput1 = InputCapturePlan.create(PcmChannelMapper.LEFT, true, new int[] { 0xff }, false);
        equal(0xff, eightChannelInput1.channelIndexMask);
        equal(8, eightChannelInput1.captureChannels);
        equal(PcmChannelMapper.LEFT, eightChannelInput1.mapperSelection);

        InputCapturePlan eightChannelInput2 = InputCapturePlan.create(PcmChannelMapper.RIGHT, true, new int[] { 0xff }, false);
        equal(0xff, eightChannelInput2.channelIndexMask);
        equal(8, eightChannelInput2.captureChannels);
        equal(PcmChannelMapper.RIGHT, eightChannelInput2.mapperSelection);

        InputCapturePlan eightChannelDevice = InputCapturePlan.create(PcmChannelMapper.STEREO, true, new int[] { 0xff }, false);
        equal(0xff, eightChannelDevice.channelIndexMask);
        equal(8, eightChannelDevice.captureChannels);
        equal(PcmChannelMapper.STEREO, eightChannelDevice.mapperSelection);

        InputCapturePlan positionalFallback = InputCapturePlan.create(PcmChannelMapper.LEFT, true, new int[] { 0xff }, true, true);
        equal(0, positionalFallback.channelIndexMask);
        equal(2, positionalFallback.captureChannels);
        equal(PcmChannelMapper.LEFT, positionalFallback.mapperSelection);
        System.out.println("InputCapturePlanSelfTest passed");
    }

    private static void equal(int expected, int actual) {
        if (expected != actual) throw new AssertionError("expected " + expected + " got " + actual);
    }
}
