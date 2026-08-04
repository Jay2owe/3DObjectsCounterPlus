package sc.fiji.oc3dplus;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusParameters;
import sc.fiji.oc3dplus.api.OC3DPlusResult;
import sc.fiji.oc3dplus.engine.OC3DPlusRunner;
import sc.fiji.oc3dplus.engine.ObjectMapBuilder;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end engine coverage on a synthetic in-memory 3D stack. Exercises
 * the same code path the macro plugin entry follows: build parameters via
 * {@link OC3DPlus.Builder}, call {@link OC3DPlus#count} (or
 * {@link OC3DPlus#detect}), inspect the result.
 *
 * <p>Builds a 30x30x30 stack with three solid cubes at known locations and
 * known intensities, then asserts the resulting object count + label image.
 * Requires {@code mcib3d-core} on the classpath at test time (provided by
 * Maven's {@code provided} scope for compile + test).
 */
public class HeadlessSyntheticStackTest {

    private static final int W = 30, H = 30, D = 30;

    @Test
    public void detectsThreeKnownBlobs() {
        ImagePlus stack = buildThreeBlobStack();

        OC3DPlusResult result = OC3DPlus.detect(stack,
                /* threshold */ 100,
                /* minSize   */ 1,
                /* maxSize   */ Integer.MAX_VALUE,
                /* edges     */ false,
                /* intensity */ null);

        assertNotNull(result.statistics());
        assertEquals("expected three blobs to survive detection",
                3, result.objectCount());
        assertNotNull("label image should be produced", result.labelImage());
    }

    @Test
    public void countWithoutFiltersBehavesLikeDetect() {
        ImagePlus stack = buildThreeBlobStack();

        OC3DPlusParameters params = OC3DPlus.builder()
                .threshold(100)
                .minSize(1)
                .build();

        OC3DPlusResult result = OC3DPlus.count(stack, params);

        assertEquals(3, result.objectCount());
        assertEquals("no filters -> no per-filter counts",
                0, result.survivingPerFilter().length);
    }

    @Test
    public void countWithoutFiltersHandlesThirtyNineSliceRawStack() {
        ImagePlus stack = buildThirtyNineSliceRawStack();

        OC3DPlusResult result = OC3DPlus.count(stack, OC3DPlus.builder()
                .threshold(100)
                .minSize(1)
                .build());

        assertEquals(1, result.objectCount());
        assertNotNull(result.labelImage());
        assertEquals(39, result.labelImage().getStackSize());
        assertEquals(0, result.survivingPerFilter().length);
    }

    @Test
    public void countWithFilterHandlesThirtyNineSliceRawStack() {
        ImagePlus stack = buildThirtyNineSliceRawStack();

        OC3DPlusResult result = OC3DPlus.count(stack, OC3DPlus.builder()
                .threshold(100)
                .minSize(1)
                .addFilter("volume", ">=", 100.0)
                .build());

        assertNotNull(result.statistics());
        assertEquals(1, result.objectCount());
        assertEquals(result.objectCount(), result.statistics().size());
        assertNotNull(result.labelImage());
        assertEquals(39, result.labelImage().getStackSize());
        assertConsecutiveLabels(result.statistics());
        assertEquals(positiveLabels(result.labelImage()), labelsFromStats(result.statistics()));
        assertEquals(1, result.survivingPerFilter().length);
        assertEquals(1, result.survivingPerFilter()[0]);
    }

    @Test
    public void countCompletesHighFragmentationThirtyNineSliceRawStackAndBuildsMaps() {
        String previousOverlayLimit = System.getProperty(ObjectMapBuilder.MAX_OVERLAY_LABELS_PROPERTY);
        String previousReserve = System.getProperty(
                ObjectMapBuilder.OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY);
        System.setProperty(ObjectMapBuilder.MAX_OVERLAY_LABELS_PROPERTY, "2000");
        System.setProperty(ObjectMapBuilder.OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY, "0");

        ImagePlus stack = buildHighFragmentationThirtyNineSliceRawStack();
        OC3DPlusResult result = null;
        ImagePlus objects = null;
        ImagePlus surfaces = null;
        ImagePlus centroids = null;
        ImagePlus centersOfMass = null;
        try {
            result = OC3DPlus.count(stack, OC3DPlus.builder()
                    .threshold(100)
                    .minSize(1)
                    .build());

            assertEquals(3328, result.objectCount());
            assertNotNull(result.labelImage());
            assertEquals(39, result.labelImage().getStackSize());
            assertEquals(0, result.survivingPerFilter().length);
            assertNull(result.labelImage().getProperty(OC3DPlusRunner.OBJECT_STATS_PROPERTY));
            assertEquals(0, OC3DPlusRunner.statsProperty(result.labelImage()).size());

            ResultsTable stats = result.statistics();
            assertEquals(result.objectCount(), stats.size());
            assertHasColumn(stats, "Volume (pixel^3)");
            assertHasColumn(stats, "Surface (pixel^2)");
            assertHasColumn(stats, "Nb of obj. voxels");
            assertHasColumn(stats, "IntDen");
            assertHasColumn(stats, "Mean");
            assertHasColumn(stats, "Min");
            assertHasColumn(stats, "Max");
            assertHasColumn(stats, "X");
            assertHasColumn(stats, "Y");
            assertHasColumn(stats, "Z");
            assertHasColumn(stats, "XM");
            assertHasColumn(stats, "YM");
            assertHasColumn(stats, "ZM");
            assertHasColumn(stats, "Morph_Sphericity");
            assertHasColumn(stats, "Morph_Compactness");
            assertHasColumn(stats, "Morph_Elongation");
            assertHasColumn(stats, "Morph_Feret3D_um");
            assertFiniteCell(stats, "Mean", 0);
            assertFiniteCell(stats, "Max", 0);
            assertFiniteCell(stats, "X", 0);

            objects = ObjectMapBuilder.objectMap(result.labelImage(), stats, stack.getTitle());
            surfaces = ObjectMapBuilder.surfaceMap(result.labelImage(), stats, stack.getTitle());
            centroids = ObjectMapBuilder.centroidMap(result.labelImage(), stats, stack.getTitle());
            centersOfMass = ObjectMapBuilder.centerOfMassMap(result.labelImage(), stats, stack.getTitle());

            assertNotNull(objects);
            assertNotNull(surfaces);
            assertNotNull(centroids);
            assertNotNull(centersOfMass);
            assertEquals(39, objects.getStackSize());
            assertEquals(39, surfaces.getStackSize());
            assertEquals(39, centroids.getStackSize());
            assertEquals(39, centersOfMass.getStackSize());
            assertLabelOverlaySize(objects, result.objectCount());
            assertLabelOverlaySize(surfaces, result.objectCount());
            assertLabelOverlaySize(centroids, result.objectCount());
            assertLabelOverlaySize(centersOfMass, result.objectCount());
        } finally {
            restoreProperty(ObjectMapBuilder.MAX_OVERLAY_LABELS_PROPERTY, previousOverlayLimit);
            restoreProperty(ObjectMapBuilder.OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY,
                    previousReserve);
            discard(objects);
            discard(surfaces);
            discard(centroids);
            discard(centersOfMass);
            if (result != null) discard(result.labelImage());
            discard(stack);
        }
    }

    @Test
    public void countWithBasicFilterCompletesHighFragmentationThirtyNineSliceRawStack() {
        ImagePlus stack = buildHighFragmentationThirtyNineSliceRawStack();
        OC3DPlusResult result = null;
        try {
            result = OC3DPlus.count(stack, OC3DPlus.builder()
                    .threshold(100)
                    .minSize(1)
                    .addFilter("volume", ">=", 2.0)
                    .build());

            assertEquals(0, result.objectCount());
            assertEquals(result.objectCount(), result.statistics().size());
            assertNotNull(result.labelImage());
            assertEquals(39, result.labelImage().getStackSize());
            assertTrue(positiveLabels(result.labelImage()).isEmpty());
            assertTrue(labelsFromStats(result.statistics()).isEmpty());
            assertHasColumn(result.statistics(), "Morph_Sphericity");
            assertHasColumn(result.statistics(), "Morph_Compactness");
            assertHasColumn(result.statistics(), "Morph_Elongation");
            assertHasColumn(result.statistics(), "Morph_Feret3D_um");
            assertEquals(1, result.survivingPerFilter().length);
            assertEquals(0, result.survivingPerFilter()[0]);
        } finally {
            if (result != null) discard(result.labelImage());
            discard(stack);
        }
    }

    @Test
    public void countWithBasicFilterKeepingHighFragmentationLabelsMatchesStatsAndImage() {
        ImagePlus stack = buildHighFragmentationThirtyNineSliceRawStack();
        OC3DPlusResult result = null;
        try {
            result = OC3DPlus.count(stack, OC3DPlus.builder()
                    .threshold(100)
                    .minSize(1)
                    .addFilter("volume", ">=", 1.0)
                    .build());

            assertEquals(3328, result.objectCount());
            assertEquals(result.objectCount(), result.statistics().size());
            assertNotNull(result.labelImage());
            assertEquals(39, result.labelImage().getStackSize());
            assertConsecutiveLabels(result.statistics());
            assertEquals(positiveLabels(result.labelImage()), labelsFromStats(result.statistics()));
            assertHasColumn(result.statistics(), "Morph_Sphericity");
            assertHasColumn(result.statistics(), "Morph_Compactness");
            assertHasColumn(result.statistics(), "Morph_Elongation");
            assertHasColumn(result.statistics(), "Morph_Feret3D_um");
            assertEquals(1, result.survivingPerFilter().length);
            assertEquals(3328, result.survivingPerFilter()[0]);
        } finally {
            if (result != null) discard(result.labelImage());
            discard(stack);
        }
    }

    @Test
    public void countWithShapeFiltersCompletesHighFragmentationThirtyNineSliceRawStackAndBuildsMaps() {
        String previousOverlayLimit = System.getProperty(ObjectMapBuilder.MAX_OVERLAY_LABELS_PROPERTY);
        String previousReserve = System.getProperty(
                ObjectMapBuilder.OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY);
        System.setProperty(ObjectMapBuilder.MAX_OVERLAY_LABELS_PROPERTY, "1000");
        System.setProperty(ObjectMapBuilder.OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY, "0");

        ImagePlus stack = buildHighFragmentationThirtyNineSliceRawCubeStack();
        OC3DPlusResult result = null;
        ImagePlus objects = null;
        ImagePlus surfaces = null;
        ImagePlus centroids = null;
        ImagePlus centersOfMass = null;
        try {
            result = OC3DPlus.count(stack, OC3DPlus.builder()
                    .threshold(100)
                    .minSize(1)
                    .addFilter("volume", ">=", 1.0)
                    .addFilter("sphericity", ">=", 0.0)
                    .addFilter("compactness", ">=", 0.0)
                    .addFilter("elongation", ">=", 1.0)
                    .addFilter("feret_diameter_max", ">=", 0.0)
                    .build());

            assertEquals(1690, result.objectCount());
            assertEquals(result.objectCount(), result.statistics().size());
            assertNotNull(result.labelImage());
            assertEquals(39, result.labelImage().getStackSize());
            assertConsecutiveLabels(result.statistics());
            assertEquals(positiveLabels(result.labelImage()), labelsFromStats(result.statistics()));
            assertEquals(5, result.survivingPerFilter().length);
            assertEquals(1690, result.survivingPerFilter()[0]);
            assertEquals(1690, result.survivingPerFilter()[1]);
            assertEquals(1690, result.survivingPerFilter()[2]);
            assertEquals(1690, result.survivingPerFilter()[3]);
            assertEquals(1690, result.survivingPerFilter()[4]);

            ResultsTable stats = result.statistics();
            assertHasColumn(stats, "Morph_Sphericity");
            assertHasColumn(stats, "Morph_Compactness");
            assertHasColumn(stats, "Morph_Elongation");
            assertHasColumn(stats, "Morph_Feret3D_um");
            assertFiniteCell(stats, "Morph_Sphericity", 0);
            assertFiniteCell(stats, "Morph_Compactness", 0);
            assertFiniteCell(stats, "Morph_Elongation", 0);
            assertFiniteCell(stats, "Morph_Feret3D_um", 0);

            objects = ObjectMapBuilder.objectMap(result.labelImage(), stats, stack.getTitle());
            surfaces = ObjectMapBuilder.surfaceMap(result.labelImage(), stats, stack.getTitle());
            centroids = ObjectMapBuilder.centroidMap(result.labelImage(), stats, stack.getTitle());
            centersOfMass = ObjectMapBuilder.centerOfMassMap(result.labelImage(), stats, stack.getTitle());

            assertNotNull(objects);
            assertNotNull(surfaces);
            assertNotNull(centroids);
            assertNotNull(centersOfMass);
            assertEquals(39, objects.getStackSize());
            assertEquals(39, surfaces.getStackSize());
            assertEquals(39, centroids.getStackSize());
            assertEquals(39, centersOfMass.getStackSize());
            assertLabelOverlaySize(objects, result.objectCount());
            assertLabelOverlaySize(surfaces, result.objectCount());
            assertLabelOverlaySize(centroids, result.objectCount());
            assertLabelOverlaySize(centersOfMass, result.objectCount());
        } finally {
            restoreProperty(ObjectMapBuilder.MAX_OVERLAY_LABELS_PROPERTY, previousOverlayLimit);
            restoreProperty(ObjectMapBuilder.OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY,
                    previousReserve);
            discard(objects);
            discard(surfaces);
            discard(centroids);
            discard(centersOfMass);
            if (result != null) discard(result.labelImage());
            discard(stack);
        }
    }

    @Test
    public void filterByVolumeRemovesSmallBlobs() {
        // Three blobs of sizes 3^3=27, 4^3=64, 5^3=125 voxels.
        // Filter volume>=50 should remove the smallest.
        ImagePlus stack = buildThreeBlobStack();

        OC3DPlusParameters params = OC3DPlus.builder()
                .threshold(100)
                .minSize(1)
                .addFilter("volume", ">=", 50.0)
                .build();

        OC3DPlusResult result = OC3DPlus.count(stack, params);

        assertEquals("smallest blob (27 vox) should be filtered out",
                2, result.objectCount());
        assertEquals("one filter -> one entry in surviving counts",
                1, result.survivingPerFilter().length);
        assertEquals(2, result.survivingPerFilter()[0]);
        assertEquals("filter label is round-trippable",
                "volume>=50.0", result.filterLabels()[0]);
    }

    @Test
    public void filterByCalibratedVolumeRemovesSmallBlobsWithUnitScaleCalibration() {
        ImagePlus stack = buildThreeBlobStack();
        Calibration calibration = new Calibration();
        calibration.setUnit("um");
        calibration.pixelWidth = 1.0;
        calibration.pixelHeight = 1.0;
        calibration.pixelDepth = 1.0;
        stack.setCalibration(calibration);

        OC3DPlusResult result = OC3DPlus.count(stack, OC3DPlus.builder()
                .threshold(100)
                .minSize(1)
                .addFilter("volume_calibrated", ">=", 50.0)
                .build());

        assertEquals("smallest blob (27 um^3) should be filtered out",
                2, result.objectCount());
        assertEquals(1, result.survivingPerFilter().length);
        assertEquals(2, result.survivingPerFilter()[0]);
    }

    @Test
    public void filterByVolumeUpperBoundKeepsMiddleBlob() {
        ImagePlus stack = buildThreeBlobStack();

        OC3DPlusParameters params = OC3DPlus.builder()
                .threshold(100)
                .minSize(1)
                .addFilter("volume", ">=", 50.0)
                .addFilter("volume", "<=", 100.0)
                .build();

        OC3DPlusResult result = OC3DPlus.count(stack, params);

        assertEquals("only the 64-voxel blob should survive",
                1, result.objectCount());
        assertEquals(2, result.survivingPerFilter().length);
        assertEquals(2, result.survivingPerFilter()[0]);
        assertEquals(1, result.survivingPerFilter()[1]);
    }

    @Test
    public void emptyImageProducesEmptyResult() {
        ImagePlus stack = buildEmptyStack();

        OC3DPlusResult result = OC3DPlus.detect(stack,
                100, 1, Integer.MAX_VALUE, false, null);

        assertEquals(0, result.objectCount());
        assertFalse(result.foundObjects());
    }

    @Test
    public void macroEntryWiringRunsEngine() {
        // Verify the plugin parses options and produces equivalent results
        // to the direct API call. Skips IJ display side effects by going
        // through the same code path the plugin uses internally.
        MacroOptionsParser.Parsed parsed = MacroOptionsParser.parse(
                "threshold=100 min=1 volume>=50");
        assertEquals(100, parsed.threshold);
        assertEquals(1, parsed.minSize);
        assertEquals(1, parsed.filters.size());

        ImagePlus stack = buildThreeBlobStack();

        OC3DPlusResult result = OC3DPlus.count(stack, OC3DPlus.builder()
                .threshold(parsed.threshold)
                .minSize(parsed.minSize)
                .maxSize(parsed.maxSize)
                .addFilter(parsed.filters.get(0))
                .build());
        assertEquals(2, result.objectCount());
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private static ImagePlus buildThreeBlobStack() {
        ImageStack stack = new ImageStack(W, H);
        for (int z = 0; z < D; z++) {
            ByteProcessor bp = new ByteProcessor(W, H);
            // Blob A: 3x3x3 = 27 voxels (half-open intervals: x in {3,4,5})
            drawCubeSlice(bp, 3, 6, 3, 6, z, 3, 6, 200);
            // Blob B: 4x4x4 = 64 voxels
            drawCubeSlice(bp, 12, 16, 12, 16, z, 12, 16, 200);
            // Blob C: 5x5x5 = 125 voxels
            drawCubeSlice(bp, 21, 26, 21, 26, z, 21, 26, 200);
            stack.addSlice(bp);
        }
        return new ImagePlus("synthetic-3-blobs", stack);
    }

    private static ImagePlus buildEmptyStack() {
        ImageStack stack = new ImageStack(W, H);
        for (int z = 0; z < D; z++) {
            stack.addSlice(new ByteProcessor(W, H));
        }
        return new ImagePlus("synthetic-empty", stack);
    }

    private static ImagePlus buildThirtyNineSliceRawStack() {
        ImageStack stack = new ImageStack(64, 64);
        for (int z = 0; z < 39; z++) {
            ShortProcessor sp = new ShortProcessor(64, 64);
            if (z >= 17 && z < 23) {
                for (int y = 20; y < 26; y++) {
                    for (int x = 20; x < 26; x++) {
                        sp.set(x, y, 240);
                    }
                }
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("synthetic-39-slice-raw", stack);
    }

    private static ImagePlus buildHighFragmentationThirtyNineSliceRawStack() {
        ImageStack stack = new ImageStack(64, 64);
        for (int z = 0; z < 39; z++) {
            ShortProcessor sp = new ShortProcessor(64, 64);
            if (z % 3 == 1) {
                for (int y = 1; y < 64; y += 4) {
                    for (int x = 1; x < 64; x += 4) {
                        sp.set(x, y, 180 + ((x + y + z) & 63));
                    }
                }
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("synthetic-39-slice-fragmented-raw", stack);
    }

    private static ImagePlus buildHighFragmentationThirtyNineSliceRawCubeStack() {
        ImageStack stack = new ImageStack(64, 64);
        for (int z = 0; z < 39; z++) {
            ShortProcessor sp = new ShortProcessor(64, 64);
            boolean cubeSlice = false;
            for (int z0 = 1; z0 + 1 < 39; z0 += 4) {
                if (z == z0 || z == z0 + 1) {
                    cubeSlice = true;
                    break;
                }
            }
            if (cubeSlice) {
                for (int y = 1; y + 1 < 64; y += 5) {
                    for (int x = 1; x + 1 < 64; x += 5) {
                        sp.set(x, y, 200);
                        sp.set(x + 1, y, 200);
                        sp.set(x, y + 1, 200);
                        sp.set(x + 1, y + 1, 200);
                    }
                }
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("synthetic-39-slice-fragmented-cubes-raw", stack);
    }

    /**
     * Fill voxels in a cube on the given Z slice. Cube is x in [x0, x1),
     * y in [y0, y1), z in [z0, z1) (half-open intervals).
     */
    private static void drawCubeSlice(ByteProcessor bp,
                                      int x0, int x1, int y0, int y1,
                                      int z, int z0, int z1,
                                      int intensity) {
        if (z < z0 || z >= z1) return;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                bp.set(x, y, intensity);
            }
        }
    }

    @Test
    public void cubeBuilderProducesExpectedVoxelCount() {
        // Sanity check the helper itself: blob B (4x4x4) should have 64 voxels.
        ImagePlus stack = buildThreeBlobStack();
        int count = 0;
        for (int z = 12; z < 16; z++) {
            ByteProcessor bp = (ByteProcessor) stack.getStack().getProcessor(z + 1);
            for (int y = 12; y < 16; y++) {
                for (int x = 12; x < 16; x++) {
                    if ((bp.get(x, y) & 0xFF) == 200) count++;
                }
            }
        }
        assertEquals("middle blob foreground voxels", 64, count);
    }

    private static void assertHasColumn(ResultsTable stats, String column) {
        assertTrue("missing column " + column, stats.getColumnIndex(column) >= 0);
    }

    private static void assertFiniteCell(ResultsTable stats, String column, int row) {
        assertHasColumn(stats, column);
        assertTrue("non-finite " + column, Double.isFinite(stats.getValue(column, row)));
    }

    private static void assertConsecutiveLabels(ResultsTable stats) {
        assertHasColumn(stats, "Label");
        for (int row = 0; row < stats.size(); row++) {
            assertEquals(row + 1, (int) Math.round(stats.getValue("Label", row)));
        }
    }

    private static Set<Integer> positiveLabels(ImagePlus image) {
        Set<Integer> labels = new HashSet<Integer>();
        if (image == null || image.getStack() == null) return labels;
        ImageStack stack = image.getStack();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            for (int i = 0; i < stack.getProcessor(slice).getPixelCount(); i++) {
                int label = Math.round(stack.getProcessor(slice).getf(i));
                if (label > 0) labels.add(Integer.valueOf(label));
            }
        }
        return labels;
    }

    private static Set<Integer> labelsFromStats(ResultsTable stats) {
        Set<Integer> labels = new HashSet<Integer>();
        for (int row = 0; row < stats.size(); row++) {
            labels.add(Integer.valueOf((int) Math.round(stats.getValue("Label", row))));
        }
        return labels;
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static void assertLabelOverlaySize(ImagePlus image, int expectedLabels) {
        assertNotNull(image.getOverlay());
        assertEquals(expectedLabels, image.getOverlay().size());
        assertNull(image.getProperty(ObjectMapBuilder.OVERLAY_SKIPPED_PROPERTY));
        assertNull(ObjectMapBuilder.overlaySkippedReason(image));
    }

    private static void discard(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }
}
