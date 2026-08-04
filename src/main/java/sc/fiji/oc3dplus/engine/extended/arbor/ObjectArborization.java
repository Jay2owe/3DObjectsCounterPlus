package sc.fiji.oc3dplus.engine.extended.arbor;

import ij.measure.Calibration;
import sc.fiji.oc3dplus.engine.extended.ObjectMask3D;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Computes per-object skeleton graph and Sholl measurements from a binary 3D
 * object mask.
 *
 * <p>The mask is row-major ({@code z * width * height + y * width + x}).
 * Skeletonization uses Fiji's Skeletonize3D plugin reflectively. If it is
 * unavailable or produces an unusable result, measurements fail closed. The
 * plugin-owned thinning implementation remains disabled until numerical
 * parity is certified. Graph measurements always come from this package's
 * 26-neighbour graph analyzer, so AnalyzeSkeleton is only a release-test
 * oracle and is not a runtime dependency.
 *
 * <p>Sholl radii use a fixed 5 micrometre step. If the calibration unit cannot
 * be converted to micrometres, skeleton counts remain available but all Sholl
 * values are {@link Double#NaN}.
 */
public final class ObjectArborization {

    public static final double SHOLL_STEP_UM = 5.0;

    private ObjectArborization() {
    }

    /** True when Sholl radii can be expressed in micrometres. */
    public static boolean hasPhysicalShollCalibration(Calibration calibration) {
        return MicronCalibration.from(calibration).available;
    }

    /**
     * Measure the shared immutable object-mask representation used by the
     * other extended measurements.
     */
    public static Result compute(ObjectMask3D mask, Calibration calibration) {
        if (mask == null) {
            throw new IllegalArgumentException("mask must not be null (mask=null).");
        }
        boolean[] voxels = new boolean[mask.width() * mask.height() * mask.depth()];
        for (int z = 0; z < mask.depth(); z++) {
            for (int y = 0; y < mask.height(); y++) {
                for (int x = 0; x < mask.width(); x++) {
                    voxels[z * mask.width() * mask.height() + y * mask.width() + x] =
                            mask.contains(x, y, z);
                }
            }
        }
        return compute(voxels, mask.width(), mask.height(), mask.depth(), calibration);
    }

    /**
     * Measure one connected object mask.
     *
     * @param mask row-major binary object mask
     * @param width mask width
     * @param height mask height
     * @param depth mask depth
     * @param calibration spatial calibration; may be null
     * @return a valid graph result, possibly with unavailable Sholl values, or
     *         a fail-closed unavailable result
     * @throws IllegalArgumentException for invalid dimensions or mask length
     */
    public static Result compute(boolean[] mask,
                                 int width,
                                 int height,
                                 int depth,
                                 Calibration calibration) {
        MaskVolume volume = MaskVolume.create(mask, width, height, depth, calibration);
        if (!volume.available) {
            return Result.unavailable(volume.unavailableReason);
        }

        Skeletonizer.Result skeletonized = Skeletonizer.skeletonize(volume);
        if (!skeletonized.available) {
            return Result.unavailable(skeletonized.unavailableReason);
        }

        SkeletonGraphAnalyzer.Summary graph = SkeletonGraphAnalyzer.analyze(
                skeletonized.skeleton, volume.width, volume.height, volume.depth);
        if (!graph.available) {
            return Result.unavailable(graph.unavailableReason);
        }

        ShollAnalyzer.Profile sholl = ShollAnalyzer.compute(
                skeletonized.skeleton, volume, SHOLL_STEP_UM);
        return new Result(
                graph.branches,
                graph.junctions,
                graph.endpoints,
                graph.skeletonVoxels,
                sholl.criticalRadiusUm,
                sholl.criticalIntersections,
                sholl.schoenenIndex,
                sholl.primaryBranches,
                sholl.points,
                skeletonized.backend,
                true,
                "");
    }

    /** Immutable result of one per-object arborization measurement. */
    public static final class Result {
        public final int skeletonBranches;
        public final int skeletonJunctions;
        public final int skeletonEndpoints;
        public final int skeletonVoxels;
        public final double shollCriticalRadiusUm;
        public final double shollCriticalIntersections;
        public final double shollSchoenenIndex;
        public final double shollPrimaryBranches;
        public final List<ShollPoint> shollProfile;
        public final String skeletonBackend;
        public final boolean valid;
        public final String unavailableReason;

        private Result(int skeletonBranches,
                       int skeletonJunctions,
                       int skeletonEndpoints,
                       int skeletonVoxels,
                       double shollCriticalRadiusUm,
                       double shollCriticalIntersections,
                       double shollSchoenenIndex,
                       double shollPrimaryBranches,
                       List<ShollPoint> shollProfile,
                       String skeletonBackend,
                       boolean valid,
                       String unavailableReason) {
            this.skeletonBranches = skeletonBranches;
            this.skeletonJunctions = skeletonJunctions;
            this.skeletonEndpoints = skeletonEndpoints;
            this.skeletonVoxels = skeletonVoxels;
            this.shollCriticalRadiusUm = shollCriticalRadiusUm;
            this.shollCriticalIntersections = shollCriticalIntersections;
            this.shollSchoenenIndex = shollSchoenenIndex;
            this.shollPrimaryBranches = shollPrimaryBranches;
            this.shollProfile = shollProfile == null
                    ? Collections.<ShollPoint>emptyList()
                    : Collections.unmodifiableList(new ArrayList<ShollPoint>(shollProfile));
            this.skeletonBackend = skeletonBackend == null ? "" : skeletonBackend;
            this.valid = valid;
            this.unavailableReason = unavailableReason == null ? "" : unavailableReason;
        }

        static Result unavailable(String reason) {
            return new Result(
                    -1, -1, -1, -1,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Collections.<ShollPoint>emptyList(),
                    "Unavailable",
                    false,
                    reason);
        }

        /** True when calibrated 5 micrometre Sholl measurements are present. */
        public boolean hasShollMeasurements() {
            return Double.isFinite(shollCriticalRadiusUm)
                    && Double.isFinite(shollCriticalIntersections);
        }
    }

    /** One radius row from the calibrated Sholl profile. */
    public static final class ShollPoint {
        public final double radiusUm;
        public final int intersections;

        ShollPoint(double radiusUm, int intersections) {
            this.radiusUm = radiusUm;
            this.intersections = intersections;
        }
    }
}
