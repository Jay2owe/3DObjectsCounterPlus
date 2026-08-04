package sc.fiji.oc3dplus.batch;

import java.util.Arrays;

/**
 * Computes descriptive scores relative to the finite values in one batch.
 */
public final class WithinBatchScorer {

    private WithinBatchScorer() {
    }

    /** Immutable scoring result aligned with the input value array. */
    public static final class Result {
        private final double[] zScores;
        private final double[] percentiles;
        private final int finiteCount;

        private Result(double[] zScores, double[] percentiles, int finiteCount) {
            this.zScores = zScores.clone();
            this.percentiles = percentiles.clone();
            this.finiteCount = finiteCount;
        }

        /** Population z-scores; unavailable entries are {@link Double#NaN}. */
        public double[] zScores() {
            return zScores.clone();
        }

        /** Empirical midrank percentiles; unavailable entries are {@link Double#NaN}. */
        public double[] percentiles() {
            return percentiles.clone();
        }

        /** Number of finite input values used as the reference population. */
        public int finiteCount() {
            return finiteCount;
        }
    }

    /**
     * Score each finite input against all finite values in the same array.
     *
     * <p>The z-score uses the population standard deviation (variance
     * denominator {@code N}). The percentile is
     * {@code 100 * (less + 0.5 * equal) / N}. Non-finite inputs are excluded
     * from the reference population and receive {@code NaN} outputs. Fewer
     * than three finite values makes both scores unavailable. A constant
     * population has valid percentiles but unavailable z-scores.
     *
     * @param values values to score
     * @return scores aligned with {@code values}
     */
    public static Result score(double[] values) {
        if (values == null) {
            throw new IllegalArgumentException("values must not be null.");
        }

        double[] zScores = nanArray(values.length);
        double[] percentiles = nanArray(values.length);
        int finiteCount = countFinite(values);
        if (finiteCount < 3) {
            return new Result(zScores, percentiles, finiteCount);
        }

        double[] sorted = new double[finiteCount];
        double mean = 0.0;
        int position = 0;
        for (double value : values) {
            if (!Double.isFinite(value)) continue;
            double normalized = normalizeZero(value);
            sorted[position] = normalized;
            position++;
            mean += (normalized - mean) / position;
        }
        Arrays.sort(sorted);

        double sumSquaredDeviations = 0.0;
        for (double value : sorted) {
            double difference = value - mean;
            sumSquaredDeviations += difference * difference;
        }
        double standardDeviation = Math.sqrt(sumSquaredDeviations / finiteCount);
        boolean zScoreAvailable = Double.isFinite(mean)
                && Double.isFinite(standardDeviation)
                && standardDeviation > 0.0;

        for (int i = 0; i < values.length; i++) {
            double value = values[i];
            if (!Double.isFinite(value)) continue;
            double normalized = normalizeZero(value);
            int less = lowerBound(sorted, normalized);
            int atMost = upperBound(sorted, normalized);
            int equal = atMost - less;
            percentiles[i] = 100.0 * (less + 0.5 * equal) / finiteCount;
            if (zScoreAvailable) {
                zScores[i] = (normalized - mean) / standardDeviation;
            }
        }
        return new Result(zScores, percentiles, finiteCount);
    }

    private static int countFinite(double[] values) {
        int count = 0;
        for (double value : values) {
            if (Double.isFinite(value)) count++;
        }
        return count;
    }

    private static double[] nanArray(int length) {
        double[] values = new double[length];
        Arrays.fill(values, Double.NaN);
        return values;
    }

    private static double normalizeZero(double value) {
        return value == 0.0 ? 0.0 : value;
    }

    private static int lowerBound(double[] sorted, double target) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (sorted[middle] < target) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static int upperBound(double[] sorted, double target) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (sorted[middle] <= target) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }
}
