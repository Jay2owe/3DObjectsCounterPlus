package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import org.junit.Assume;
import org.junit.Test;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusResult;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What it costs to add a per-object {@code Median} to the accumulator.
 *
 * <pre>
 * mvn -o -B test -Dtest=MedianCostProbeTest -Doc3dplus.medianCost=true
 * </pre>
 *
 * <p>Median is the one Tier 1 column the classic path emits that no replacement
 * computes (TOLERANCES.md §0.4). The decision was to implement it rather than drop
 * the column, on condition that the cost be measured first - because a median is
 * the only statistic in the table that cannot be accumulated in constant space per
 * object. Everything else is a running total.
 *
 * <p>Three strategies are measured against each other on the same labelled volume:
 *
 * <ul>
 *   <li><b>values</b> - retain every foreground voxel's intensity per object, then
 *       select. Exact for any bit depth. This is what {@code Counter3D} does
 *       ({@code Object3D.obj_voxels} plus {@code median(float[])}).</li>
 *   <li><b>dense histogram</b> - one bin array per object over the integer
 *       intensity range, then walk the cumulative count. Exact for 8- and 16-bit.
 *       Cost is per <em>object</em>, not per voxel, so it inverts the trade-off.</li>
 *   <li><b>sparse histogram</b> - a map of value to count per object. Cost follows
 *       the number of distinct intensities an object actually contains.</li>
 * </ul>
 *
 * <p>Memory is measured as bytes <em>retained</em> after a settle-and-collect, not
 * as peak allocation, because the question is what the structure costs to hold
 * while the scan runs.
 */
public class MedianCostProbeTest {

    private static final String ENABLE_PROPERTY = "oc3dplus.medianCost";

    @Test
    public void reportMedianCost() {
        Assume.assumeTrue("set -D" + ENABLE_PROPERTY + "=true to measure the Median cost",
                Boolean.getBoolean(ENABLE_PROPERTY));

        System.out.println("=== cost of adding a per-object Median ===");
        System.out.println("  strategy      = how the median is obtained");
        System.out.println("  retained      = bytes held while scanning, after settle-and-collect");
        System.out.println("  bytes/voxel   = retained / foreground voxels");
        System.out.println("  millis        = wall clock for the median pass alone");
        // Object SIZE is what moves the trade-off, not object count: the value
        // strategy costs per voxel, the histogram strategies cost per object.
        measure("many tiny objects   (8 vox)   512x512x20", 512, 512, 20, 4, 2);
        measure("medium objects      (216 vox) 512x512x20", 512, 512, 20, 12, 6);
        measure("large objects       (1728 vox)512x512x20", 512, 512, 20, 20, 12);
        measure("one slab            (huge)    256x256x32", 256, 256, 32, 512, 200);
    }

    /** All three strategies must agree with each other, or the measurement is meaningless. */
    @Test
    public void everyStrategyProducesTheSameMedian() {
        ImagePlus intensity = speckle(128, 128, 8, 8, 3);
        ImagePlus labels = null;
        try {
            OC3DPlusResult result = OC3DPlus.count(intensity, OC3DPlus.builder()
                    .threshold(1).minSize(1).build());
            labels = result.labelImage();
            assertTrue("expected objects to measure", result.objectCount() > 0);

            Map<Integer, Double> byValues = medianByRetainedValues(labels, intensity).medians;
            Map<Integer, Double> byDense = medianByDenseHistogram(labels, intensity).medians;
            Map<Integer, Double> bySparse = medianBySparseHistogram(labels, intensity).medians;

            assertEquals(byValues.size(), byDense.size());
            assertEquals(byValues.size(), bySparse.size());
            for (Map.Entry<Integer, Double> entry : byValues.entrySet()) {
                double expected = entry.getValue().doubleValue();
                assertEquals("dense histogram disagrees for label " + entry.getKey(),
                        expected, byDense.get(entry.getKey()).doubleValue(), 0.0);
                assertEquals("sparse histogram disagrees for label " + entry.getKey(),
                        expected, bySparse.get(entry.getKey()).doubleValue(), 0.0);
            }
        } finally {
            Stacks.discard(labels);
            Stacks.discard(intensity);
        }
    }

    private static void measure(String label, int width, int height, int depth,
                                int blockStride, int blockSize) {
        ImagePlus intensity = speckle(width, height, depth, blockStride, blockSize);
        ImagePlus labels = null;
        try {
            OC3DPlusResult result = OC3DPlus.count(intensity, OC3DPlus.builder()
                    .threshold(1).minSize(1).build());
            labels = result.labelImage();
            long foreground = countForeground(labels);
            int objects = result.objectCount();

            Measured values = timed(labels, intensity, "values");
            Measured dense = timed(labels, intensity, "dense");
            Measured sparse = timed(labels, intensity, "sparse");

            System.out.println("  " + label
                    + "  objects=" + objects
                    + "  foregroundVoxels=" + foreground);
            report("values         ", values, foreground);
            report("dense histogram", dense, foreground);
            report("sparse histgram", sparse, foreground);
        } finally {
            Stacks.discard(labels);
            Stacks.discard(intensity);
        }
    }

    private static void report(String strategy, Measured measured, long foreground) {
        double bytesPerVoxel = foreground == 0 ? 0.0 : (double) measured.retainedBytes / foreground;
        System.out.println("      " + strategy
                + "  retained=" + (measured.retainedBytes / 1024) + " KB"
                + "  bytes/voxel=" + String.format(java.util.Locale.ROOT, "%.2f", bytesPerVoxel)
                + "  millis=" + measured.millis);
    }

    private static Measured timed(ImagePlus labels, ImagePlus intensity, String strategy) {
        settle();
        long before = usedHeap();
        long started = System.nanoTime();
        Measured measured = "values".equals(strategy) ? medianByRetainedValues(labels, intensity)
                : "dense".equals(strategy) ? medianByDenseHistogram(labels, intensity)
                : medianBySparseHistogram(labels, intensity);
        measured.millis = (System.nanoTime() - started) / 1000000L;
        settle();
        measured.retainedBytes = Math.max(0, usedHeap() - before);
        // Keep the structure reachable until after the measurement.
        assertTrue(measured.medians != null);
        return measured;
    }

    private static final class Measured {
        Map<Integer, Double> medians;
        Object retained;
        long retainedBytes;
        long millis;
    }

    // ── strategy 1: retain every voxel value per object ──────────────────

    private static Measured medianByRetainedValues(ImagePlus labels, ImagePlus intensity) {
        Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
        forEachVoxel(labels, intensity, new VoxelVisitor() {
            @Override public void visit(int label, float value) {
                Integer previous = counts.get(Integer.valueOf(label));
                counts.put(Integer.valueOf(label),
                        Integer.valueOf(previous == null ? 1 : previous.intValue() + 1));
            }
        });
        Map<Integer, float[]> byLabel = new HashMap<Integer, float[]>();
        final Map<Integer, Integer> cursor = new HashMap<Integer, Integer>();
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            byLabel.put(entry.getKey(), new float[entry.getValue().intValue()]);
            cursor.put(entry.getKey(), Integer.valueOf(0));
        }
        final Map<Integer, float[]> values = byLabel;
        forEachVoxel(labels, intensity, new VoxelVisitor() {
            @Override public void visit(int label, float value) {
                Integer key = Integer.valueOf(label);
                float[] array = values.get(key);
                if (array == null) return;
                int at = cursor.get(key).intValue();
                array[at] = value;
                cursor.put(key, Integer.valueOf(at + 1));
            }
        });

        Measured measured = new Measured();
        measured.medians = new HashMap<Integer, Double>();
        for (Map.Entry<Integer, float[]> entry : byLabel.entrySet()) {
            float[] array = entry.getValue();
            Arrays.sort(array);
            measured.medians.put(entry.getKey(), Double.valueOf(medianOfSorted(array)));
        }
        measured.retained = byLabel;
        return measured;
    }

    /** Lower of the two middle values for an even count, matching a cumulative-count walk. */
    private static double medianOfSorted(float[] sorted) {
        if (sorted.length == 0) return Double.NaN;
        return sorted[(sorted.length - 1) / 2];
    }

    // ── strategy 2: dense per-object histogram ───────────────────────────

    private static Measured medianByDenseHistogram(ImagePlus labels, ImagePlus intensity) {
        final int bins = intensity.getBitDepth() == 8 ? 256 : 65536;
        final Map<Integer, int[]> histograms = new HashMap<Integer, int[]>();
        forEachVoxel(labels, intensity, new VoxelVisitor() {
            @Override public void visit(int label, float value) {
                Integer key = Integer.valueOf(label);
                int[] histogram = histograms.get(key);
                if (histogram == null) {
                    histogram = new int[bins];
                    histograms.put(key, histogram);
                }
                int bin = (int) value;
                if (bin >= 0 && bin < bins) histogram[bin]++;
            }
        });
        Measured measured = new Measured();
        measured.medians = new HashMap<Integer, Double>();
        for (Map.Entry<Integer, int[]> entry : histograms.entrySet()) {
            measured.medians.put(entry.getKey(),
                    Double.valueOf(medianFromHistogram(entry.getValue())));
        }
        measured.retained = histograms;
        return measured;
    }

    private static double medianFromHistogram(int[] histogram) {
        long total = 0;
        for (int i = 0; i < histogram.length; i++) total += histogram[i];
        if (total == 0) return Double.NaN;
        long target = (total - 1) / 2;
        long seen = 0;
        for (int i = 0; i < histogram.length; i++) {
            seen += histogram[i];
            if (seen > target) return i;
        }
        return Double.NaN;
    }

    // ── strategy 3: sparse per-object histogram ──────────────────────────

    private static Measured medianBySparseHistogram(ImagePlus labels, ImagePlus intensity) {
        final Map<Integer, Map<Integer, Integer>> histograms =
                new HashMap<Integer, Map<Integer, Integer>>();
        forEachVoxel(labels, intensity, new VoxelVisitor() {
            @Override public void visit(int label, float value) {
                Integer key = Integer.valueOf(label);
                Map<Integer, Integer> histogram = histograms.get(key);
                if (histogram == null) {
                    histogram = new HashMap<Integer, Integer>();
                    histograms.put(key, histogram);
                }
                Integer bin = Integer.valueOf((int) value);
                Integer previous = histogram.get(bin);
                histogram.put(bin, Integer.valueOf(previous == null ? 1 : previous.intValue() + 1));
            }
        });
        Measured measured = new Measured();
        measured.medians = new HashMap<Integer, Double>();
        for (Map.Entry<Integer, Map<Integer, Integer>> entry : histograms.entrySet()) {
            measured.medians.put(entry.getKey(),
                    Double.valueOf(medianFromSparse(entry.getValue())));
        }
        measured.retained = histograms;
        return measured;
    }

    private static double medianFromSparse(Map<Integer, Integer> histogram) {
        List<Integer> keys = new ArrayList<Integer>(histogram.keySet());
        java.util.Collections.sort(keys);
        long total = 0;
        for (int i = 0; i < keys.size(); i++) total += histogram.get(keys.get(i)).intValue();
        if (total == 0) return Double.NaN;
        long target = (total - 1) / 2;
        long seen = 0;
        for (int i = 0; i < keys.size(); i++) {
            seen += histogram.get(keys.get(i)).intValue();
            if (seen > target) return keys.get(i).intValue();
        }
        return Double.NaN;
    }

    // ── plumbing ────────────────────────────────────────────────────────

    private interface VoxelVisitor {
        void visit(int label, float value);
    }

    private static void forEachVoxel(ImagePlus labels, ImagePlus intensity, VoxelVisitor visitor) {
        ImageStack labelStack = labels.getStack();
        ImageStack intensityStack = intensity.getStack();
        int slices = Math.min(labelStack.getSize(), intensityStack.getSize());
        for (int z = 1; z <= slices; z++) {
            ImageProcessor labelProcessor = labelStack.getProcessor(z);
            ImageProcessor intensityProcessor = intensityStack.getProcessor(z);
            int pixels = Math.min(labelProcessor.getPixelCount(), intensityProcessor.getPixelCount());
            for (int i = 0; i < pixels; i++) {
                float raw = labelProcessor.getf(i);
                if (!Float.isFinite(raw) || raw <= 0f) continue;
                visitor.visit(Math.round(raw), intensityProcessor.getf(i));
            }
        }
    }

    private static long countForeground(ImagePlus labels) {
        final long[] total = new long[1];
        ImageStack stack = labels.getStack();
        for (int z = 1; z <= stack.getSize(); z++) {
            ImageProcessor processor = stack.getProcessor(z);
            for (int i = 0; i < processor.getPixelCount(); i++) {
                float raw = processor.getf(i);
                if (Float.isFinite(raw) && raw > 0f) total[0]++;
            }
        }
        return total[0];
    }

    /**
     * Solid cubes of {@code blockSize} on a lattice of {@code stride}, with
     * intensities that vary within an object so a median is not simply its only
     * value. Varying {@code blockSize} is what makes the per-voxel and per-object
     * costs trade against each other.
     */
    private static ImagePlus speckle(int width, int height, int depth, int stride, int blockSize) {
        ImagePlus image = Stacks.bytes("median-" + width + "x" + height + "x" + depth,
                width, height, depth);
        for (int z = 1; z + blockSize < depth; z += stride) {
            for (int y = 1; y + blockSize < height; y += stride) {
                for (int x = 1; x + blockSize < width; x += stride) {
                    for (int dz = 0; dz < blockSize; dz++) {
                        for (int dy = 0; dy < blockSize; dy++) {
                            for (int dx = 0; dx < blockSize; dx++) {
                                int value = 40 + ((x + y + z + dx * 3 + dy * 5 + dz * 7) % 200);
                                Stacks.set(image, x + dx, y + dy, z + dz, value);
                            }
                        }
                    }
                }
            }
        }
        return image;
    }

    private static void settle() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(30);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static long usedHeap() {
        long used = 0;
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        for (int i = 0; i < pools.size(); i++) {
            MemoryPoolMXBean pool = pools.get(i);
            if (pool.getType() == MemoryType.HEAP && pool.getUsage() != null) {
                used += pool.getUsage().getUsed();
            }
        }
        return used;
    }
}
