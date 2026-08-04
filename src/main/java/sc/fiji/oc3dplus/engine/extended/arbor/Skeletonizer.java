package sc.fiji.oc3dplus.engine.extended.arbor;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.lang.reflect.Method;

final class Skeletonizer {

    private static final String FIJI_BACKEND = "Fiji Skeletonize3D";

    private Skeletonizer() {
    }

    static Result skeletonize(MaskVolume volume) {
        boolean[] fiji = tryFijiSkeletonize3D(volume);
        if (isUsable(fiji, volume)) {
            return Result.available(fiji, FIJI_BACKEND);
        }
        return Result.unavailable(
                "Fiji Skeletonize3D was not available or did not produce a usable skeleton. "
                        + "The internal thinning implementation is disabled until its numerical "
                        + "parity with Fiji has been certified.");
    }

    private static boolean[] tryFijiSkeletonize3D(MaskVolume volume) {
        ImagePlus image = null;
        try {
            Class<?> pluginClass = Class.forName("sc.fiji.skeletonize3D.Skeletonize3D_");
            Object plugin = pluginClass.getDeclaredConstructor().newInstance();
            image = volume.toImagePlus();
            Method setup = pluginClass.getMethod("setup", String.class, ImagePlus.class);
            setup.invoke(plugin, "", image);
            Method run = pluginClass.getMethod("run", ImageProcessor.class);
            run.invoke(plugin, image.getProcessor());
            return readMask(image);
        } catch (Throwable unavailableOrFailed) {
            return null;
        } finally {
            if (image != null) {
                image.changes = false;
                image.close();
                image.flush();
            }
        }
    }

    private static boolean isUsable(boolean[] skeleton, MaskVolume volume) {
        return skeleton != null
                && skeleton.length == volume.mask.length
                && BinaryMaskOps.countTrue(skeleton) > 0
                && BinaryMaskOps.isSubset(skeleton, volume.mask)
                && BinaryMaskOps.componentCount26(
                skeleton, volume.width, volume.height, volume.depth, 2) == 1;
    }

    private static boolean[] readMask(ImagePlus image) {
        ImageStack stack = image.getStack();
        int width = image.getWidth();
        int height = image.getHeight();
        int depth = stack.getSize();
        boolean[] mask = new boolean[width * height * depth];
        for (int z = 0; z < depth; z++) {
            ImageProcessor processor = stack.getProcessor(z + 1);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    mask[BinaryMaskOps.index(x, y, z, width, height)] =
                            processor.getf(x, y) > 0.0f;
                }
            }
        }
        return mask;
    }

    static final class Result {
        final boolean[] skeleton;
        final String backend;
        final boolean available;
        final String unavailableReason;

        private Result(boolean[] skeleton,
                       String backend,
                       boolean available,
                       String unavailableReason) {
            this.skeleton = skeleton;
            this.backend = backend;
            this.available = available;
            this.unavailableReason = unavailableReason;
        }

        static Result available(boolean[] skeleton, String backend) {
            return new Result(skeleton, backend, true, "");
        }

        static Result unavailable(String reason) {
            return new Result(new boolean[0], "Unavailable", false, reason);
        }
    }
}
