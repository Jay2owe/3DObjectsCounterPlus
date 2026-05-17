package sc.fiji.oc3dplus.engine;

import ij.IJ;
import ij.measure.ResultsTable;
import sc.fiji.oc3dplus.api.OC3DPlusResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Formats the native-style summary line shown by 3D Objects Counter. */
public final class SummaryReporter {

    private SummaryReporter() {}

    public static void log(String imageTitle,
                           OC3DPlusResult result,
                           int minSize,
                           int maxSize,
                           int threshold) {
        IJ.log(format(imageTitle, result, minSize, maxSize, threshold));
    }

    public static void log(String imageTitle,
                           String redirectTitle,
                           OC3DPlusResult result,
                           int minSize,
                           int maxSize,
                           int threshold) {
        IJ.log(format(imageTitle, redirectTitle, result, minSize, maxSize, threshold));
    }

    public static String format(String imageTitle,
                                OC3DPlusResult result,
                                int minSize,
                                int maxSize,
                                int threshold) {
        return format(imageTitle, null, result, minSize, maxSize, threshold);
    }

    public static String format(String imageTitle,
                                String redirectTitle,
                                OC3DPlusResult result,
                                int minSize,
                                int maxSize,
                                int threshold) {
        String title = measurementSubject(imageTitle, redirectTitle);
        int objectCount = result == null ? 0 : result.objectCount();
        String line = title + ": " + objectCount + " objects detected (Size filter set to "
                + minSize + "-" + formatMaxSize(maxSize)
                + " voxels, threshold set to: " + threshold + ").";
        String morphSummary = morphologySummary(result == null ? null : result.statistics());
        return morphSummary.isEmpty() ? line : line + " " + morphSummary;
    }

    static String measurementSubject(String imageTitle, String redirectTitle) {
        String title = safeTitle(imageTitle);
        String redirect = safeTitleOrEmpty(redirectTitle);
        return redirect.isEmpty() ? title : title + " redirect to " + redirect;
    }

    private static String safeTitle(String title) {
        return title == null || title.isEmpty() ? "<untitled>" : title;
    }

    private static String safeTitleOrEmpty(String title) {
        return title == null || title.isEmpty() ? "" : title;
    }

    private static String formatMaxSize(int maxSize) {
        return maxSize == Integer.MAX_VALUE ? "Infinity" : Integer.toString(maxSize);
    }

    private static String morphologySummary(ResultsTable stats) {
        if (stats == null || stats.size() == 0) return "";
        List<String> values = new ArrayList<String>();
        addMean(values, stats, "Nb of obj. voxels", "Size");
        addMean(values, stats, firstHeadingStartingWith(stats, "Volume ("), "Volume");
        addMean(values, stats, firstHeadingStartingWith(stats, "Surface ("), "Surface area");
        addMean(values, stats, "Morph_Sphericity", "Sphericity");
        addMean(values, stats, "Morph_Compactness", "Compactness");
        addMean(values, stats, "Morph_Elongation", "Elongation");
        addMean(values, stats, "Mean", "Mean intensity");
        addMean(values, stats, "Max", "Max intensity");
        addMean(values, stats, "Morph_Feret3D_um", "Max Feret diameter");
        return values.isEmpty() ? "" : "Morphology means: " + join(values) + ".";
    }

    private static void addMean(List<String> values,
                                ResultsTable stats,
                                String column,
                                String label) {
        if (values == null || stats == null || column == null || label == null) return;
        if (stats.getColumnIndex(column) < 0) return;
        double sum = 0.0;
        int count = 0;
        for (int row = 0; row < stats.size(); row++) {
            try {
                double value = stats.getValue(column, row);
                if (Double.isFinite(value)) {
                    sum += value;
                    count++;
                }
            } catch (RuntimeException unreadableCell) {
                // Sparse ResultsTable columns can miss cells; skip those rows.
            }
        }
        if (count > 0) {
            values.add(label + "=" + formatDouble(sum / count));
        }
    }

    private static String firstHeadingStartingWith(ResultsTable stats, String prefix) {
        if (stats == null || prefix == null) return null;
        String[] headings = stats.getHeadings();
        if (headings == null) return null;
        for (int i = 0; i < headings.length; i++) {
            String heading = headings[i];
            if (heading != null && heading.startsWith(prefix)) {
                return heading;
            }
        }
        return null;
    }

    private static String formatDouble(double value) {
        if (!Double.isFinite(value)) return "NaN";
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.3f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(values.get(i));
        }
        return out.toString();
    }
}
