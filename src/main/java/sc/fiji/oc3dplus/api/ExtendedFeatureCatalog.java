package sc.fiji.oc3dplus.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Stable macro/filter names and result headings for optional measurements. */
public final class ExtendedFeatureCatalog {

    public static final String FRACTAL_DIM_XY = "fractal_dim_xy";
    public static final String FRACTAL_R2_XY = "fractal_r2_xy";
    public static final String LACUNARITY_MEAN_XY = "lacunarity_mean_xy";
    public static final String LACUNARITY_SPREAD_XY = "lacunarity_spread_xy";

    public static final String RI = "ri";
    public static final String SRI = "sri";
    public static final String PB = "pb";
    public static final String MP = "mp";
    public static final String VSD = "vsd";

    public static final String SHOLL_CRITICAL_RADIUS_UM = "sholl_critical_radius_um";
    public static final String SHOLL_CRITICAL_INTERSECTIONS = "sholl_critical_intersections";
    public static final String SHOLL_SCHOENEN_INDEX = "sholl_schoenen_index";
    public static final String SHOLL_PRIMARY_BRANCHES = "sholl_primary_branches";
    public static final String SKELETON_BRANCHES = "skeleton_branches";
    public static final String SKELETON_JUNCTIONS = "skeleton_junctions";
    public static final String SKELETON_ENDPOINTS = "skeleton_endpoints";
    public static final String SKELETON_VOXELS = "skeleton_voxels";

    public static final String[] FRACTAL_FEATURES = {
            FRACTAL_DIM_XY, FRACTAL_R2_XY,
            LACUNARITY_MEAN_XY, LACUNARITY_SPREAD_XY
    };
    public static final String[] COMPOSITE_FEATURES = {RI, SRI, PB, MP, VSD};
    public static final String[] ARBORIZATION_FEATURES = {
            SHOLL_CRITICAL_RADIUS_UM,
            SHOLL_CRITICAL_INTERSECTIONS,
            SHOLL_SCHOENEN_INDEX,
            SHOLL_PRIMARY_BRANCHES,
            SKELETON_BRANCHES,
            SKELETON_JUNCTIONS,
            SKELETON_ENDPOINTS,
            SKELETON_VOXELS
    };

    private static final Set<String> ALL = unmodifiableSet(
            FRACTAL_FEATURES, COMPOSITE_FEATURES, ARBORIZATION_FEATURES);

    private ExtendedFeatureCatalog() {}

    public static boolean isExtendedFeature(String name) {
        return name != null && ALL.contains(name);
    }

    public static boolean isFractalFeature(String name) {
        return contains(FRACTAL_FEATURES, name);
    }

    public static boolean isCompositeFeature(String name) {
        return contains(COMPOSITE_FEATURES, name);
    }

    public static boolean isArborizationFeature(String name) {
        return contains(ARBORIZATION_FEATURES, name);
    }

    public static Set<String> allFeatureNames() {
        return ALL;
    }

    private static boolean contains(String[] names, String value) {
        if (value == null) return false;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(value)) return true;
        }
        return false;
    }

    private static Set<String> unmodifiableSet(String[]... groups) {
        LinkedHashSet<String> values = new LinkedHashSet<String>();
        for (int i = 0; i < groups.length; i++) {
            values.addAll(Arrays.asList(groups[i]));
        }
        return Collections.unmodifiableSet(values);
    }
}
