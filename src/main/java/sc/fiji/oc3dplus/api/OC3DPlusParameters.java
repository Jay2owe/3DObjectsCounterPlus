package sc.fiji.oc3dplus.api;

import ij.ImagePlus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable parameter bundle for one run of the 3D Objects Counter+ engine.
 *
 * <p>Use {@link sc.fiji.oc3dplus.api.OC3DPlus#builder()} to construct one
 * fluently; the constructor here is also public for direct use.
 */
public final class OC3DPlusParameters {

    /** Sink for non-fatal engine warnings (e.g. an unsupported feature name). */
    public interface WarningSink {
        void warn(String message);
    }

    public static final WarningSink NO_OP_WARNING_SINK = new WarningSink() {
        @Override public void warn(String message) {
        }
    };

    /** Intensity threshold. Voxels strictly below this are zeroed before labelling. */
    public final int threshold;
    /** Minimum object size in voxels. */
    public final int minSize;
    /** Maximum object size in voxels. */
    public final int maxSize;
    /** Whether to exclude objects touching the image edges. */
    public final boolean excludeOnEdges;
    /** Morphology predicates; objects must pass <em>all</em> to survive. */
    public final List<MorphPredicate> morphPredicates;
    /** Optional intensity-measurement source (a.k.a. "redirect"). May be null. */
    public final ImagePlus intensityImage;
    /** Non-fatal warning sink. Never null. */
    public final WarningSink warningSink;
    /** Optional extended measurements. Never null. */
    public final OC3DPlusMeasurements measurements;
    /**
     * Which channel of a multichannel input to measure, 1-based.
     *
     * <p>{@link #USE_CURRENT_POSITION} means "whatever the image is showing",
     * which is the default and is what a plain 3D stack always resolves to.
     *
     * <p>This exists because a channel is a separate signal: connecting objects
     * across channels, or measuring one channel's intensities inside another's
     * objects, is not a 3D measurement. Earlier versions had no such setting and
     * silently measured only the first {@code nSlices} planes of a hyperstack's
     * stack - for a 2-channel 101-frame timelapse, one plane out of 202.
     */
    public final int channel;
    /**
     * Which frame (time point) of a timelapse input to measure, 1-based.
     *
     * @see #channel
     */
    public final int frame;

    /** Resolve the channel or frame from the image's own current position. */
    public static final int USE_CURRENT_POSITION = 0;

    public OC3DPlusParameters(int threshold,
                              int minSize,
                              int maxSize,
                              boolean excludeOnEdges,
                              List<MorphPredicate> morphPredicates,
                              ImagePlus intensityImage,
                              WarningSink warningSink) {
        this(threshold, minSize, maxSize, excludeOnEdges, morphPredicates,
                intensityImage, warningSink, OC3DPlusMeasurements.NONE);
    }

    public OC3DPlusParameters(int threshold,
                              int minSize,
                              int maxSize,
                              boolean excludeOnEdges,
                              List<MorphPredicate> morphPredicates,
                              ImagePlus intensityImage,
                              WarningSink warningSink,
                              OC3DPlusMeasurements measurements) {
        this(threshold, minSize, maxSize, excludeOnEdges, morphPredicates,
                intensityImage, warningSink, measurements,
                USE_CURRENT_POSITION, USE_CURRENT_POSITION);
    }

    public OC3DPlusParameters(int threshold,
                              int minSize,
                              int maxSize,
                              boolean excludeOnEdges,
                              List<MorphPredicate> morphPredicates,
                              ImagePlus intensityImage,
                              WarningSink warningSink,
                              OC3DPlusMeasurements measurements,
                              int channel,
                              int frame) {
        this.threshold = threshold;
        this.minSize = Math.max(0, minSize);
        this.maxSize = Math.max(this.minSize, maxSize);
        this.excludeOnEdges = excludeOnEdges;
        this.morphPredicates = immutableCopy(morphPredicates);
        this.intensityImage = intensityImage;
        this.warningSink = warningSink == null ? NO_OP_WARNING_SINK : warningSink;
        this.measurements = measurements == null ? OC3DPlusMeasurements.NONE : measurements;
        this.channel = Math.max(USE_CURRENT_POSITION, channel);
        this.frame = Math.max(USE_CURRENT_POSITION, frame);
    }

    private static List<MorphPredicate> immutableCopy(List<MorphPredicate> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<MorphPredicate>(source));
    }
}
