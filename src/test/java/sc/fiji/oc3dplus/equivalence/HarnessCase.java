package sc.fiji.oc3dplus.equivalence;

/**
 * The three pinned references of the equivalence harness (harness section 2).
 *
 * <p>"The current output" is engine-dependent, so there is no single global
 * reference. {@code OC3DPlusRunner:311} routes to the classic
 * {@code Utilities.Counter3D} only for 8/16-bit, single-channel, single-frame
 * input; everything else falls to mcib3d. Each case therefore pins its own
 * reference and its own comparison strategy.
 */
public enum HarnessCase {

    /**
     * 8/16-bit, one channel, one frame. Reference: the classic
     * {@code Counter3D} path. What the overwhelming majority of users see, and
     * the one that must not move.
     *
     * <p>Compared by <b>exact label equality including numbering</b>.
     * {@code Counter3D} renumbers by ascending provisional id and hands ids out
     * in z-y-x scan order, and {@code StreamingLabeller} holds the same
     * invariant by construction, so this is not an assumption - it was verified
     * voxel-for-voxel by {@code Counter3DOracleTest} on 2026-08-03. Weakening it
     * to a partition comparison would discard the strongest evidence available.
     */
    A(Comparison.EXACT_LABELS),

    /** 32-bit, multichannel or hyperstack input. Reference: the mcib3d path. */
    B(Comparison.PARTITION),

    /** Label-image input via {@code fromLabelImage}. Reference: the mcib3d path. */
    C(Comparison.PARTITION);

    /** How the label image and object maps are compared for a case. */
    public enum Comparison {
        /** Byte-identical label image, numbering included. */
        EXACT_LABELS,
        /**
         * Identical as a partition: the set of voxel-sets must match regardless
         * of which integers name them, because mcib3d numbers objects
         * differently.
         */
        PARTITION
    }

    private final Comparison comparison;

    HarnessCase(Comparison comparison) {
        this.comparison = comparison;
    }

    public Comparison comparison() {
        return comparison;
    }

    public static HarnessCase parse(String text) {
        if ("A".equals(text)) return A;
        if ("B".equals(text)) return B;
        if ("C".equals(text)) return C;
        throw new IllegalArgumentException("Unknown harness case '" + text + "'; expected A, B or C.");
    }
}
