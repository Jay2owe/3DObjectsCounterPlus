package sc.fiji.oc3dplus.batch;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Explicit allowlist for within-batch descriptive scoring. Coordinates,
 * labels, text, and intensity measurements are intentionally excluded.
 */
public final class ScoreFeatureCatalog {

    public static final String VOLUME = "Volume";
    public static final String SURFACE = "Surface";

    private static final Set<String> FEATURES = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    VOLUME,
                    SURFACE,
                    "Nb of obj. voxels",
                    "Nb of surf. voxels",
                    "Morph_Sphericity",
                    "Morph_Compactness",
                    "Morph_Elongation",
                    "Morph_Feret3D_um",
                    "Morph_FractalDim_XY",
                    "Morph_LacunarityMean_XY",
                    "Morph_LacunaritySpread_XY",
                    "Morph_RI",
                    "Morph_SRI",
                    "Morph_PB",
                    "Morph_MP",
                    "Morph_VSD",
                    "Morph_ShollCriticalRadius_um",
                    "Morph_ShollCriticalIntersections",
                    "Morph_ShollSchoenenIndex",
                    "Morph_ShollPrimaryBranches",
                    "Morph_SkeletonBranches",
                    "Morph_SkeletonJunctions",
                    "Morph_SkeletonEndpoints",
                    "Morph_SkeletonVoxels")));

    private ScoreFeatureCatalog() {}

    public static Set<String> features() {
        return FEATURES;
    }

    public static boolean isScoreable(String heading) {
        return canonicalFeature(heading) != null;
    }

    /**
     * Maps dynamic calibrated headings to stable score feature names.
     * The original heading remains present in {@code batch_objects.csv}.
     */
    public static String canonicalFeature(String heading) {
        if (heading == null) return null;
        if (heading.startsWith("Volume (") && heading.endsWith("^3)")) {
            return VOLUME;
        }
        if (heading.startsWith("Surface (") && heading.endsWith("^2)")) {
            return SURFACE;
        }
        return FEATURES.contains(heading) ? heading : null;
    }

    /** Power of length requiring conversion to the common micrometre unit. */
    public static int physicalDimensionPower(String feature) {
        if (VOLUME.equals(feature)) return 3;
        if (SURFACE.equals(feature)) return 2;
        if ("Morph_Feret3D_um".equals(feature)) return 1;
        return 0;
    }

    public static String scoringUnit(String feature) {
        int power = physicalDimensionPower(feature);
        if (power == 1) return "um";
        if (power == 2) return "um^2";
        if (power == 3) return "um^3";
        if ("Morph_ShollCriticalRadius_um".equals(feature)) return "um";
        if ("Nb of obj. voxels".equals(feature)
                || "Nb of surf. voxels".equals(feature)
                || "Morph_SkeletonVoxels".equals(feature)) {
            return "voxel";
        }
        if (feature != null && (feature.contains("Branches")
                || feature.contains("Junctions")
                || feature.contains("Endpoints")
                || feature.contains("Intersections"))) {
            return "count";
        }
        return "unitless";
    }
}
