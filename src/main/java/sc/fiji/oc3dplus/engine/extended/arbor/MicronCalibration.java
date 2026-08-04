package sc.fiji.oc3dplus.engine.extended.arbor;

import ij.measure.Calibration;

import java.util.Locale;

final class MicronCalibration {

    final double pixelWidthUm;
    final double pixelHeightUm;
    final double pixelDepthUm;
    final boolean available;

    private MicronCalibration(double pixelWidthUm,
                              double pixelHeightUm,
                              double pixelDepthUm,
                              boolean available) {
        this.pixelWidthUm = pixelWidthUm;
        this.pixelHeightUm = pixelHeightUm;
        this.pixelDepthUm = pixelDepthUm;
        this.available = available;
    }

    static MicronCalibration from(Calibration calibration) {
        if (calibration == null) {
            return unavailable();
        }
        double factor = micrometresPerUnit(calibration.getUnit());
        if (!Double.isFinite(factor)) {
            return unavailable();
        }
        double width = calibration.pixelWidth * factor;
        double height = calibration.pixelHeight * factor;
        double depth = calibration.pixelDepth * factor;
        if (!positiveFinite(width) || !positiveFinite(height) || !positiveFinite(depth)) {
            return unavailable();
        }
        return new MicronCalibration(width, height, depth, true);
    }

    static MicronCalibration unavailable() {
        return new MicronCalibration(Double.NaN, Double.NaN, Double.NaN, false);
    }

    private static double micrometresPerUnit(String unit) {
        if (unit == null) {
            return Double.NaN;
        }
        String normalized = unit.trim().toLowerCase(Locale.ROOT)
                .replace('\u00b5', 'u')
                .replace('\u03bc', 'u');
        if ("um".equals(normalized)
                || "micron".equals(normalized)
                || "microns".equals(normalized)
                || "micrometer".equals(normalized)
                || "micrometers".equals(normalized)
                || "micrometre".equals(normalized)
                || "micrometres".equals(normalized)) {
            return 1.0;
        }
        if ("nm".equals(normalized)
                || "nanometer".equals(normalized)
                || "nanometers".equals(normalized)
                || "nanometre".equals(normalized)
                || "nanometres".equals(normalized)) {
            return 0.001;
        }
        if ("mm".equals(normalized)
                || "millimeter".equals(normalized)
                || "millimeters".equals(normalized)
                || "millimetre".equals(normalized)
                || "millimetres".equals(normalized)) {
            return 1000.0;
        }
        if ("cm".equals(normalized)) {
            return 10000.0;
        }
        if ("m".equals(normalized)
                || "meter".equals(normalized)
                || "meters".equals(normalized)
                || "metre".equals(normalized)
                || "metres".equals(normalized)) {
            return 1000000.0;
        }
        return Double.NaN;
    }

    private static boolean positiveFinite(double value) {
        return value > 0.0 && Double.isFinite(value);
    }
}
