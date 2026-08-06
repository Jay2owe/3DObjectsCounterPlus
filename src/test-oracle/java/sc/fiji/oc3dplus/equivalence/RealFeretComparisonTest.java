package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;
import ij.plugin.FileInfoVirtualStack;
import org.junit.Assume;
import org.junit.Test;

import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusResult;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Feret comparison on <b>real objects</b>, which is what
 * {@code FERET_DELTA.md} section 5 says the Stage 06 decision is still missing.
 *
 * <p>Everything measured so far came from synthetic shapes: axis-aligned boxes,
 * which flatter the 13-direction estimate because a box's longest axis is a body
 * diagonal and therefore one of the sampled directions, and hand-built oblique rods,
 * which are its worst case by construction. Neither says what happens to microglia
 * and amyloid objects, which are exactly the elongated, obliquely-oriented case the
 * estimate is weakest on. Real objects can now be measured, so they are.
 *
 * <p>Both implementations run on the same label image, produced by the shipping
 * path, so the comparison isolates the Feret algorithm and nothing else. mcib3d is
 * still on the classpath until Stage 04.
 *
 * <pre>
 * java -Xmx5g -Doc3dplus.realFeret=&lt;list file&gt; \
 *      -cp "target/classes;target/test-classes;$(cat cp.txt)" \
 *      org.junit.runner.JUnitCore sc.fiji.oc3dplus.equivalence.RealFeretComparisonTest
 * </pre>
 */
public class RealFeretComparisonTest {

    private static final String LIST_PROPERTY = "oc3dplus.realFeret";

    /**
     * Objects above this are skipped, and the count of skipped objects is reported.
     * mcib3d's exact Feret is pairwise over the contour, so a single very large
     * object can cost more than a whole corpus of ordinary ones.
     */
    private static final long MAX_OBJECT_VOXELS =
            Long.getLong("oc3dplus.realFeret.maxObjectVoxels", 200000L).longValue();

    /** Above this an object is long and thin enough for the direction gap to bite. */
    private static final double ELONGATED = 2.0;

    private static final File REPORT =
            new File("docs" + File.separator + "migration", "FERET_REAL_DATA.md");

    private static final class Row {
        String file = "";
        String note = "";
        int objects = -1;
        int measured;
        int skipped;
        List<Double> relative = new ArrayList<Double>();
        List<Double> elongated = new ArrayList<Double>();
        int over1;
        int over3;
        int over5;
        double worstFeretUnits;
        double worstUnderUnits;
        /**
         * The most negative relative difference seen, i.e. the largest amount by
         * which the estimate <i>exceeded</i> the exact value. The synthetic probe
         * asserts this cannot happen; on real objects it is worth reporting the
         * number rather than assuming the assertion generalises.
         */
        double largestOverEstimate;
        double largestOverEstimateUnits;
    }

    @Test
    public void compareFeretOnRealObjects() throws Exception {
        String configured = System.getProperty(LIST_PROPERTY);
        Assume.assumeTrue("set -D" + LIST_PROPERTY + "=<file listing real stacks>",
                configured != null && !configured.trim().isEmpty());

        List<String> paths = readList(new File(configured.trim()));
        List<Row> rows = new ArrayList<Row>();
        for (int i = 0; i < paths.size(); i++) {
            Row row = measure(new File(paths.get(i)));
            rows.add(row);
            System.out.println("  " + (i + 1) + "/" + paths.size() + "  " + row.file
                    + "  objects=" + row.objects
                    + "  measured=" + row.measured
                    + "  skipped=" + row.skipped
                    + "  " + FeretComparisonProbeTest.distribution(row.relative)
                    + (row.note.isEmpty() ? "" : "  " + row.note));
            System.out.flush();
            write(rows, paths.size());
        }
        write(rows, paths.size());
    }

    private static Row measure(File file) {
        Row row = new Row();
        row.file = file.getName();
        if (!file.isFile()) {
            row.note = "MISSING";
            return row;
        }
        ImagePlus image = null;
        ImagePlus labels = null;
        try {
            try {
                image = FileInfoVirtualStack.openVirtual(file.getAbsolutePath());
            } catch (RuntimeException unopenable) {
                row.note = "could not open virtually: " + unopenable.getClass().getSimpleName();
                return row;
            }
            if (image == null || image.getStack() == null) {
                row.note = "could not open virtually";
                return row;
            }
            int threshold = RealCorpusPerformanceTest.isoDataAtCentreSlice(image);
            OC3DPlusResult result = OC3DPlus.count(image, OC3DPlus.builder()
                    .threshold(threshold).channel(1).frame(1).build());
            row.objects = result.objectCount();
            labels = result.labelImage();
            if (labels == null) {
                row.note = "no label image";
                return row;
            }

            int[] skipped = new int[1];
            List<double[]> pairs =
                    FeretComparisonProbeTest.measurePairs(labels, MAX_OBJECT_VOXELS, skipped);
            row.skipped = skipped[0];
            for (int i = 0; i < pairs.size(); i++) {
                double exact = pairs.get(i)[0];
                double estimate = pairs.get(i)[1];
                double elongation = pairs.get(i)[3];
                if (Double.isNaN(exact) || Double.isInfinite(exact) || exact <= 0.0) continue;
                if (Double.isNaN(estimate) || Double.isInfinite(estimate)) continue;
                double under = (exact - estimate) / exact;
                row.relative.add(Double.valueOf(under));
                if (elongation >= ELONGATED) row.elongated.add(Double.valueOf(under));
                if (under > 0.01) row.over1++;
                if (under > 0.03) row.over3++;
                if (under > 0.05) row.over5++;
                if (exact - estimate > row.worstUnderUnits) {
                    row.worstUnderUnits = exact - estimate;
                    row.worstFeretUnits = exact;
                }
                if (under < row.largestOverEstimate) {
                    row.largestOverEstimate = under;
                    row.largestOverEstimateUnits = estimate - exact;
                }
            }
            row.measured = row.relative.size();
            return row;
        } catch (OutOfMemoryError memory) {
            row.note = "OutOfMemoryError";
            return row;
        } catch (RuntimeException failed) {
            row.note = failed.getClass().getSimpleName() + ": " + failed.getMessage();
            return row;
        } finally {
            Stacks.discard(labels);
            Stacks.discard(image);
        }
    }

    private static List<String> readList(File file) throws IOException {
        List<String> out = new ArrayList<String>();
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            out.add(line);
        }
        return out;
    }

    private static void write(List<Row> rows, int total) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# Feret on real objects - 13-direction estimate vs mcib3d exact\n\n");
        if (rows.size() < total) {
            out.append("**PARTIAL: ").append(rows.size()).append(" of ").append(total)
                    .append(" stacks measured so far.**\n\n");
        }
        out.append("Written by `RealFeretComparisonTest`. Each stack is labelled by the ")
                .append("shipping path (IsoData on the centre slice, channel 1, frame 1), then ")
                .append("both Feret implementations run on that one label image, so the only ")
                .append("difference between the two columns is the algorithm.\n\n");
        out.append("Under-estimate is `(exact - estimate) / exact`. The estimate samples 13 ")
                .append("fixed directions - 3 axes, 6 face diagonals, 4 body diagonals - so it ")
                .append("can only under-read, and does so when an object's long axis falls ")
                .append("between them.\n\n");
        out.append("Objects above ").append(MAX_OBJECT_VOXELS)
                .append(" voxels are skipped and counted, because mcib3d's exact Feret is ")
                .append("pairwise over the contour and one huge object can outweigh a whole ")
                .append("corpus. `Skipped` is therefore part of the result, not a footnote.\n\n");
        out.append("| # | File | Objects | Measured | Skipped | min | median | p95 | max "
                + "| >1% | >3% | >5% | Elongated (>=2) p95 | Worst absolute | Notes |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        List<Double> all = new ArrayList<Double>();
        List<Double> allElongated = new ArrayList<Double>();
        int allOver1 = 0;
        int allOver3 = 0;
        int allOver5 = 0;
        int allSkipped = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            List<Double> sorted = new ArrayList<Double>(row.relative);
            Collections.sort(sorted);
            List<Double> sortedElongated = new ArrayList<Double>(row.elongated);
            Collections.sort(sortedElongated);
            out.append("| ").append(i + 1)
                    .append(" | `").append(row.file).append('`')
                    .append(" | ").append(row.objects < 0 ? "-" : Integer.toString(row.objects))
                    .append(" | ").append(row.measured)
                    .append(" | ").append(row.skipped)
                    .append(" | ").append(cell(sorted, 0.0))
                    .append(" | ").append(cell(sorted, 0.5))
                    .append(" | ").append(cell(sorted, 0.95))
                    .append(" | ").append(cell(sorted, 1.0))
                    .append(" | ").append(row.over1)
                    .append(" | ").append(row.over3)
                    .append(" | ").append(row.over5)
                    .append(" | ").append(sortedElongated.isEmpty()
                            ? "no elongated objects"
                            : cell(sortedElongated, 0.95) + " (n=" + sortedElongated.size() + ")")
                    .append(" | ").append(row.worstUnderUnits <= 0.0 ? "-"
                            : String.format(java.util.Locale.ROOT, "%.3f of %.3f",
                                    row.worstUnderUnits, row.worstFeretUnits))
                    .append(" | ").append(row.note)
                    .append(" |\n");
            all.addAll(row.relative);
            allElongated.addAll(row.elongated);
            allOver1 += row.over1;
            allOver3 += row.over3;
            allOver5 += row.over5;
            allSkipped += row.skipped;
        }
        Collections.sort(all);
        Collections.sort(allElongated);
        out.append('\n');
        out.append("**All objects across all stacks: n=").append(all.size())
                .append(", skipped ").append(allSkipped).append(".** ")
                .append(all.isEmpty() ? "no data" : FeretComparisonProbeTest.distribution(all))
                .append("\n\n");
        if (!all.isEmpty()) {
            out.append("Objects under-reading by more than 1%: ").append(allOver1)
                    .append(" of ").append(all.size())
                    .append(" (").append(percent(allOver1, all.size())).append("). ")
                    .append("More than 3%: ").append(allOver3)
                    .append(" (").append(percent(allOver3, all.size())).append("). ")
                    .append("More than 5%: ").append(allOver5)
                    .append(" (").append(percent(allOver5, all.size())).append(").\n\n");
        }
        double worstOver = 0.0;
        double worstOverUnits = 0.0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).largestOverEstimate < worstOver) {
                worstOver = rows.get(i).largestOverEstimate;
                worstOverUnits = rows.get(i).largestOverEstimateUnits;
            }
        }
        out.append("Largest **over**-estimate anywhere: ")
                .append(worstOver == 0.0
                        ? "none - the estimate never exceeded the exact value, which is what "
                                + "`FeretComparisonProbeTest#theEstimateNeverExceedsTheExactValue` "
                                + "asserts on synthetic shapes"
                        : String.format(java.util.Locale.ROOT,
                                "%.3e relative (%.3e calibrated units)%s",
                                -worstOver, worstOverUnits,
                                -worstOver < 1e-9
                                        ? " - at or below double-rounding scale"
                                        : " - **above double-rounding scale, so it needs "
                                                + "explaining before the sign claim is repeated**"))
                .append("\n\n");
        if (!allElongated.isEmpty()) {
            out.append("Elongated objects only (elongation >= ").append(ELONGATED)
                    .append(", n=").append(allElongated.size()).append("): ")
                    .append(FeretComparisonProbeTest.distribution(allElongated))
                    .append(". This is the subset the direction gap is expected to reach.\n\n");
        }
        out.append("The synthetic figures this sits beside: 0% on axis-aligned and compact ")
                .append("shapes, up to 5.72% on hand-built oblique rods (`FERET_DELTA.md` ")
                .append("section 3). Real objects are the question those could not answer.\n");
        File parent = REPORT.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        Files.write(REPORT.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + REPORT.getAbsolutePath());
    }

    private static String cell(List<Double> sorted, double fraction) {
        if (sorted.isEmpty()) return "-";
        return FeretComparisonProbeTest.format(
                FeretComparisonProbeTest.percentile(sorted, fraction));
    }

    private static String percent(int part, int whole) {
        if (whole == 0) return "0%";
        return String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * part / whole);
    }
}
