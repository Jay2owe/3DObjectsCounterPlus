package sc.fiji.oc3dplus.engine.extended;

/**
 * Dependency-free composite shape measurements derived from scalar features.
 */
public final class CompositeShapeMeasurements {

    /** Denominators this close to zero are treated as numerically undefined. */
    public static final double POLARITY_DENOMINATOR_TOLERANCE = 1.0e-9;
    /** Smallest 3D object considered reliable for composite reporting. */
    public static final long MIN_RELIABLE_OBJECT_VOXELS = 8L;

    private CompositeShapeMeasurements() {
    }

    public static Result compute(double sphericity,
                                 double distanceMean,
                                 double distanceStandardDeviation,
                                 double spareness,
                                 double elongation,
                                 double flatness,
                                 double feretDiameter,
                                 double volume) {
        return compute(
                sphericity,
                distanceMean,
                distanceStandardDeviation,
                spareness,
                elongation,
                flatness,
                feretDiameter,
                volume,
                Long.MAX_VALUE);
    }

    /**
     * Computes composites with an internal fixed small-object reliability
     * guard. Voxel count is already available from object detection and does
     * not add a user input.
     */
    public static Result compute(double sphericity,
                                 double distanceMean,
                                 double distanceStandardDeviation,
                                 double spareness,
                                 double elongation,
                                 double flatness,
                                 double feretDiameter,
                                 double volume,
                                 long objectVoxelCount) {
        if (objectVoxelCount < MIN_RELIABLE_OBJECT_VOXELS) {
            return Result.unavailable();
        }
        double ramificationIndex =
                safeDivide(1.0, sphericity);
        double surfaceRoughnessIndex =
                isFinite(distanceStandardDeviation)
                        && distanceStandardDeviation >= 0.0
                        ? safeDivide(distanceStandardDeviation, distanceMean)
                        : Double.NaN;
        double processBurden =
                isFinite(spareness) && spareness >= 0.0 && spareness <= 1.0
                        ? 1.0 - spareness : Double.NaN;

        double elongationOffset =
                isFinite(elongation) ? elongation - 1.0 : Double.NaN;
        double flatnessOffset =
                isFinite(flatness) ? flatness - 1.0 : Double.NaN;
        double polarityDenominator = elongationOffset + flatnessOffset;
        double morphologicalPolarity =
                !isFinite(elongationOffset)
                        || !isFinite(flatnessOffset)
                        || elongationOffset < 0.0
                        || flatnessOffset < 0.0
                        || polarityDenominator
                        <= POLARITY_DENOMINATOR_TOLERANCE
                        ? Double.NaN
                        : elongationOffset / polarityDenominator;

        double volumeSpanDiscrepancy = Double.NaN;
        if (isFinite(feretDiameter)
                && isFinite(volume)
                && feretDiameter > 0.0
                && volume > 0.0) {
            double ratio =
                    feretDiameter * feretDiameter * feretDiameter / volume;
            if (isFinite(ratio) && ratio > 0.0) {
                volumeSpanDiscrepancy = Math.log10(ratio);
            }
        }

        return new Result(
                ramificationIndex,
                surfaceRoughnessIndex,
                processBurden,
                morphologicalPolarity,
                volumeSpanDiscrepancy);
    }

    private static double safeDivide(double numerator, double denominator) {
        if (!isFinite(numerator)
                || !isFinite(denominator)
                || denominator <= 0.0) {
            return Double.NaN;
        }
        double quotient = numerator / denominator;
        return isFinite(quotient) ? quotient : Double.NaN;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    public static final class Result {
        private final double ramificationIndex;
        private final double surfaceRoughnessIndex;
        private final double processBurden;
        private final double morphologicalPolarity;
        private final double volumeSpanDiscrepancy;

        private Result(double ramificationIndex,
                       double surfaceRoughnessIndex,
                       double processBurden,
                       double morphologicalPolarity,
                       double volumeSpanDiscrepancy) {
            this.ramificationIndex = ramificationIndex;
            this.surfaceRoughnessIndex = surfaceRoughnessIndex;
            this.processBurden = processBurden;
            this.morphologicalPolarity = morphologicalPolarity;
            this.volumeSpanDiscrepancy = volumeSpanDiscrepancy;
        }

        private static Result unavailable() {
            return new Result(
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN);
        }

        public double ramificationIndex() {
            return ramificationIndex;
        }

        public double surfaceRoughnessIndex() {
            return surfaceRoughnessIndex;
        }

        public double processBurden() {
            return processBurden;
        }

        public double morphologicalPolarity() {
            return morphologicalPolarity;
        }

        public double volumeSpanDiscrepancy() {
            return volumeSpanDiscrepancy;
        }
    }
}
