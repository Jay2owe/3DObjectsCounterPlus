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
 * Does the accumulator's new {@code Median} reproduce {@code Counter3D}'s?
 *
 * <p>{@code Median} is the one column the unified detection path would otherwise
 * have dropped: neither accumulator computed a median before, and the mcib3d path
 * never emitted the column at all, so the classic path was its only source. The
 * decision was to keep it rather than take the loss, which means the replacement
 * has to be shown equal to the original rather than merely plausible.
 *
 * <p>The claim under test is stronger than the ratified {@code float-narrow} rule
 * that covers the other Case A float columns. {@code Counter3D} stores intensities
 * in a {@code float[]} taken from {@code ImageProcessor}, and the accumulator reads
 * the same {@code getf} values; selecting and averaging them in {@code float}
 * therefore reproduces the shipped number <b>bit for bit</b>, so these assertions
 * use a zero delta. If that ever stops holding, the honest response is to relax
 * the claim in TOLERANCES.md — not the delta here.
 */
public class MedianEquivalenceTest {

    private static final int THRESHOLD = 100;

    /** A stack with three separated objects whose intensities vary within each. */
    private static ImagePlus threeObjectsWithVaryingIntensity() {
        int width = 20;
        int height = 20;
        int depth = 10;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice("z" + z, new ShortProcessor(width, height));
        }
        ImagePlus image = new ImagePlus("three-objects", stack);

        // Object A: 3x3x3, odd voxel count (27), intensities 101..127.
        fill(image, 2, 5, 2, 5, 2, 5, 101);
        // Object B: 4x2x2, even voxel count (16), intensities 201..216.
        fill(image, 10, 14, 3, 5, 3, 5, 201);
        // Object C: single voxel, count 1.
        setValue(image, 16, 16, 7, 150);
        return image;
    }

    private static void fill(ImagePlus image,
                             int x0, int x1, int y0, int y1, int z0, int z1,
                             int startValue) {
        int value = startValue;
        for (int z = z0; z < z1; z++) {
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    setValue(image, x, y, z, value++);
                }
            }
        }
    }

    private static void setValue(ImagePlus image, int x, int y, int z, int value) {
        image.getStack().getProcessor(z + 1).set(x, y, value);
    }

    @Test
    public void accumulatorMedianMatchesCounter3DExactly() {
        ImagePlus image = threeObjectsWithVaryingIntensity();
        ImagePlus labels = null;
        try {
            ObjectsCounter3DWrapper.Result classic = new ReferenceEngines().run(
                    image, THRESHOLD, 0, Integer.MAX_VALUE, false, false, true, false);
            ResultsTable classicStats = classic.getStatistics();
            labels = classic.getObjectsMap();

            assertTrue("classic path emitted no Median column; the fixture or the "
                            + "shipped jar changed shape",
                    classicStats.getColumnIndex("Median") >= 0);
            assertTrue("expected three objects, found " + classicStats.size(),
                    classicStats.size() == 3);

            LabelFeatureAccumulator.Result measured =
                    LabelFeatureAccumulator.scan(labels, image, image.getCalibration());

            System.out.println("=== Median: Counter3D vs accumulator ===");
            for (int row = 0; row < classicStats.size(); row++) {
                int label = (int) Math.round(classicStats.getValue("Label", row));
                double expected = classicStats.getValue("Median", row);
                double actual = measured.valuesForLabel(label).median();
                long voxels = measured.valuesForLabel(label).voxelCount();
                System.out.println("  label " + label
                        + "  voxels=" + voxels
                        + "  classic=" + expected
                        + "  accumulator=" + actual
                        + (expected == actual ? "  EXACT" : "  DIFFERENT"));
                assertEquals("Median differs for label " + label
                        + " (" + voxels + " voxels)", expected, actual, 0.0);
            }
        } finally {
            Stacks.discard(labels);
            Stacks.discard(image);
        }
    }

    /**
     * The even-voxel-count case specifically, because that is where a plausible
     * alternative implementation diverges. {@code Counter3D} averages the two
     * middle values; taking the lower one instead would agree on every
     * odd-sized object and disagree on roughly half of all real objects.
     */
    @Test
    public void evenSizedObjectsAgreeToo() {
        ImagePlus image = threeObjectsWithVaryingIntensity();
        ImagePlus labels = null;
        try {
            ObjectsCounter3DWrapper.Result classic = new ReferenceEngines().run(
                    image, THRESHOLD, 0, Integer.MAX_VALUE, false, false, true, false);
            labels = classic.getObjectsMap();
            LabelFeatureAccumulator.Result measured =
                    LabelFeatureAccumulator.scan(labels, image, image.getCalibration());

            int evenSized = 0;
            for (int row = 0; row < classic.getStatistics().size(); row++) {
                int label = (int) Math.round(classic.getStatistics().getValue("Label", row));
                long voxels = measured.valuesForLabel(label).voxelCount();
                if ((voxels & 1L) != 0L) continue;
                evenSized++;
                assertEquals("even-sized object " + label + " (" + voxels + " voxels)",
                        classic.getStatistics().getValue("Median", row),
                        measured.valuesForLabel(label).median(),
                        0.0);
            }
            assertTrue("the fixture contained no even-sized object, so this test "
                    + "asserted nothing", evenSized > 0);
        } finally {
            Stacks.discard(labels);
            Stacks.discard(image);
        }
    }

    /**
     * Agreement across many shapes and thresholds rather than one fixture, since a
     * median is sensitive to exactly which voxels an object contains.
     */
    @Test
    public void agreementAcrossThresholdsAndShapes() {
        int[] thresholds = {1, 50, 100, 150, 205};
        int mismatches = 0;
        int compared = 0;
        for (int t = 0; t < thresholds.length; t++) {
            ImagePlus image = threeObjectsWithVaryingIntensity();
            ImagePlus labels = null;
            try {
                ObjectsCounter3DWrapper.Result classic = new ReferenceEngines().run(
                        image, thresholds[t], 0, Integer.MAX_VALUE, false, false, true, false);
                ResultsTable stats = classic.getStatistics();
                labels = classic.getObjectsMap();
                if (labels == null || stats.size() == 0) continue;
                LabelFeatureAccumulator.Result measured =
                        LabelFeatureAccumulator.scan(labels, image, image.getCalibration());
                for (int row = 0; row < stats.size(); row++) {
                    int label = (int) Math.round(stats.getValue("Label", row));
                    LabelFeatureAccumulator.FeatureValues values =
                            measured.valuesForLabel(label);
                    if (values == null) continue;
                    compared++;
                    if (stats.getValue("Median", row) != values.median()) {
                        mismatches++;
                        System.out.println("  MISMATCH threshold=" + thresholds[t]
                                + " label=" + label
                                + " classic=" + stats.getValue("Median", row)
                                + " accumulator=" + values.median());
                    }
                }
            } finally {
                Stacks.discard(labels);
                Stacks.discard(image);
            }
        }
        System.out.println("=== Median agreement: " + (compared - mismatches)
                + " of " + compared + " objects exact across "
                + thresholds.length + " thresholds ===");
        assertTrue("no objects were compared", compared > 0);
        assertEquals("objects whose Median differs", 0, mismatches);
    }

    /** Local copy of the discard helper; the equivalence package's is not visible here. */
    private static final class Stacks {
        static void discard(ImagePlus image) {
            if (image != null) {
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
    }
}
