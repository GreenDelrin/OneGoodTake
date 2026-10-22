package com.onegoodtake.app.audio;

import java.util.Arrays;

/** Maps the requested analog input mode to USB channel-index masks when advertised. */
public final class InputCapturePlan {
    public final boolean usesChannelIndexMask;
    public final int channelIndexMask;
    public final int captureChannels;
    public final int mapperSelection;

    private InputCapturePlan(boolean usesIndex, int indexMask, int channels, int mapperSelection) {
        this.usesChannelIndexMask = usesIndex;
        this.channelIndexMask = indexMask;
        this.captureChannels = channels;
        this.mapperSelection = mapperSelection;
    }

    public static InputCapturePlan create(int requestedSelection, boolean usbInput, int[] supportedIndexMasks, boolean supportsStereo) {
        int requestedMask = requestedSelection == PcmChannelMapper.LEFT ? 1
                : requestedSelection == PcmChannelMapper.RIGHT ? 2
                : requestedSelection == PcmChannelMapper.STEREO ? 3 : 0;
        if (requestedMask == 0) throw new IllegalArgumentException("Unknown input channel selection");
        int availableIndexMask = usbInput && supportedIndexMasks != null
                ? Arrays.stream(supportedIndexMasks)
                    .filter(mask -> (mask & requestedMask) == requestedMask)
                    .boxed().min((a, b) -> Integer.compare(Integer.bitCount(a), Integer.bitCount(b)))
                    .orElse(0)
                : 0;
        if (availableIndexMask != 0) {
            int channels = Integer.bitCount(availableIndexMask);
            int mapperSelection = channels == 1 ? PcmChannelMapper.LEFT : requestedSelection;
            return new InputCapturePlan(true, availableIndexMask, channels, mapperSelection);
        }
        if (supportsStereo) return new InputCapturePlan(false, 0, 2, requestedSelection);
        if (requestedSelection == PcmChannelMapper.LEFT) return new InputCapturePlan(false, 0, 1, PcmChannelMapper.LEFT);
        throw new IllegalArgumentException("The input does not expose two channels");
    }

    public static InputCapturePlan create(int requestedSelection, boolean usbInput, int[] supportedIndexMasks,
                                          boolean supportsStereo, boolean forcePositionalFallback) {
        if (!forcePositionalFallback) return create(requestedSelection, usbInput, supportedIndexMasks, supportsStereo);
        if (requestedSelection != PcmChannelMapper.LEFT && requestedSelection != PcmChannelMapper.RIGHT
                && requestedSelection != PcmChannelMapper.STEREO)
            throw new IllegalArgumentException("Unknown input channel selection");
        if (!supportsStereo) throw new IllegalArgumentException("The input does not expose two channels");
        return new InputCapturePlan(false, 0, 2, requestedSelection);
    }
}
