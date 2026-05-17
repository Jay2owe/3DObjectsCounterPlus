package sc.fiji.oc3dplus.api;

import ij.ImagePlus;
import ij.measure.ResultsTable;

/**
 * Result of one call to {@link OC3DPlus#count(ImagePlus, OC3DPlusParameters)}.
 *
 * <p>Holds the label image returned by the engine, the per-object statistics
 * table, and per-filter survivor counts for UI feedback. Filtered
 * {@link OC3DPlus#count(ImagePlus, OC3DPlusParameters)} calls relabel
 * surviving objects consecutively. Detect-only calls may return the native
 * label numbering.
 */
public final class OC3DPlusResult {

    private final ResultsTable statistics;
    private final ImagePlus labelImage;
    private final int[] survivingPerFilter;
    private final String[] filterLabels;
    private final boolean foundObjects;

    public OC3DPlusResult(ResultsTable statistics,
                          ImagePlus labelImage,
                          int[] survivingPerFilter,
                          String[] filterLabels) {
        this.statistics = statistics == null ? new ResultsTable() : statistics;
        this.labelImage = labelImage;
        this.survivingPerFilter = survivingPerFilter == null
                ? new int[0]
                : survivingPerFilter.clone();
        this.filterLabels = filterLabels == null
                ? new String[0]
                : filterLabels.clone();
        this.foundObjects = this.statistics.size() > 0;
    }

    /** Per-object statistics. One row per surviving object. */
    public ResultsTable statistics() {
        return statistics;
    }

    /**
     * Label image: one integer label per surviving object, 0 = background.
     * May be {@code null} if the engine could not create an object map.
     */
    public ImagePlus labelImage() {
        return labelImage;
    }

    /** Number of surviving objects. */
    public int objectCount() {
        return statistics.size();
    }

    /**
     * Per-filter survivor counts. Length = number of filters passed in; entry
     * {@code i} is the count after filter {@code i} (and all earlier filters)
     * were applied. Useful for UI feedback ("sphericity>=0.6: 42 surviving").
     */
    public int[] survivingPerFilter() {
        return survivingPerFilter.clone();
    }

    /** Formatted filter strings (same indexing as {@link #survivingPerFilter}). */
    public String[] filterLabels() {
        return filterLabels.clone();
    }

    /** True if at least one object survived. */
    public boolean foundObjects() {
        return foundObjects;
    }
}
