package sc.fiji.oc3dplus.ui;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

/** Native-style defaults for the interactive 3D Objects Counter+ dialog. */
final class OC3DPlusDialogDefaults {

    private OC3DPlusDialogDefaults() {}

    static int centerSlice(ImagePlus image) {
        int slices = image == null ? 1 : Math.max(1, image.getNSlices());
        return Math.max(1, (slices + 1) / 2);
    }

    static void moveToCenterSlice(ImagePlus image) {
        if (image == null) return;
        int channel = Math.max(1, image.getC());
        int frame = Math.max(1, image.getT());
        image.setPosition(channel, centerSlice(image), frame);
        image.updateAndDraw();
    }

    static int isoDataThresholdAtCenterSlice(ImagePlus image, int fallback) {
        ImageProcessor processor = centerSliceProcessor(image);
        if (processor == null) return fallback;
        try {
            return clampToInt(processor.getAutoThreshold(), fallback);
        } catch (RuntimeException thresholdUnavailable) {
            return fallback;
        }
    }

    static int sliderMinimum(ImagePlus image) {
        double min = finiteMinimum(image, 0.0);
        if (min >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (min <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) Math.floor(min);
    }

    static int sliderMaximum(ImagePlus image, int threshold) {
        double max = finiteMaximum(image, Math.max(1, threshold));
        if (max >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (max <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        int rounded = (int) Math.ceil(max);
        return rounded < threshold ? threshold : rounded;
    }

    static double finiteMaximum(ImagePlus image, double fallback) {
        return finiteRangeValue(image, fallback, false);
    }

    private static double finiteMinimum(ImagePlus image, double fallback) {
        return finiteRangeValue(image, fallback, true);
    }

    private static double finiteRangeValue(ImagePlus image, double fallback, boolean wantMinimum) {
        ImageStack stack = image == null ? null : image.getStack();
        if (stack == null || stack.size() <= 0) return fallback;
        double best = Double.NaN;
        for (int slice = 1; slice <= stack.size(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            if (processor == null) continue;
            for (int i = 0; i < processor.getPixelCount(); i++) {
                float value = processor.getf(i);
                if (!Float.isFinite(value)) continue;
                if (Double.isNaN(best)
                        || (wantMinimum && value < best)
                        || (!wantMinimum && value > best)) {
                    best = value;
                }
            }
        }
        return Double.isNaN(best) ? fallback : best;
    }

    private static ImageProcessor centerSliceProcessor(ImagePlus image) {
        ImageStack stack = image == null ? null : image.getImageStack();
        if (stack == null || stack.size() <= 0) return null;
        int channel = clamp(Math.max(1, image.getC()), 1, Math.max(1, image.getNChannels()));
        int slice = centerSlice(image);
        int frame = clamp(Math.max(1, image.getT()), 1, Math.max(1, image.getNFrames()));
        int index = image.getStackIndex(channel, slice, frame);
        index = clamp(index, 1, stack.size());
        return stack.getProcessor(index);
    }

    private static int clampToInt(int value, int fallback) {
        if (value == Integer.MIN_VALUE || value == Integer.MAX_VALUE) return fallback;
        return value;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        return value > max ? max : value;
    }
}
