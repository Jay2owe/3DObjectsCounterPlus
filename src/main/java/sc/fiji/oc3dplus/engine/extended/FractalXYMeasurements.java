package sc.fiji.oc3dplus.engine.extended;

import java.util.ArrayList;
import java.util.List;

/**
 * Box-counting dimension and gliding-box lacunarity of an object's XY union
 * projection.
 */
public final class FractalXYMeasurements {

    private static final int[] RADII_PX = {1, 2, 4, 8, 16, 32, 64};
    private static final int MIN_BOUND_PX = 8;
    private static final int MIN_FOREGROUND_PIXELS = 32;
    private static final int MIN_REGRESSION_SCALES = 4;
    private static final double MIN_RELIABLE_R_SQUARED = 0.9;

    private FractalXYMeasurements() {
    }

    public static int[] supportedRadiiPx() {
        return RADII_PX.clone();
    }

    public static Result compute(ObjectMask3D objectMask) {
        if (objectMask == null) {
            throw new IllegalArgumentException("objectMask must not be null");
        }
        int objectWidth = objectMask.boundsWidth();
        int objectHeight = objectMask.boundsHeight();
        if (objectWidth < MIN_BOUND_PX || objectHeight < MIN_BOUND_PX) {
            return Result.invalid();
        }

        byte[] projection = objectMask.tightXyProjection();
        if (foregroundCount(projection) < MIN_FOREGROUND_PIXELS) {
            return Result.invalid();
        }
        int pad = (int) Math.ceil(Math.max(objectWidth, objectHeight) / 4.0);
        int paddedWidth = objectWidth + 2 * pad;
        int paddedHeight = objectHeight + 2 * pad;
        byte[] binary = new byte[paddedWidth * paddedHeight];
        for (int y = 0; y < objectHeight; y++) {
            for (int x = 0; x < objectWidth; x++) {
                if (projection[y * objectWidth + x] != 0) {
                    binary[(y + pad) * paddedWidth + x + pad] = 1;
                }
            }
        }

        List<Double> logInverseSize = new ArrayList<Double>();
        List<Double> logBoxCount = new ArrayList<Double>();
        List<Double> lacunarities = new ArrayList<Double>();
        int maximumObjectSide = Math.max(objectWidth, objectHeight);
        int minimumPaddedSide = Math.min(paddedWidth, paddedHeight);

        for (int i = 0; i < RADII_PX.length; i++) {
            int radius = RADII_PX[i];
            if (radius <= maximumObjectSide) {
                int count = countOccupiedBoxes(
                        binary, paddedWidth, paddedHeight, radius, pad, pad);
                if (count > 0) {
                    logInverseSize.add(Double.valueOf(Math.log(1.0 / radius)));
                    logBoxCount.add(Double.valueOf(Math.log(count)));
                }
            }
            if (radius <= maximumObjectSide && radius <= minimumPaddedSide) {
                double lacunarity =
                        lacunarity(binary, paddedWidth, paddedHeight, radius);
                if (isFinite(lacunarity)) {
                    lacunarities.add(Double.valueOf(lacunarity));
                }
            }
        }

        if (logInverseSize.size() < MIN_REGRESSION_SCALES) {
            return Result.invalid();
        }
        Regression regression = regress(logInverseSize, logBoxCount);
        if (!isFinite(regression.slope) || !isFinite(regression.rSquared)) {
            return Result.invalid();
        }

        double lacunarityMean = mean(lacunarities);
        double lacunaritySpread = populationSpread(lacunarities, lacunarityMean);
        if (regression.rSquared < MIN_RELIABLE_R_SQUARED) {
            return Result.unreliable(regression.rSquared, logInverseSize.size());
        }
        return new Result(
                regression.slope,
                regression.rSquared,
                lacunarityMean,
                lacunaritySpread,
                logInverseSize.size(),
                true,
                true);
    }

    private static int foregroundCount(byte[] projection) {
        int count = 0;
        for (int i = 0; i < projection.length; i++) {
            if (projection[i] != 0) count++;
        }
        return count;
    }

    private static int countOccupiedBoxes(byte[] binary,
                                          int width,
                                          int height,
                                          int radius,
                                          int originX,
                                          int originY) {
        int count = 0;
        int startX = Math.floorMod(originX, radius);
        int startY = Math.floorMod(originY, radius);
        for (int y = startY; y < height; y += radius) {
            for (int x = startX; x < width; x += radius) {
                if (boxHasForeground(binary, width, height, x, y, radius)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean boxHasForeground(byte[] binary,
                                            int width,
                                            int height,
                                            int xStart,
                                            int yStart,
                                            int radius) {
        int xEnd = Math.min(width, xStart + radius);
        int yEnd = Math.min(height, yStart + radius);
        for (int y = yStart; y < yEnd; y++) {
            for (int x = xStart; x < xEnd; x++) {
                if (binary[y * width + x] != 0) return true;
            }
        }
        return false;
    }

    private static double lacunarity(byte[] binary,
                                     int width,
                                     int height,
                                     int radius) {
        int[] integral = integralImage(binary, width, height);
        int count = 0;
        double massSum = 0.0;
        double squaredMassSum = 0.0;
        for (int y = 0; y <= height - radius; y++) {
            for (int x = 0; x <= width - radius; x++) {
                int mass = rectangleSum(
                        integral, width + 1, x, y, x + radius, y + radius);
                massSum += mass;
                squaredMassSum += (double) mass * (double) mass;
                count++;
            }
        }
        if (count == 0) return Double.NaN;
        double meanMass = massSum / count;
        if (meanMass <= 0.0) return Double.NaN;
        double variance = Math.max(
                0.0, squaredMassSum / count - meanMass * meanMass);
        return variance / (meanMass * meanMass) + 1.0;
    }

    private static int[] integralImage(byte[] binary, int width, int height) {
        int stride = width + 1;
        int[] integral = new int[stride * (height + 1)];
        for (int y = 1; y <= height; y++) {
            int rowSum = 0;
            for (int x = 1; x <= width; x++) {
                if (binary[(y - 1) * width + x - 1] != 0) rowSum++;
                integral[y * stride + x] =
                        integral[(y - 1) * stride + x] + rowSum;
            }
        }
        return integral;
    }

    private static int rectangleSum(int[] integral,
                                    int stride,
                                    int xStart,
                                    int yStart,
                                    int xEnd,
                                    int yEnd) {
        return integral[yEnd * stride + xEnd]
                - integral[yStart * stride + xEnd]
                - integral[yEnd * stride + xStart]
                + integral[yStart * stride + xStart];
    }

    private static Regression regress(List<Double> xValues,
                                      List<Double> yValues) {
        double meanX = mean(xValues);
        double meanY = mean(yValues);
        double sumSquaredX = 0.0;
        double sumProduct = 0.0;
        double totalSquaredY = 0.0;
        for (int i = 0; i < xValues.size(); i++) {
            double deltaX = xValues.get(i).doubleValue() - meanX;
            double deltaY = yValues.get(i).doubleValue() - meanY;
            sumSquaredX += deltaX * deltaX;
            sumProduct += deltaX * deltaY;
            totalSquaredY += deltaY * deltaY;
        }
        if (sumSquaredX <= 0.0 || totalSquaredY <= 0.0) {
            return Regression.invalid();
        }

        double slope = sumProduct / sumSquaredX;
        double intercept = meanY - slope * meanX;
        double residualSquared = 0.0;
        for (int i = 0; i < xValues.size(); i++) {
            double predicted =
                    intercept + slope * xValues.get(i).doubleValue();
            double residual = yValues.get(i).doubleValue() - predicted;
            residualSquared += residual * residual;
        }
        double rSquared = 1.0 - residualSquared / totalSquaredY;
        if (rSquared < 0.0 && rSquared > -1.0e-12) rSquared = 0.0;
        if (rSquared > 1.0 && rSquared < 1.0 + 1.0e-12) rSquared = 1.0;
        return new Regression(slope, rSquared);
    }

    private static double mean(List<Double> values) {
        if (values == null || values.isEmpty()) return Double.NaN;
        double sum = 0.0;
        for (int i = 0; i < values.size(); i++) {
            sum += values.get(i).doubleValue();
        }
        return sum / values.size();
    }

    private static double populationSpread(List<Double> values, double mean) {
        if (values == null || values.isEmpty() || !isFinite(mean)) {
            return Double.NaN;
        }
        double squaredDifferenceSum = 0.0;
        for (int i = 0; i < values.size(); i++) {
            double difference = values.get(i).doubleValue() - mean;
            squaredDifferenceSum += difference * difference;
        }
        return Math.sqrt(squaredDifferenceSum / values.size());
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    public static final class Result {
        private final double fractalDimension;
        private final double rSquared;
        private final double lacunarityMean;
        private final double lacunaritySpread;
        private final int regressionScaleCount;
        private final boolean valid;
        private final boolean reliable;

        private Result(double fractalDimension,
                       double rSquared,
                       double lacunarityMean,
                       double lacunaritySpread,
                       int regressionScaleCount,
                       boolean valid,
                       boolean reliable) {
            this.fractalDimension = fractalDimension;
            this.rSquared = rSquared;
            this.lacunarityMean = lacunarityMean;
            this.lacunaritySpread = lacunaritySpread;
            this.regressionScaleCount = regressionScaleCount;
            this.valid = valid;
            this.reliable = reliable;
        }

        private static Result invalid() {
            return new Result(
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    0,
                    false,
                    false);
        }

        private static Result unreliable(double rSquared, int regressionScaleCount) {
            return new Result(
                    Double.NaN,
                    rSquared,
                    Double.NaN,
                    Double.NaN,
                    regressionScaleCount,
                    false,
                    false);
        }

        public double fractalDimension() {
            return fractalDimension;
        }

        public double rSquared() {
            return rSquared;
        }

        public double lacunarityMean() {
            return lacunarityMean;
        }

        public double lacunaritySpread() {
            return lacunaritySpread;
        }

        public int regressionScaleCount() {
            return regressionScaleCount;
        }

        public boolean isValid() {
            return valid;
        }

        public boolean isReliable() {
            return reliable;
        }
    }

    private static final class Regression {
        final double slope;
        final double rSquared;

        private Regression(double slope, double rSquared) {
            this.slope = slope;
            this.rSquared = rSquared;
        }

        static Regression invalid() {
            return new Regression(Double.NaN, Double.NaN);
        }
    }
}
