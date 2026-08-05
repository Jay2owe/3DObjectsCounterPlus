package sc.fiji.oc3dplus.equivalence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The tier contract as executable code, so it cannot drift from the prose in
 * {@code docs/migration/TOLERANCES.md}. {@link ToleranceContractTest} parses the
 * tables in that document and asserts they describe exactly these entries.
 *
 * <p>Tiers and bounds are declared per <b>column and case</b>, not globally,
 * because the three cases have different current references (harness section 2)
 * and therefore different achievable claims. See TOLERANCES.md section 0 for the
 * source and bytecode evidence behind each bound.
 */
public final class ColumnContract {

    /** How a column's two values are compared. */
    public enum Rule {
        /** Identical values. No tolerance. */
        EXACT("exact"),
        /** Relative difference within the declared bound. */
        RELATIVE("relative"),
        /** Absolute difference within the declared bound. */
        ABSOLUTE("absolute"),
        /**
         * Exact when the stack has more than one slice. Single-slice input is a
         * declared known difference, reported rather than passed: Counter3D
         * excludes in-plane-interior voxels from {@code surf_size} when
         * {@code nbSlices == 1}, the accumulator does not.
         */
        EXACT_IF_3D("exact-if-3d"),
        /**
         * Equal after narrowing the candidate to {@code float}. Used where the
         * pre-migration reference is a {@code float} field of
         * {@code Utilities.Object3D} and bit-identity against a double-precision
         * replacement is arithmetically unreachable. Stronger than a numeric
         * tolerance; see TOLERANCES.md section 3, and note it awaits the user's
         * ratification.
         */
        FLOAT_NARROW("float-narrow"),
        /**
         * {@link #FLOAT_NARROW} when the stack has more than one slice; a declared
         * known difference when it has exactly one. Both implementations sum the
         * same per-voxel exposed-face terms in the same order for a true 3D stack,
         * so only float-versus-double accumulation separates them - but
         * {@code Counter3D} has a single-slice special case that the accumulator
         * does not share. Measured by {@code SurfaceDefinitionProbeTest}.
         */
        FLOAT_NARROW_IF_3D("float-narrow-if-3d"),
        /** Known algorithmic difference; requires written sign-off. */
        SIGNOFF("signoff"),
        /** No implementation exists to compare against yet. Must be closed. */
        OPEN("open");

        private final String token;

        Rule(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        public static Rule parse(String text) {
            Rule[] all = values();
            for (int i = 0; i < all.length; i++) {
                if (all[i].token.equals(text)) return all[i];
            }
            throw new IllegalArgumentException("Unknown comparison rule '" + text + "'.");
        }
    }

    /** One declared contract row. */
    public static final class Entry {
        public final String column;
        public final Set<HarnessCase> cases;
        public final int tier;
        public final Rule rule;
        /** Declared bound, or {@code NaN} when the rule takes none. */
        public final double bound;

        Entry(String column, Set<HarnessCase> cases, int tier, Rule rule, double bound) {
            this.column = column;
            this.cases = Collections.unmodifiableSet(cases);
            this.tier = tier;
            this.rule = rule;
            this.bound = bound;
        }

        public String key() {
            List<String> caseNames = new ArrayList<String>();
            for (HarnessCase c : HarnessCase.values()) {
                if (cases.contains(c)) caseNames.add(c.name());
            }
            return column + " [" + join(caseNames, ",") + "]";
        }

        @Override public String toString() {
            return key() + " tier=" + tier + " rule=" + rule.token()
                    + " bound=" + (Double.isNaN(bound) ? "-" : Double.toString(bound));
        }
    }

    /**
     * Canonical name of a statistics column. The volume and surface headings
     * carry the calibration unit, so they are folded onto a unit-free name; a
     * change of unit still shows up as a column-set difference because the raw
     * heading list is compared separately.
     */
    public static final String VOLUME = "Volume (unit^3)";
    public static final String SURFACE = "Surface (unit^2)";
    /** Pseudo-column for the object count, which is a record scalar not a table column. */
    public static final String OBJECT_COUNT = "objectCount";

    private static final List<Entry> ENTRIES = build();
    private static final Map<String, Entry> BY_KEY = index(ENTRIES);

    private ColumnContract() {}

    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** Canonicalises a raw {@code ResultsTable} heading for contract lookup. */
    public static String canonicalName(String heading) {
        if (heading == null) return "";
        if (heading.startsWith("Volume (")) return VOLUME;
        if (heading.startsWith("Surface (")) return SURFACE;
        return heading;
    }

    /**
     * The contract row governing {@code heading} for {@code harnessCase}, or
     * {@code null} when the column is not covered. An uncovered column is a
     * finding, not a pass - {@link Differ} reports it so a new column cannot
     * enter the output unnoticed.
     */
    public static Entry lookup(String heading, HarnessCase harnessCase) {
        String column = canonicalName(heading);
        for (int i = 0; i < ENTRIES.size(); i++) {
            Entry entry = ENTRIES.get(i);
            if (entry.column.equals(column) && entry.cases.contains(harnessCase)) {
                return entry;
            }
        }
        return null;
    }

    private static Map<String, Entry> index(List<Entry> entries) {
        Map<String, Entry> out = new LinkedHashMap<String, Entry>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (out.put(entry.key(), entry) != null) {
                throw new IllegalStateException("Duplicate contract entry " + entry.key());
            }
        }
        return out;
    }

    public static Map<String, Entry> byKey() {
        return Collections.unmodifiableMap(BY_KEY);
    }

    private static List<Entry> build() {
        List<Entry> out = new ArrayList<Entry>();

        // Section 1 - Tier 1, exact, no tolerance.
        String[] tier1AllCases = {
                OBJECT_COUNT,
                "Label",
                "Nb of obj. voxels",
                VOLUME,
                "Min",
                "Max",
                "BX", "BY", "BZ", "B-width", "B-height", "B-depth",
                // engine/extended runs downstream of the label map and touches no
                // mcib3d, so identical labels must give identical values.
                "Morph_FractalDim_XY",
                "Morph_FractalDim_XY_R2",
                "Morph_LacunarityMean_XY",
                "Morph_LacunaritySpread_XY",
                "Morph_RI", "Morph_SRI", "Morph_PB", "Morph_MP", "Morph_VSD",
                "Morph_ShollCriticalRadius_um",
                "Morph_ShollCriticalIntersections",
                "Morph_ShollSchoenenIndex",
                "Morph_ShollPrimaryBranches",
                "Morph_SkeletonBranches",
                "Morph_SkeletonJunctions",
                "Morph_SkeletonEndpoints",
                "Morph_SkeletonVoxels",
                "Morph_ArborizationBackend"
        };
        for (int i = 0; i < tier1AllCases.length; i++) {
            out.add(exact(tier1AllCases[i], allCases()));
        }

        // Already produced by LabelFeatureAccumulator on both non-label paths
        // today, so the migration does not change the code computing them.
        String[] morphAB = {
                "Morph_Sphericity", "Morph_Compactness", "Morph_Elongation", "Morph_Feret3D_um"
        };
        for (int i = 0; i < morphAB.length; i++) {
            out.add(exact(morphAB[i], EnumSet.of(HarnessCase.A, HarnessCase.B)));
        }

        // Section 2 - Tier 2, bounded.
        out.add(entry(SURFACE, EnumSet.of(HarnessCase.A), 3, Rule.SIGNOFF, Double.NaN));
        out.add(entry(SURFACE, EnumSet.of(HarnessCase.B, HarnessCase.C), 2, Rule.RELATIVE, 5e-2));
        out.add(entry("Nb of surf. voxels", EnumSet.of(HarnessCase.A), 2, Rule.EXACT_IF_3D, Double.NaN));
        out.add(entry("Nb of surf. voxels", EnumSet.of(HarnessCase.B, HarnessCase.C), 2, Rule.RELATIVE, 5e-2));
        out.add(entry("Morph_Sphericity", EnumSet.of(HarnessCase.C), 2, Rule.RELATIVE, 5e-2));
        out.add(entry("Morph_Compactness", EnumSet.of(HarnessCase.C), 2, Rule.RELATIVE, 5e-2));
        out.add(entry("Morph_Elongation", EnumSet.of(HarnessCase.C), 2, Rule.RELATIVE, 1e-9));

        // Case A's reference for these is a float field of Utilities.Object3D,
        // so bit-identity against a double replacement is unreachable.
        //
        // StdDev is deliberately NOT in this list. It looks like a sibling of these
        // and was originally declared as one, but the difference is not precision:
        // Counter3D divides by n-1 and the accumulator by n, so the values differ by
        // sqrt(n/(n-1)) - up to 29% on a two-voxel object. StdDevDefinitionProbeTest
        // measures it; TOLERANCES.md sections 2 and 4 carry it as Tier 3.
        String[] floatTyped = {"IntDen", "Mean", "X", "Y", "Z", "XM", "YM", "ZM"};
        for (int i = 0; i < floatTyped.length; i++) {
            out.add(entry(floatTyped[i], EnumSet.of(HarnessCase.A), 3, Rule.SIGNOFF, Double.NaN));
            out.add(exact(floatTyped[i], EnumSet.of(HarnessCase.B, HarnessCase.C)));
        }
        // Sample versus population standard deviation, measured, not precision.
        out.add(entry("StdDev", EnumSet.of(HarnessCase.A), 3, Rule.SIGNOFF, Double.NaN));
        out.add(exact("StdDev", EnumSet.of(HarnessCase.B, HarnessCase.C)));

        // Median came from the classic path alone before Stage 03 - mcib3d never
        // emitted the column - and is now computed by the accumulator for every
        // case, bit-identically to Utilities.Object3D.median. Its cell rule stays
        // float-narrow on Case A alongside its siblings; on B and C the column is
        // new, which diffColumns reports once as a declared addition.
        out.add(entry("Median", EnumSet.of(HarnessCase.A), 3, Rule.SIGNOFF, Double.NaN));
        out.add(entry("Median", EnumSet.of(HarnessCase.B, HarnessCase.C), 3, Rule.SIGNOFF,
                Double.NaN));

        // Section 4 - Tier 3. Feret is Tier 3 on Case C only: Cases A and B
        // already report the 13-direction bounded estimate today.
        out.add(entry("Morph_Feret3D_um", EnumSet.of(HarnessCase.C), 3, Rule.SIGNOFF, Double.NaN));

        return Collections.unmodifiableList(out);
    }

    private static Set<HarnessCase> allCases() {
        return EnumSet.of(HarnessCase.A, HarnessCase.B, HarnessCase.C);
    }

    private static Entry exact(String column, Set<HarnessCase> cases) {
        return entry(column, cases, 1, Rule.EXACT, Double.NaN);
    }

    private static Entry entry(String column, Set<HarnessCase> cases, int tier, Rule rule, double bound) {
        return new Entry(column, EnumSet.copyOf(cases), tier, rule, bound);
    }

    static String join(List<String> parts, String separator) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) out.append(separator);
            out.append(parts.get(i));
        }
        return out.toString();
    }

    static Set<HarnessCase> parseCases(String text) {
        String[] tokens = text.split(",");
        List<HarnessCase> parsed = new ArrayList<HarnessCase>();
        for (int i = 0; i < tokens.length; i++) {
            parsed.add(HarnessCase.parse(tokens[i].trim()));
        }
        return EnumSet.copyOf(parsed);
    }

    /** Columns that hold text rather than a number. */
    static boolean isTextColumn(String heading) {
        return "Morph_ArborizationBackend".equals(heading);
    }

    static List<String> allContractedColumns() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < ENTRIES.size(); i++) {
            String column = ENTRIES.get(i).column;
            if (!out.contains(column)) out.add(column);
        }
        Collections.sort(out);
        return out;
    }

    static List<String> tier1Columns() {
        return Collections.unmodifiableList(Arrays.asList("Label", "Nb of obj. voxels"));
    }
}
