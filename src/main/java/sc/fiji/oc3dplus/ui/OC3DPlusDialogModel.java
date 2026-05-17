package sc.fiji.oc3dplus.ui;

import ij.ImagePlus;
import ij.measure.Calibration;
import sc.fiji.oc3dplus.MacroOptionsParser;
import sc.fiji.oc3dplus.api.MorphPredicate;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusParameters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mutable, Swing-free data model for the 3D Objects Counter+ dialog. Holds
 * the user's current selections, validates them, and produces an
 * {@link OC3DPlusParameters} or an equivalent macro-options string. The
 * actual Swing rendering lives in {@link OC3DPlusDialog} but always reads
 * and writes through this model so the logic is unit-testable.
 */
public final class OC3DPlusDialogModel {

    public static final class FilterRow {
        public String feature;
        public String operator;
        public double value;
        public boolean enabled;

        public FilterRow(String feature, String operator, double value, boolean enabled) {
            this.feature = feature == null ? "sphericity" : feature;
            this.operator = operator == null ? ">=" : operator;
            this.value = value;
            this.enabled = enabled;
        }

        FilterRow copy() {
            return new FilterRow(feature, operator, value, enabled);
        }
    }

    public static final class FeatureRange {
        public final String feature;
        public final String label;
        public final String minDefault;
        public final String maxDefault;
        public final double hardMin;
        public final double hardMax;
        public String minText;
        public String maxText;

        FeatureRange(String feature,
                     String label,
                     String minDefault,
                     String maxDefault,
                     double hardMin,
                     double hardMax) {
            this.feature = feature;
            this.label = label;
            this.minDefault = minDefault;
            this.maxDefault = maxDefault;
            this.hardMin = hardMin;
            this.hardMax = hardMax;
            this.minText = minDefault;
            this.maxText = maxDefault;
        }

        FeatureRange copy() {
            FeatureRange copy = new FeatureRange(feature, label, minDefault, maxDefault, hardMin, hardMax);
            copy.minText = minText;
            copy.maxText = maxText;
            return copy;
        }
    }

    public int threshold = 128;
    public int minSize = 10;
    /** {@link Integer#MAX_VALUE} represents "Infinity" in the UI. */
    public int maxSize = Integer.MAX_VALUE;
    public boolean excludeOnEdges = false;
    public boolean showLabels = true;
    public boolean showSurfaces = true;
    public boolean showCentroids = true;
    public boolean showCentersOfMass = true;
    public boolean showStats = true;
    public boolean showSummary = true;
    /** Empty string = no redirect. */
    public String redirectTitle = "";
    private final List<FeatureRange> featureRanges = defaultFeatureRanges();
    private final List<FilterRow> filters = new ArrayList<FilterRow>();

    public List<FeatureRange> featureRanges() {
        return featureRanges;
    }

    public void configureForImage(ImagePlus image) {
        featureRanges.clear();
        featureRanges.addAll(defaultFeatureRanges(calibratedVolumeUnit(image)));
    }

    public List<FilterRow> filters() {
        return filters;
    }

    public void addFilter(FilterRow row) {
        if (row != null) filters.add(row);
    }

    public void removeFilter(int index) {
        if (index >= 0 && index < filters.size()) filters.remove(index);
    }

    public List<MorphPredicate> enabledPredicates() {
        List<MorphPredicate> out = new ArrayList<MorphPredicate>();
        for (FeatureRange range : featureRanges) {
            if (range == null) continue;
            double min = parseRangeBound(range.minText, range.label + " minimum");
            double max = parseRangeBound(range.maxText, range.label + " maximum");
            double defaultMin = parseRangeBound(range.minDefault, range.label + " default minimum");
            double defaultMax = parseRangeBound(range.maxDefault, range.label + " default maximum");
            if (Double.isFinite(min) && Double.compare(min, defaultMin) != 0) {
                out.add(new MorphPredicate(range.feature, MorphPredicate.Operator.GE, min));
            }
            if (Double.isFinite(max) && Double.compare(max, defaultMax) != 0) {
                out.add(new MorphPredicate(range.feature, MorphPredicate.Operator.LE, max));
            }
        }
        for (FilterRow row : filters) {
            if (row == null || !row.enabled) continue;
            out.add(new MorphPredicate(row.feature, operatorFrom(row.operator), row.value));
        }
        return out;
    }

    /**
     * Returns an empty list when the model is valid, otherwise human-readable
     * error messages.
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<String>();
        if (minSize < 0) errors.add("Min size must be >= 0 (minSize=" + minSize + ").");
        if (maxSize < minSize) {
            errors.add("Max size (" + (maxSize == Integer.MAX_VALUE ? "Infinity" : maxSize)
                    + ") must be >= min size (" + minSize + ").");
        }
        if (redirectTitle != null && !redirectTitle.isEmpty()
                && !MacroOptionsParser.isSafeBracketedValue(redirectTitle)) {
            errors.add("Redirect image title cannot contain [, ], quotes, backslashes, or line breaks "
                    + "(redirectTitle='" + redirectTitle + "'). "
                    + "Rename the image and try again.");
        }
        for (FeatureRange range : featureRanges) {
            if (range == null) continue;
            try {
                double min = parseRangeBound(range.minText, range.label + " minimum");
                double max = parseRangeBound(range.maxText, range.label + " maximum");
                if (min == Double.POSITIVE_INFINITY) {
                    errors.add(range.label + ": minimum cannot be Infinity.");
                }
                if (max == Double.NEGATIVE_INFINITY) {
                    errors.add(range.label + ": maximum cannot be -Infinity.");
                }
                if (min > max) {
                    errors.add(range.label + ": minimum must be <= maximum "
                            + "(min=" + range.minText + ", max=" + range.maxText + ").");
                }
                if (min < range.hardMin) {
                    errors.add(range.label + ": minimum must be >= " + formatBound(range.hardMin)
                            + " (min=" + range.minText + ").");
                }
                if (max > range.hardMax) {
                    errors.add(range.label + ": maximum must be <= " + formatBound(range.hardMax)
                            + " (max=" + range.maxText + ").");
                }
            } catch (IllegalArgumentException invalidRange) {
                errors.add(invalidRange.getMessage());
            }
        }
        for (int i = 0; i < filters.size(); i++) {
            FilterRow row = filters.get(i);
            if (row == null || !row.enabled) continue;
            if (row.feature == null || row.feature.trim().isEmpty()) {
                errors.add("Filter " + (i + 1) + ": feature must not be blank "
                        + "(feature='" + row.feature + "').");
            }
            if (row.operator == null || !isOperator(row.operator)) {
                errors.add("Filter " + (i + 1) + ": operator must be one of >=, <=, >, < "
                        + "(operator='" + row.operator + "').");
            }
            if (!Double.isFinite(row.value)) {
                errors.add("Filter " + (i + 1) + ": value must be a finite number "
                        + "(value=" + row.value + ").");
            }
        }
        return errors;
    }

    /** Produces the parameter bundle the engine consumes. */
    public OC3DPlusParameters toParameters(ij.ImagePlus intensityImage,
                                           OC3DPlusParameters.WarningSink warningSink) {
        OC3DPlus.Builder builder = OC3DPlus.builder()
                .threshold(threshold)
                .minSize(minSize)
                .maxSize(maxSize)
                .excludeOnEdges(excludeOnEdges)
                .intensityImage(intensityImage)
                .warningSink(warningSink);
        for (MorphPredicate predicate : enabledPredicates()) {
            builder.addFilter(predicate);
        }
        return builder.build();
    }

    /**
     * Produces a macro-options string equivalent to the current model state.
     * The macro recorder uses this so the user's interactive choices can be
     * replayed via {@code run("3D Objects Counter+", ...)}.
     */
    public String toMacroOptions() {
        StringBuilder sb = new StringBuilder();
        sb.append("threshold=").append(threshold);
        sb.append(" min=").append(minSize);
        sb.append(" max=").append(maxSize == Integer.MAX_VALUE ? "Infinity" : Integer.toString(maxSize));
        if (excludeOnEdges) sb.append(" exclude_edges");
        if (redirectTitle != null && !redirectTitle.isEmpty()) {
            sb.append(" redirect=[")
                    .append(MacroOptionsParser.requireSafeBracketedValue(
                            redirectTitle, "Redirect image title"))
                    .append(']');
        }
        for (MorphPredicate predicate : enabledPredicates()) {
            sb.append(' ').append(predicate.format());
        }
        if (!showLabels) sb.append(" hide_labels");
        if (!showSurfaces) sb.append(" hide_surfaces");
        if (!showCentroids) sb.append(" hide_centroids");
        if (!showCentersOfMass) sb.append(" hide_centers_of_mass");
        if (!showStats) sb.append(" hide_stats");
        if (!showSummary) sb.append(" hide_summary");
        return sb.toString();
    }

    /** Reset the model to the supplied snapshot (used for Cancel-style reverts). */
    public void copyFrom(OC3DPlusDialogModel other) {
        if (other == null) return;
        this.threshold = other.threshold;
        this.minSize = other.minSize;
        this.maxSize = other.maxSize;
        this.excludeOnEdges = other.excludeOnEdges;
        this.showLabels = other.showLabels;
        this.showSurfaces = other.showSurfaces;
        this.showCentroids = other.showCentroids;
        this.showCentersOfMass = other.showCentersOfMass;
        this.showStats = other.showStats;
        this.showSummary = other.showSummary;
        this.redirectTitle = other.redirectTitle == null ? "" : other.redirectTitle;
        this.featureRanges.clear();
        for (FeatureRange range : other.featureRanges) {
            this.featureRanges.add(range.copy());
        }
        this.filters.clear();
        for (FilterRow row : other.filters) {
            this.filters.add(row.copy());
        }
    }

    public OC3DPlusDialogModel snapshot() {
        OC3DPlusDialogModel copy = new OC3DPlusDialogModel();
        copy.copyFrom(this);
        return copy;
    }

    static boolean isOperator(String symbol) {
        return ">=".equals(symbol) || "<=".equals(symbol)
                || ">".equals(symbol) || "<".equals(symbol);
    }

    static MorphPredicate.Operator operatorFrom(String symbol) {
        if ("<=".equals(symbol)) return MorphPredicate.Operator.LE;
        if (">".equals(symbol)) return MorphPredicate.Operator.GT;
        if ("<".equals(symbol)) return MorphPredicate.Operator.LT;
        return MorphPredicate.Operator.GE;
    }

    /** Public helper for the dialog's feature dropdown. */
    public static List<String> featureOptions() {
        return Collections.unmodifiableList(java.util.Arrays.asList(
                "sphericity",
                "compactness",
                "elongation",
                "volume",
                "volume_calibrated",
                "surface_area",
                "mean_intensity",
                "max_intensity",
                "feret_diameter_max"));
    }

    public static List<String> operatorOptions() {
        return Collections.unmodifiableList(java.util.Arrays.asList(">=", "<=", ">", "<"));
    }

    static double parseRangeBound(String text, String fieldName) {
        String label = fieldName == null || fieldName.trim().isEmpty() ? "Range bound" : fieldName;
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank.");
        }
        if ("infinity".equalsIgnoreCase(value) || "+infinity".equalsIgnoreCase(value)
                || "inf".equalsIgnoreCase(value) || "+inf".equalsIgnoreCase(value)) {
            return Double.POSITIVE_INFINITY;
        }
        if ("-infinity".equalsIgnoreCase(value) || "-inf".equalsIgnoreCase(value)) {
            return Double.NEGATIVE_INFINITY;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (Double.isNaN(parsed)) {
                throw new IllegalArgumentException(label + " must not be NaN.");
            }
            return parsed;
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException(label + " must be a number or Infinity "
                    + "(value='" + text + "').", nfe);
        }
    }

    private static String formatBound(double value) {
        if (value == Double.POSITIVE_INFINITY) return "Infinity";
        if (value == Double.NEGATIVE_INFINITY) return "-Infinity";
        if (value == Math.rint(value)) return Long.toString(Math.round(value));
        return Double.toString(value);
    }

    private static List<FeatureRange> defaultFeatureRanges() {
        return defaultFeatureRanges(null);
    }

    private static List<FeatureRange> defaultFeatureRanges(String calibratedVolumeUnit) {
        List<FeatureRange> ranges = new ArrayList<FeatureRange>();
        ranges.add(new FeatureRange("sphericity", "Sphericity", "0", "1", 0, 1));
        ranges.add(new FeatureRange("compactness", "Compactness", "0", "1", 0, 1));
        ranges.add(new FeatureRange("elongation", "Elongation", "1", "Infinity", 1, Double.POSITIVE_INFINITY));
        if (calibratedVolumeUnit != null) {
            ranges.add(new FeatureRange("volume_calibrated",
                    "Volume (" + calibratedVolumeUnit + "^3)",
                    "0", "Infinity", 0, Double.POSITIVE_INFINITY));
        }
        ranges.add(new FeatureRange("surface_area", "Surface area", "0", "Infinity", 0, Double.POSITIVE_INFINITY));
        ranges.add(new FeatureRange("mean_intensity", "Mean intensity", "0", "Infinity",
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY));
        ranges.add(new FeatureRange("max_intensity", "Max intensity", "0", "Infinity",
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY));
        ranges.add(new FeatureRange("feret_diameter_max", "Max Feret diameter", "0", "Infinity",
                0, Double.POSITIVE_INFINITY));
        return ranges;
    }

    static String calibratedVolumeUnit(ImagePlus image) {
        if (image == null) return null;
        Calibration cal = image.getCalibration();
        if (cal == null || !hasActualSpatialUnit(cal)) return null;
        double width = cal.pixelWidth;
        double height = cal.pixelHeight;
        double depth = cal.pixelDepth;
        if (!Double.isFinite(width) || !Double.isFinite(height) || !Double.isFinite(depth)
                || width <= 0.0 || height <= 0.0 || depth <= 0.0) {
            return null;
        }
        String unit = cal.getUnit() == null ? "" : cal.getUnit().trim();
        return unit.length() == 0 ? null : unit;
    }

    private static boolean hasActualSpatialUnit(Calibration cal) {
        if (cal == null) return false;
        String unit = cal.getUnit();
        if (unit == null) return false;
        String normalized = unit.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.length() > 0
                && !"pixel".equals(normalized)
                && !"pixels".equals(normalized)
                && !"px".equals(normalized);
    }
}
