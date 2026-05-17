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

    public OC3DPlusParameters(int threshold,
                              int minSize,
                              int maxSize,
                              boolean excludeOnEdges,
                              List<MorphPredicate> morphPredicates,
                              ImagePlus intensityImage,
                              WarningSink warningSink) {
        this.threshold = threshold;
        this.minSize = Math.max(0, minSize);
        this.maxSize = Math.max(this.minSize, maxSize);
        this.excludeOnEdges = excludeOnEdges;
        this.morphPredicates = immutableCopy(morphPredicates);
        this.intensityImage = intensityImage;
        this.warningSink = warningSink == null ? NO_OP_WARNING_SINK : warningSink;
    }

    private static List<MorphPredicate> immutableCopy(List<MorphPredicate> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<MorphPredicate>(source));
    }
}
