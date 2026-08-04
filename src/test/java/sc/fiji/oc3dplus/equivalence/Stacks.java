package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

/**
 * Deterministic stack builders for the fixture corpus. Every value here is
 * produced by integer arithmetic so two runs on the same source produce
 * identical inputs.
 */
final class Stacks {

    /** Foreground intensity used by every built fixture unless stated otherwise. */
    static final int FOREGROUND = 200;

    private Stacks() {}

    static ImagePlus bytes(String title, int width, int height, int depth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new ByteProcessor(width, height));
        }
        return new ImagePlus(title, stack);
    }

    static ImagePlus shorts(String title, int width, int height, int depth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new ShortProcessor(width, height));
        }
        return new ImagePlus(title, stack);
    }

    static ImagePlus floats(String title, int width, int height, int depth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new FloatProcessor(width, height));
        }
        return new ImagePlus(title, stack);
    }

    /** Sets one voxel, 0-based in z. */
    static void set(ImagePlus image, int x, int y, int z, double value) {
        ImageProcessor processor = image.getStack().getProcessor(z + 1);
        processor.setf(x, y, (float) value);
    }

    static double get(ImagePlus image, int x, int y, int z) {
        return image.getStack().getProcessor(z + 1).getf(x, y);
    }

    /** Fills a half-open box [x0,x1) x [y0,y1) x [z0,z1). */
    static void box(ImagePlus image,
                    int x0, int x1,
                    int y0, int y1,
                    int z0, int z1,
                    double value) {
        for (int z = z0; z < z1; z++) {
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    set(image, x, y, z, value);
                }
            }
        }
    }

    /** Solid ball of squared-radius {@code radius*radius} about an integer centre. */
    static void ball(ImagePlus image, int cx, int cy, int cz, int radius, double value) {
        shell(image, cx, cy, cz, 0, radius, value);
    }

    /**
     * Spherical shell with inner radius {@code inner} (exclusive by squared
     * distance) and outer radius {@code outer} (inclusive).
     */
    static void shell(ImagePlus image, int cx, int cy, int cz, int inner, int outer, double value) {
        int depth = image.getStack().getSize();
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int dx = x - cx, dy = y - cy, dz = z - cz;
                    int d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 <= outer * outer && d2 > inner * inner) {
                        set(image, x, y, z, value);
                    }
                }
            }
        }
    }

    /**
     * Deterministic 16-bit intensity gradient used as a redirect target. Never
     * zero, so redirected intensity statistics are defined for every voxel.
     */
    static ImagePlus gradient(int width, int height, int depth) {
        ImagePlus image = shorts("gradient", width, height, depth);
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    set(image, x, y, z, 5 + ((x * 7 + y * 13 + z * 29) % 251));
                }
            }
        }
        return image;
    }

    static ImagePlus calibrate(ImagePlus image, String unit, double xy, double z) {
        Calibration calibration = new Calibration();
        calibration.setUnit(unit);
        calibration.pixelWidth = xy;
        calibration.pixelHeight = xy;
        calibration.pixelDepth = z;
        image.setCalibration(calibration);
        return image;
    }

    /**
     * Single voxels on a lattice of stride 2, which keeps them apart under
     * 26-connectivity, stopping once {@code count} have been placed. Used for the
     * processor-boundary object-count ladder.
     */
    static void isolatedVoxels(ImagePlus image, int count, double value) {
        int placed = 0;
        int depth = image.getStack().getSize();
        for (int z = 0; z < depth && placed < count; z += 2) {
            for (int y = 0; y < image.getHeight() && placed < count; y += 2) {
                for (int x = 0; x < image.getWidth() && placed < count; x += 2) {
                    set(image, x, y, z, value);
                    placed++;
                }
            }
        }
        if (placed != count) {
            throw new IllegalStateException("fixture lattice holds " + placed
                    + " voxels but " + count + " were requested");
        }
    }

    static void discard(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }
}
