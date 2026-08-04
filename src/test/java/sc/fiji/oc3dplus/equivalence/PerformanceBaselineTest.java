package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;
import org.junit.Assume;
import org.junit.Test;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusResult;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Records the pre-migration performance baseline (harness section 8). Not a gate -
 * it is the headline claim for the release, so it is measured rather than
 * asserted.
 *
 * <pre>
 * mvn -o -B test -Dtest=PerformanceBaselineTest -Doc3dplus.performance=true
 * </pre>
 *
 * <p>This measures <b>synthetic</b> volumes only. The release-note figures have to
 * come from the real corpus, which needs the ~20 stacks from Amyloid, Microglia,
 * IHF Pipeline and Thick Sections that Stage 01 asks the user to nominate; a
 * synthetic figure is not a substitute and is labelled as such in the output.
 *
 * <p>Peak heap is read from the JVM's heap memory pools after resetting their peak
 * counters, which measures the whole run rather than sampling it. Two figures from
 * one JVM are comparable with each other; they are not comparable with a figure
 * from a differently-configured JVM, so the report records {@code -Xmx}.
 */
public class PerformanceBaselineTest {

    private static final String ENABLE_PROPERTY = "oc3dplus.performance";
    /** The 52.4M-voxel volume from the Stage 2 measurement; needs a large heap. */
    private static final String LARGE_PROPERTY = "oc3dplus.performance.large";

    private static final File REPORT =
            new File("docs" + File.separator + "migration", "PERFORMANCE_BASELINE.md");

    @Test
    public void recordBaseline() throws Exception {
        Assume.assumeTrue("set -D" + ENABLE_PROPERTY + "=true to measure the baseline",
                Boolean.getBoolean(ENABLE_PROPERTY));

        List<String> rows = new ArrayList<String>();
        rows.add("| Volume | Voxels | Objects | Wall clock | Peak heap |");
        rows.add("|---|---|---|---|---|");
        rows.add(measure(128, 128, 32));
        rows.add(measure(256, 256, 32));
        rows.add(measure(512, 512, 20));
        if (Boolean.getBoolean(LARGE_PROPERTY)) {
            rows.add(measure(1024, 1024, 50));
        }
        write(rows);
        for (int i = 0; i < rows.size(); i++) {
            System.out.println("baseline " + rows.get(i));
        }
    }

    private static String measure(int width, int height, int depth) {
        ImagePlus input = speckle(width, height, depth);
        ImagePlus labels = null;
        try {
            resetPeakHeap();
            long started = System.nanoTime();
            OC3DPlusResult result = OC3DPlus.count(input, OC3DPlus.builder()
                    .threshold(100).minSize(1).build());
            long elapsedMillis = (System.nanoTime() - started) / 1000000L;
            labels = result.labelImage();
            long peakBytes = peakHeap();
            long voxels = (long) width * height * depth;
            return "| " + width + "x" + height + "x" + depth
                    + " | " + voxels
                    + " | " + result.objectCount()
                    + " | " + elapsedMillis + " ms"
                    + " | " + (peakBytes / (1024 * 1024)) + " MB |";
        } finally {
            Stacks.discard(labels);
            Stacks.discard(input);
        }
    }

    /** Blobs on a stride-4 lattice: enough objects to exercise per-object work. */
    private static ImagePlus speckle(int width, int height, int depth) {
        ImagePlus image = Stacks.bytes("perf-" + width + "x" + height + "x" + depth,
                width, height, depth);
        for (int z = 1; z < depth; z += 4) {
            for (int y = 1; y < height - 1; y += 4) {
                for (int x = 1; x < width - 1; x += 4) {
                    Stacks.set(image, x, y, z, Stacks.FOREGROUND);
                    Stacks.set(image, x + 1, y, z, Stacks.FOREGROUND);
                    Stacks.set(image, x, y + 1, z, Stacks.FOREGROUND);
                }
            }
        }
        return image;
    }

    private static void resetPeakHeap() {
        System.gc();
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        for (int i = 0; i < pools.size(); i++) {
            if (pools.get(i).getType() == MemoryType.HEAP) pools.get(i).resetPeakUsage();
        }
    }

    private static long peakHeap() {
        long total = 0;
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        for (int i = 0; i < pools.size(); i++) {
            MemoryPoolMXBean pool = pools.get(i);
            if (pool.getType() != MemoryType.HEAP || pool.getPeakUsage() == null) continue;
            total += pool.getPeakUsage().getUsed();
        }
        return total;
    }

    private static void write(List<String> rows) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# Performance baseline - pre-migration build ")
                .append(GoldenStore.BUILD_SHA).append("\n\n");
        out.append("Measured by `PerformanceBaselineTest`. **Synthetic volumes only.**\n\n");
        out.append("These are not the release-note figures. Harness section 8 requires the ")
                .append("baseline to be measured on the real corpus - the ~20 stacks from ")
                .append("Amyloid, Microglia, IHF Pipeline and Thick Sections, including the ")
                .append("largest routinely processed - so that the memory claim rests on real ")
                .append("inputs. That corpus has not been nominated yet, so this table exists ")
                .append("to make the synthetic before/after comparable and nothing more.\n\n");
        out.append("Detection path: classic `Counter3D` (8-bit, one channel, one frame).\n\n");
        out.append("JVM max heap: ")
                .append(Runtime.getRuntime().maxMemory() / (1024 * 1024))
                .append(" MB. Figures are only comparable within one JVM configuration.\n\n");
        for (int i = 0; i < rows.size(); i++) {
            out.append(rows.get(i)).append('\n');
        }
        out.append("\nPeak heap is the sum of peak usage across the JVM's heap pools after ")
                .append("resetting their peak counters, so it covers the whole run rather than ")
                .append("a sample of it. It includes the fixture itself, which a real run would ")
                .append("read from disk.\n");
        File parent = REPORT.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        Files.write(REPORT.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
    }
}
