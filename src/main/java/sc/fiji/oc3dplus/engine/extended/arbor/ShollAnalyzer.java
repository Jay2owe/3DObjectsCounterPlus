package sc.fiji.oc3dplus.engine.extended.arbor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;

final class ShollAnalyzer {

    private static final int MAX_PROFILE_RADII = 100000;

    private ShollAnalyzer() {
    }

    static Profile compute(boolean[] skeleton, MaskVolume volume, double stepUm) {
        if (skeleton == null || volume == null
                || !volume.micronCalibration.available
                || !(stepUm > 0.0)
                || !Double.isFinite(stepUm)) {
            return Profile.unavailable();
        }

        double[] distancesUm = new double[skeleton.length];
        double maxRadiusUm = 0.0;
        int skeletonVoxelCount = 0;
        for (int index = 0; index < skeleton.length; index++) {
            checkCancelled(index);
            if (!skeleton[index]) {
                continue;
            }
            double distance = distanceUm(index, volume);
            if (!Double.isFinite(distance)) {
                return Profile.unavailable();
            }
            distancesUm[index] = distance;
            maxRadiusUm = Math.max(maxRadiusUm, distance);
            skeletonVoxelCount++;
        }
        if (skeletonVoxelCount == 0 || !(maxRadiusUm >= stepUm)) {
            return Profile.unavailable();
        }

        int radiusCount = (int) Math.floor(maxRadiusUm / stepUm + 1.0e-12);
        if (radiusCount <= 0 || radiusCount > MAX_PROFILE_RADII) {
            return Profile.unavailable();
        }

        int[] difference = new int[radiusCount + 2];
        for (int first = 0; first < skeleton.length; first++) {
            checkCancelled(first);
            if (!skeleton[first]) {
                continue;
            }
            int[] adjacent = BinaryMaskOps.foregroundNeighbors26(
                    skeleton, first, volume.width, volume.height, volume.depth);
            for (int n = 0; n < adjacent.length; n++) {
                int second = adjacent[n];
                if (second <= first) {
                    continue;
                }
                double min = Math.min(distancesUm[first], distancesUm[second]);
                double max = Math.max(distancesUm[first], distancesUm[second]);
                int firstRadius = (int) Math.floor(min / stepUm) + 1;
                int lastRadius = (int) Math.floor(max / stepUm + 1.0e-12);
                firstRadius = Math.max(1, firstRadius);
                lastRadius = Math.min(radiusCount, lastRadius);
                if (firstRadius <= lastRadius) {
                    difference[firstRadius]++;
                    difference[lastRadius + 1]--;
                }
            }
        }

        List<ObjectArborization.ShollPoint> points =
                new ArrayList<ObjectArborization.ShollPoint>(radiusCount);
        int intersections = 0;
        int critical = 0;
        double criticalRadius = Double.NaN;
        int primary = 0;
        for (int radiusIndex = 1; radiusIndex <= radiusCount; radiusIndex++) {
            intersections += difference[radiusIndex];
            double radius = radiusIndex * stepUm;
            points.add(new ObjectArborization.ShollPoint(radius, intersections));
            if (primary == 0 && intersections > 0) {
                primary = intersections;
            }
            if (intersections > critical) {
                critical = intersections;
                criticalRadius = radius;
            }
        }
        if (critical <= 0 || primary <= 0) {
            return Profile.unavailable();
        }
        return new Profile(
                criticalRadius,
                critical,
                ((double) critical) / primary,
                primary,
                points);
    }

    private static void checkCancelled(int index) {
        if ((index & 1023) == 0 && Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Sholl analysis cancelled.");
        }
    }

    private static double distanceUm(int index, MaskVolume volume) {
        MicronCalibration calibration = volume.micronCalibration;
        double dx = (BinaryMaskOps.xOf(index, volume.width) - volume.centerX)
                * calibration.pixelWidthUm;
        double dy = (BinaryMaskOps.yOf(index, volume.width, volume.height) - volume.centerY)
                * calibration.pixelHeightUm;
        double dz = (BinaryMaskOps.zOf(index, volume.width, volume.height) - volume.centerZ)
                * calibration.pixelDepthUm;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static final class Profile {
        final double criticalRadiusUm;
        final double criticalIntersections;
        final double schoenenIndex;
        final double primaryBranches;
        final List<ObjectArborization.ShollPoint> points;

        private Profile(double criticalRadiusUm,
                        double criticalIntersections,
                        double schoenenIndex,
                        double primaryBranches,
                        List<ObjectArborization.ShollPoint> points) {
            this.criticalRadiusUm = criticalRadiusUm;
            this.criticalIntersections = criticalIntersections;
            this.schoenenIndex = schoenenIndex;
            this.primaryBranches = primaryBranches;
            this.points = points;
        }

        static Profile unavailable() {
            return new Profile(
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Collections.<ObjectArborization.ShollPoint>emptyList());
        }
    }
}
