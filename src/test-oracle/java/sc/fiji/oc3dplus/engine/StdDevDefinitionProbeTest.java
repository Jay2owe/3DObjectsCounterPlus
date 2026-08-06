package sc.fiji.oc3dplus.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;
import sc.fiji.oc3d.core.measure.LabelFeatureAccumulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@code StdDev} is a <b>sample</b> standard deviation in {@code Counter3D} and a
 * <b>population</b> one in the accumulator. Measured, because the difference was
 * first mistaken for floating-point noise.
 *
 * <h2>Why this needed its own probe</h2>
 *
 * TOLERANCES.md §2 declares {@code StdDev} on Case A as {@code float-narrow},
 * justified by the reference field being a {@code float}. That justification is
 * wrong. The first Case A comparison after unification showed
 * {@code 2.631174087524414} against {@code 2.581988897471494} on a 27-voxel
 * object - a 1.9% gap, far beyond any {@code float} rounding step, and
 * {@code 2.631174 / 2.581989 = 1.019049 = sqrt(27/26)} exactly.
 *
 * <p>So the two divide by different things:
 *
 * <pre>
 * Counter3D    sqrt( sum((v - mean)^2) / (n - 1) )    sample
 * accumulator  sqrt( sumSquares/n - mean^2 )          population
 * </pre>
 *
 * <p>The ratio is {@code sqrt(n / (n - 1))}, so the difference is largest exactly
 * where it is least welcome: +41% for a 2-voxel object, +5.4% at 10 voxels, +1.9%
 * at 27, +0.5% at 100, negligible above a few thousand. Small objects are the
 * common case in punctate data.
 *
 * <p>Neither is a bug. A population standard deviation is the defensible choice
 * when every voxel of the object has been enumerated rather than sampled. But it
 * is a different number for every object on the path that must not move, so it
 * needs a sign-off and a release note, not a tolerance.
 */
public class StdDevDefinitionProbeTest {

    /** Object sizes to compare, chosen to span where the ratio matters. */
    private static final int[] CUBE_SIDES = {2, 3, 4, 5, 6, 8};

    @Test
    public void classicIsSampleAndAccumulatorIsPopulation() {
        System.out.println("=== StdDev: Counter3D vs accumulator ===");
        System.out.printf("%8s %10s %14s %14s %10s %10s%n",
                "voxels", "mean", "classic", "accumulator", "ratio", "sqrt(n/n-1)");

        int compared = 0;
        for (int s = 0; s < CUBE_SIDES.length; s++) {
            int side = CUBE_SIDES[s];
            ImagePlus image = cubeWithVaryingIntensity(side);
            ImagePlus labels = null;
            try {
                ObjectsCounter3DWrapper.Result classic = new ReferenceEngines().run(
                        image, 100, 0, Integer.MAX_VALUE, false, false, true, false);
                ResultsTable stats = classic.getStatistics();
                labels = classic.getObjectsMap();
                if (stats.size() != 1) continue;

                LabelFeatureAccumulator.Result measured =
                        LabelFeatureAccumulator.scan(labels, image, image.getCalibration());
                LabelFeatureAccumulator.FeatureValues values = measured.valuesForLabel(1);

                long n = values.voxelCount();
                double classicSd = stats.getValue("StdDev", 0);
                double accumulatorSd = values.intensityStdDev();
                if (accumulatorSd == 0.0) continue;
                double ratio = classicSd / accumulatorSd;
                double predicted = Math.sqrt((double) n / (double) (n - 1));

                System.out.printf("%8d %10.4f %14.9f %14.9f %10.6f %10.6f%n",
                        n, values.intensityMean(), classicSd, accumulatorSd, ratio, predicted);

                compared++;
                // The prediction is exact in real arithmetic; the slack is for the
                // reference being accumulated in float.
                assertEquals("StdDev ratio for a " + n + "-voxel object is not "
                                + "sqrt(n/(n-1)), so the divisor is not the whole story",
                        predicted, ratio, 1.0e-5);
            } finally {
                discard(labels);
                discard(image);
            }
        }
        assertTrue("no objects were compared", compared >= CUBE_SIDES.length - 1);
        System.out.println("  ratio is sqrt(n/(n-1)) throughout: classic divides by n-1,");
        System.out.println("  the accumulator by n. Not a precision difference.");
    }

    /** How wrong the number gets, for the release note. */
    @Test
    public void reportsTheRelativeChangeByObjectSize() {
        System.out.println("=== StdDev relative change, by object size ===");
        int[] sizes = {2, 5, 10, 27, 100, 1000, 10000};
        for (int i = 0; i < sizes.length; i++) {
            int n = sizes[i];
            double ratio = Math.sqrt((double) n / (double) (n - 1));
            System.out.printf("  n=%-6d accumulator is %.3f%% lower than classic%n",
                    n, 100.0 * (1.0 - 1.0 / ratio));
        }
    }

    /**
     * A solid cube whose voxel intensities vary, so the standard deviation is not
     * zero and the divisor is observable.
     */
    private static ImagePlus cubeWithVaryingIntensity(int side) {
        int size = side + 4;
        ImageStack stack = new ImageStack(size, size);
        for (int z = 0; z < size; z++) {
            stack.addSlice("z" + z, new ShortProcessor(size, size));
        }
        ImagePlus image = new ImagePlus("cube-" + side, stack);
        int value = 100;
        for (int z = 2; z < 2 + side; z++) {
            ImageProcessor slice = stack.getProcessor(z + 1);
            for (int y = 2; y < 2 + side; y++) {
                for (int x = 2; x < 2 + side; x++) {
                    slice.set(x, y, 100 + (value++ % 9));
                }
            }
        }
        return image;
    }

    private static void discard(ImagePlus image) {
        if (image == null) return;
        ImageStack stack = image.getStack();
        if (stack != null) {
            for (int i = 1; i <= stack.getSize(); i++) {
                ImageProcessor processor = stack.getProcessor(i);
                if (processor != null) processor.setPixels(null);
            }
        }
        image.flush();
    }
}
