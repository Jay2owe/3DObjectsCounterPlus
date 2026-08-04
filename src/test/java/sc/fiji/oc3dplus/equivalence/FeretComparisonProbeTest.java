package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * The Feret delta distribution that the Stage 06 decision needs, measured on
 * shapes chosen to be <b>unkind</b> to the estimate under test.
 *
 * <p>Why this exists separately from the corpus: the Case C fixtures are all
 * axis-aligned boxes, and a box's longest axis is a body diagonal, which is one of
 * the 13 directions the accumulator samples. The corpus therefore reports
 * agreement to one or two ULP - a true measurement, but close to the best case,
 * and it would badly overstate how well the estimate does in general. An oblique
 * rod whose long axis lies between the sampled directions is where a bounded
 * directional estimate loses, so that is what is measured here.
 *
 * <p>The 13 directions are the 3 axes, the 6 face diagonals and the 4 body
 * diagonals of the 26-neighbourhood. A direction such as (2,1,0) is not among
 * them.
 *
 * <p>mcib3d computes Feret exactly, by pairwise boundary distance, and is still on
 * the classpath until Stage 04 - so both implementations can be run side by side
 * on the same label image, which is a stronger comparison than either against a
 * golden.
 */
public class FeretComparisonProbeTest {

    /** The estimate samples a fixed direction set, so it can only under-estimate. */
    private static final double SIGN_TOLERANCE = 1e-9;

    @Test
    public void reportFeretDeltaDistribution() {
        List<Double> allRelative = new ArrayList<Double>();
        List<String> rows = new ArrayList<String>();
        rows.add(probe("axis-aligned cube 3x3x3", cube(), allRelative));
        rows.add(probe("solid sphere r=6", sphere(), allRelative));
        rows.add(probe("rod along (1,0,0)", rod(1, 0, 0, 14), allRelative));
        rows.add(probe("rod along (1,1,1) - a sampled direction", rod(1, 1, 1, 9), allRelative));
        rows.add(probe("rod along (2,1,0) - NOT a sampled direction", rod(2, 1, 0, 9), allRelative));
        rows.add(probe("rod along (3,1,0) - NOT a sampled direction", rod(3, 1, 0, 6), allRelative));
        rows.add(probe("rod along (2,1,1) - NOT a sampled direction", rod(2, 1, 1, 8), allRelative));
        rows.add(probe("rod along (3,2,1) - NOT a sampled direction", rod(3, 2, 1, 5), allRelative));

        System.out.println("=== Feret: 13-direction estimate vs mcib3d exact ===");
        for (int i = 0; i < rows.size(); i++) {
            System.out.println("  " + rows.get(i));
        }
        System.out.println("  ALL OBJECTS  " + distribution(allRelative));
        assertTrue("expected at least one object measured", !allRelative.isEmpty());
    }

    /**
     * The estimate must never exceed the exact value. An over-estimate would mean
     * the sampled extrema are not a subset of the true pairwise distances, which
     * would be a bug rather than a bounded approximation.
     */
    @Test
    public void theEstimateNeverExceedsTheExactValue() {
        int[][] directions = {{1, 0, 0}, {1, 1, 1}, {2, 1, 0}, {3, 1, 0}, {2, 1, 1}, {3, 2, 1}};
        for (int i = 0; i < directions.length; i++) {
            ImagePlus labels = rod(directions[i][0], directions[i][1], directions[i][2], 8);
            try {
                List<double[]> pairs = measurePairs(labels);
                for (int p = 0; p < pairs.size(); p++) {
                    double exact = pairs.get(p)[0];
                    double estimate = pairs.get(p)[1];
                    assertTrue("13-direction Feret over-estimated on direction ("
                                    + directions[i][0] + "," + directions[i][1] + ","
                                    + directions[i][2] + "): exact=" + exact
                                    + " estimate=" + estimate,
                            estimate <= exact + SIGN_TOLERANCE * Math.max(1.0, exact));
                }
            } finally {
                Stacks.discard(labels);
            }
        }
    }

    private static String probe(String label, ImagePlus labels, List<Double> accumulator) {
        try {
            List<double[]> pairs = measurePairs(labels);
            List<Double> relative = new ArrayList<Double>();
            for (int i = 0; i < pairs.size(); i++) {
                double exact = pairs.get(i)[0];
                double estimate = pairs.get(i)[1];
                if (!isFinite(exact) || !isFinite(estimate) || exact == 0.0) continue;
                relative.add(Double.valueOf((exact - estimate) / exact));
            }
            accumulator.addAll(relative);
            return pad(label) + distribution(relative)
                    + (pairs.isEmpty() ? "  (no objects)" : "  n=" + pairs.size());
        } finally {
            Stacks.discard(labels);
        }
    }

    /** {@code {exactFeret, estimatedFeret}} per object, ascending by label. */
    private static List<double[]> measurePairs(ImagePlus labels) {
        List<double[]> out = new ArrayList<double[]>();

        sc.fiji.oc3d.core.measure.LabelFeatureAccumulator.Result measured =
                sc.fiji.oc3d.core.measure.LabelFeatureAccumulator.scan(
                        labels, null, labels.getCalibration());

        mcib3d.image3d.ImageHandler handler = mcib3d.image3d.ImageHandler.wrap(labels);
        mcib3d.geom2.Objects3DIntPopulation population =
                new mcib3d.geom2.Objects3DIntPopulation(handler);
        List<mcib3d.geom2.Object3DInt> objects = population.getObjects3DInt();
        for (int i = 0; i < objects.size(); i++) {
            mcib3d.geom2.Object3DInt object = objects.get(i);
            if (object == null) continue;
            int label = Math.round(object.getLabel());
            Double exact = new mcib3d.geom2.measurements.MeasureFeret(object)
                    .getValueMeasurement(mcib3d.geom2.measurements.MeasureFeret.FERET_UNIT);
            sc.fiji.oc3d.core.measure.LabelFeatureAccumulator.FeatureValues values =
                    measured.valuesForLabel(label);
            if (exact == null || values == null) continue;
            out.add(new double[] {exact.doubleValue(), values.feretDiameterMax()});
        }
        return out;
    }

    private static String distribution(List<Double> relative) {
        if (relative.isEmpty()) return "no data";
        List<Double> sorted = new ArrayList<Double>(relative);
        Collections.sort(sorted);
        return "min=" + format(percentile(sorted, 0.0))
                + " median=" + format(percentile(sorted, 0.5))
                + " p95=" + format(percentile(sorted, 0.95))
                + " max=" + format(percentile(sorted, 1.0));
    }

    private static double percentile(List<Double> sorted, double fraction) {
        int index = (int) Math.round(fraction * (sorted.size() - 1));
        return sorted.get(index).doubleValue();
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%+.4f%%", value * 100.0);
    }

    private static String pad(String label) {
        StringBuilder out = new StringBuilder(label);
        while (out.length() < 46) out.append(' ');
        return out.toString();
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    // ── shapes ───────────────────────────────────────────────────────────

    private static ImagePlus cube() {
        ImagePlus image = Stacks.shorts("feret-cube", 16, 16, 8);
        Stacks.box(image, 2, 5, 2, 5, 2, 5, 1);
        return image;
    }

    private static ImagePlus sphere() {
        ImagePlus image = Stacks.shorts("feret-sphere", 24, 24, 24);
        Stacks.ball(image, 12, 12, 12, 6, 1);
        return image;
    }

    /**
     * A one-voxel-wide rod of {@code steps} voxels stepping by {@code (dx,dy,dz)},
     * so its long axis lies along that direction.
     */
    private static ImagePlus rod(int dx, int dy, int dz, int steps) {
        int span = Math.max(Math.max(dx, dy), dz) * steps + 4;
        int size = Math.max(16, span);
        int depth = Math.max(8, dz * steps + 4);
        ImagePlus image = Stacks.shorts("feret-rod-" + dx + dy + dz, size, size, depth);
        int x = 2, y = 2, z = 2;
        for (int i = 0; i < steps; i++) {
            if (x >= size || y >= size || z >= depth) break;
            Stacks.set(image, x, y, z, 1);
            x += dx;
            y += dy;
            z += dz;
        }
        return image;
    }
}
