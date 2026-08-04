package sc.fiji.oc3dplus.engine.extended.arbor;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ByteProcessor;

final class MaskVolume {

    final boolean[] mask;
    final int width;
    final int height;
    final int depth;
    final int voxelCount;
    final double centerX;
    final double centerY;
    final double centerZ;
    final MicronCalibration micronCalibration;
    final Calibration imageCalibration;
    final boolean available;
    final String unavailableReason;

    private MaskVolume(boolean[] mask,
                       int width,
                       int height,
                       int depth,
                       int voxelCount,
                       double centerX,
                       double centerY,
                       double centerZ,
                       MicronCalibration micronCalibration,
                       Calibration imageCalibration,
                       boolean available,
                       String unavailableReason) {
        this.mask = mask;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.voxelCount = voxelCount;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.micronCalibration = micronCalibration;
        this.imageCalibration = imageCalibration;
        this.available = available;
        this.unavailableReason = unavailableReason == null ? "" : unavailableReason;
    }

    static MaskVolume create(boolean[] source,
                             int sourceWidth,
                             int sourceHeight,
                             int sourceDepth,
                             Calibration calibration) {
        int expectedLength = checkedLength(sourceWidth, sourceHeight, sourceDepth);
        if (source == null) {
            throw new IllegalArgumentException("mask must not be null (mask=null).");
        }
        if (source.length != expectedLength) {
            throw new IllegalArgumentException("mask length (" + source.length
                    + ") must equal width*height*depth (" + expectedLength + ").");
        }

        int sourceCount = BinaryMaskOps.countTrue(source);
        if (sourceCount == 0) {
            return unavailable("Object mask contains no foreground voxels.");
        }
        if (BinaryMaskOps.componentCount26(
                source, sourceWidth, sourceHeight, sourceDepth, 2) != 1) {
            return unavailable("Object mask must contain exactly one 26-connected foreground component.");
        }
        if (sourceWidth > Integer.MAX_VALUE - 2
                || sourceHeight > Integer.MAX_VALUE - 2
                || sourceDepth > Integer.MAX_VALUE - 2) {
            throw new IllegalArgumentException("mask dimensions are too large to add a safety border.");
        }

        int width = sourceWidth + 2;
        int height = sourceHeight + 2;
        int depth = sourceDepth + 2;
        boolean[] padded = new boolean[checkedLength(width, height, depth)];
        double sumX = 0.0;
        double sumY = 0.0;
        double sumZ = 0.0;
        for (int z = 0; z < sourceDepth; z++) {
            for (int y = 0; y < sourceHeight; y++) {
                for (int x = 0; x < sourceWidth; x++) {
                    int sourceIndex = BinaryMaskOps.index(x, y, z, sourceWidth, sourceHeight);
                    if (!source[sourceIndex]) {
                        continue;
                    }
                    int px = x + 1;
                    int py = y + 1;
                    int pz = z + 1;
                    padded[BinaryMaskOps.index(px, py, pz, width, height)] = true;
                    sumX += px;
                    sumY += py;
                    sumZ += pz;
                }
            }
        }

        Calibration copy = calibration == null ? null : calibration.copy();
        return new MaskVolume(
                padded,
                width,
                height,
                depth,
                sourceCount,
                sumX / sourceCount,
                sumY / sourceCount,
                sumZ / sourceCount,
                MicronCalibration.from(calibration),
                copy,
                true,
                "");
    }

    ImagePlus toImagePlus() {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (mask[BinaryMaskOps.index(x, y, z, width, height)]) {
                        processor.set(x, y, 255);
                    }
                }
            }
            stack.addSlice(processor);
        }
        ImagePlus image = new ImagePlus("3D Objects Counter+ object skeleton source", stack);
        if (imageCalibration != null) {
            image.setCalibration(imageCalibration.copy());
        }
        return image;
    }

    private static MaskVolume unavailable(String reason) {
        return new MaskVolume(
                new boolean[0], 0, 0, 0, 0,
                Double.NaN, Double.NaN, Double.NaN,
                MicronCalibration.unavailable(), null, false, reason);
    }

    private static int checkedLength(int width, int height, int depth) {
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("width, height, and depth must all be positive "
                    + "(width=" + width + ", height=" + height + ", depth=" + depth + ").");
        }
        long length = (long) width * (long) height * (long) depth;
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("mask is too large (width*height*depth="
                    + length + ").");
        }
        return (int) length;
    }
}
