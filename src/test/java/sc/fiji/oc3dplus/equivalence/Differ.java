package sc.fiji.oc3dplus.equivalence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies the tier contract to a golden/candidate pair.
 *
 * <p>Comparison strategy follows the case (harness section 5): Case A by exact
 * label equality including numbering, Cases B and C as partitions with rows put
 * into canonical order first, because mcib3d numbers objects differently.
 *
 * <p>A column present in one record and absent in the other is a Tier 1 finding
 * in its own right. That is not pedantry - it is how the harness catches the
 * `Median` column, which the classic path emits and no replacement computes.
 */
public final class Differ {

    /** One difference, classified by tier. */
    public static final class Finding {
        public final int tier;
        public final String recordId;
        public final String what;
        public final String detail;

        Finding(int tier, String recordId, String what, String detail) {
            this.tier = tier;
            this.recordId = recordId;
            this.what = what;
            this.detail = detail;
        }

        /** Tier 1 always blocks; Tier 2 blocks only outside its declared bound. */
        public boolean blocking() {
            return tier == 1;
        }

        @Override public String toString() {
            return "TIER " + tier + "  " + recordId + "  " + what + ": " + detail;
        }
    }

    /** Accumulated relative differences for one (case, column) pair. */
    public static final class Delta {
        public final HarnessCase harnessCase;
        public final String column;
        final List<Double> relative = new ArrayList<Double>();
        int outsideBound;
        int nonZero;
        int compared;

        Delta(HarnessCase harnessCase, String column) {
            this.harnessCase = harnessCase;
            this.column = column;
        }

        public String key() {
            return harnessCase + "/" + column;
        }

        public double min() {
            return percentile(0.0);
        }

        public double median() {
            return percentile(0.5);
        }

        public double p95() {
            return percentile(0.95);
        }

        public double max() {
            return percentile(1.0);
        }

        private double percentile(double fraction) {
            if (relative.isEmpty()) return 0.0;
            List<Double> sorted = new ArrayList<Double>(relative);
            Collections.sort(sorted);
            int index = (int) Math.round(fraction * (sorted.size() - 1));
            return sorted.get(index).doubleValue();
        }

        public String describe() {
            return key()
                    + " compared=" + compared
                    + " nonZero=" + nonZero
                    + " outsideBound=" + outsideBound
                    + " min=" + CaptureRecord.number(min())
                    + " median=" + CaptureRecord.number(median())
                    + " p95=" + CaptureRecord.number(p95())
                    + " max=" + CaptureRecord.number(max());
        }
    }

    /** Findings plus the Tier 2 delta table the harness asks for. */
    public static final class Report {
        public final List<Finding> findings = new ArrayList<Finding>();
        private final Map<String, Delta> deltas = new LinkedHashMap<String, Delta>();

        public List<Finding> tier(int tier) {
            List<Finding> out = new ArrayList<Finding>();
            for (int i = 0; i < findings.size(); i++) {
                if (findings.get(i).tier == tier) out.add(findings.get(i));
            }
            return out;
        }

        public List<Delta> deltaTable() {
            return Collections.unmodifiableList(new ArrayList<Delta>(deltas.values()));
        }

        Delta delta(HarnessCase harnessCase, String column) {
            String key = harnessCase + "/" + column;
            Delta existing = deltas.get(key);
            if (existing == null) {
                existing = new Delta(harnessCase, column);
                deltas.put(key, existing);
            }
            return existing;
        }

        void add(int tier, String recordId, String what, String detail) {
            findings.add(new Finding(tier, recordId, what, detail));
        }

        public boolean clean() {
            return findings.isEmpty();
        }

        public String summarise() {
            StringBuilder out = new StringBuilder();
            out.append("tier1=").append(tier(1).size())
                    .append(" tier2=").append(tier(2).size())
                    .append(" tier3=").append(tier(3).size());
            return out.toString();
        }
    }

    /** Scalars that must match exactly; a difference is Tier 1. */
    private static final List<String> TIER1_SCALARS = Collections.unmodifiableList(Arrays.asList(
            "outcome",
            "exception.class",
            "exception.message",
            "objectCount",
            "foundObjects",
            "survivingPerFilter",
            "filterLabels",
            "warnings.count",
            // "summary" is compared by diffSummary instead: its structure at Tier 1,
            // each morphology mean at the tier of the column it is a mean of. As a
            // flat Tier 1 string it re-reported every declared column difference a
            // second time.
            "label.present",
            "label.dims",
            "label.distinct",
            "label.dense",
            "label.max",
            "label.partition",
            "stats.rowCount",
            "stats.rowDigest",
            "maps.built",
            "maps.guard",
            "map.objects.partition",
            "map.surfaces.partition",
            "map.centroids.partition",
            "map.centersOfMass.partition",
            "map.objects.overlay",
            "map.surfaces.overlay",
            "map.centroids.overlay",
            "map.centersOfMass.overlay",
            "map.objects.overlaySkipped",
            "map.surfaces.overlaySkipped",
            "map.centroids.overlaySkipped",
            "map.centersOfMass.overlaySkipped"));

    /**
     * Scalars that describe the fixture and configuration. A difference means the
     * corpus itself moved, which invalidates the comparison rather than reporting
     * on the migration, so it is Tier 1 and named as corpus drift.
     */
    private static final List<String> INTEGRITY_SCALARS = Collections.unmodifiableList(Arrays.asList(
            "input.dims",
            "input.bitDepth",
            "input.channels",
            "input.frames",
            "input.calibration",
            "config.threshold",
            "config.minSize",
            "config.maxSize",
            "config.excludeOnEdges",
            "config.redirect",
            "config.maps",
            "config.fractal",
            "config.composites",
            "config.arborization",
            "config.filters"));

    /**
     * Tier 3: reported for sign-off, not blocking. The new labeller extends the
     * output bit-depth ladder with a 32-bit float rung so counts above 65,535
     * cannot wrap, which is a deliberate documented change rather than a
     * regression - but it is user-visible, so it must never pass silently.
     */
    private static final List<String> TIER3_SCALARS = Collections.unmodifiableList(Arrays.asList(
            "label.bitDepth"));

    private Differ() {}

    public static Report diff(List<CaptureRecord> goldens, List<CaptureRecord> candidates) {
        Report report = new Report();
        Map<String, CaptureRecord> byId = new LinkedHashMap<String, CaptureRecord>();
        for (int i = 0; i < candidates.size(); i++) {
            byId.put(candidates.get(i).id(), candidates.get(i));
        }
        for (int i = 0; i < goldens.size(); i++) {
            CaptureRecord golden = goldens.get(i);
            CaptureRecord candidate = byId.remove(golden.id());
            if (candidate == null) {
                report.add(1, golden.id(), "record", "present in golden, absent from candidate");
                continue;
            }
            diffRecord(golden, candidate, report);
        }
        for (Map.Entry<String, CaptureRecord> extra : byId.entrySet()) {
            report.add(1, extra.getKey(), "record", "present in candidate, absent from golden");
        }
        return report;
    }

    /**
     * Did a run that <b>crashed</b> in the shipped plugin now succeed?
     *
     * <p>TOLERANCES.md §4 records this as a signed-off Tier 3 item: {@code
     * Counter3D.findObjects} sizes {@code IDcount} as {@code new int[tag]} with
     * {@code tag} bumped at the start of the <i>next</i> voxel's iteration, so a
     * foreground final voxel that starts a new object throws
     * {@code ArrayIndexOutOfBoundsException}. The golden captured that crash
     * deliberately, exception text and all, and the fix is by construction.
     *
     * <p>The direction is asymmetric on purpose. A golden that crashed and a
     * candidate that completes is the declared fix. A golden that completed and a
     * candidate that crashes is a Tier 1 regression and is never covered here.
     * The exception type is pinned too, so an unrelated crash disappearing does
     * not quietly pass through this door.
     */
    private static boolean isDeclaredCrashFix(CaptureRecord golden, CaptureRecord candidate) {
        return "exception".equals(golden.get("outcome"))
                && "ok".equals(candidate.get("outcome"))
                && "java.lang.ArrayIndexOutOfBoundsException".equals(
                        golden.get("exception.class"));
    }

    static void diffRecord(CaptureRecord golden, CaptureRecord candidate, Report report) {
        String id = golden.id();
        if (golden.harnessCase != candidate.harnessCase) {
            report.add(1, id, "case",
                    "golden=" + golden.harnessCase + " candidate=" + candidate.harnessCase);
            return;
        }
        HarnessCase harnessCase = golden.harnessCase;

        if (isDeclaredCrashFix(golden, candidate)) {
            report.add(3, id, "crash fixed (declared)",
                    "the golden records " + golden.get("exception.class")
                            + " from the shipped Counter3D and the candidate completes"
                            + " with " + candidate.get("objectCount") + " object(s);"
                            + " see TOLERANCES.md section 4, 'Final-voxel isolated object'."
                            + " Nothing further is comparable for this record: the golden"
                            + " has no measurements to compare against");
            return;
        }

        for (int i = 0; i < INTEGRITY_SCALARS.size(); i++) {
            String key = INTEGRITY_SCALARS.get(i);
            if (!golden.get(key).equals(candidate.get(key))) {
                report.add(1, id, "corpus drift: " + key,
                        "golden=" + golden.get(key) + " candidate=" + candidate.get(key));
            }
        }
        for (int i = 0; i < TIER1_SCALARS.size(); i++) {
            String key = TIER1_SCALARS.get(i);
            if (!golden.get(key).equals(candidate.get(key))) {
                report.add(1, id, key,
                        "golden=" + golden.get(key) + " candidate=" + candidate.get(key));
            }
        }
        for (int i = 0; i < TIER3_SCALARS.size(); i++) {
            String key = TIER3_SCALARS.get(i);
            if (!golden.get(key).equals(candidate.get(key))) {
                report.add(3, id, key,
                        "golden=" + golden.get(key) + " candidate=" + candidate.get(key));
            }
        }
        diffWarningText(golden, candidate, report);
        diffSummary(golden, candidate, report, harnessCase, depthOf(golden));
        diffCsvLines(golden, candidate, report);

        // Case A is compared by exact label equality, numbering included. Cases B
        // and C use the partition digest, already covered as a Tier 1 scalar.
        if (harnessCase.comparison() == HarnessCase.Comparison.EXACT_LABELS) {
            compareExactScalar(golden, candidate, report, "label.digest");
            compareExactScalar(golden, candidate, report, "map.objects.pixels");
            compareExactScalar(golden, candidate, report, "map.surfaces.pixels");
            compareExactScalar(golden, candidate, report, "map.centroids.pixels");
            compareExactScalar(golden, candidate, report, "map.centersOfMass.pixels");
        }

        diffColumns(golden, candidate, report);
        diffRows(golden, candidate, report, harnessCase, depthOf(golden));
    }

    private static void compareExactScalar(CaptureRecord golden,
                                           CaptureRecord candidate,
                                           Report report,
                                           String key) {
        if (!golden.has(key) && !candidate.has(key)) return;
        if (!golden.get(key).equals(candidate.get(key))) {
            report.add(1, golden.id(), key,
                    "golden=" + golden.get(key) + " candidate=" + candidate.get(key));
        }
    }

    /**
     * The summary line's morphology means, mapped to the columns they are means of.
     *
     * @see sc.fiji.oc3d.core.io.SummaryReporter
     */
    private static final String[][] SUMMARY_FIELD_COLUMNS = {
            {"Size", "Nb of obj. voxels"},
            {"Volume", "Volume ("},
            {"Surface area", "Surface ("},
            {"Sphericity", "Morph_Sphericity"},
            {"Compactness", "Morph_Compactness"},
            {"Elongation", "Morph_Elongation"},
            {"Mean intensity", "Mean"},
            {"Max intensity", "Max"},
            {"Max Feret diameter", "Morph_Feret3D_um"}
    };

    /**
     * Compares the summary line, tiering each morphology mean as its own column.
     *
     * <p>The summary used to be a Tier 1 exact string, which double-counted every
     * declared column difference: the single-slice surface divergence of
     * TOLERANCES.md §0.3 is contracted as Tier 3, reported correctly as Tier 3 in
     * the {@code Surface} column, and then reported <i>again</i> as a Tier 1
     * summary mismatch purely because the mean is rendered into that sentence. All
     * twelve {@code single-slice} configurations failed that way, on a difference
     * already signed off.
     *
     * <p>This is not a loosening. The sentence's structure - object count, size
     * filter, threshold, and which means are present and in what order - is still
     * compared exactly at Tier 1. Only the numeric value of a mean is tiered, and
     * it is tiered by the same contract, with the same bound, as the column it
     * summarises. A value whose column is Tier 1 is still Tier 1 here.
     */
    private static void diffSummary(CaptureRecord golden,
                                    CaptureRecord candidate,
                                    Report report,
                                    HarnessCase harnessCase,
                                    int depth) {
        String goldenText = golden.get("summary");
        String candidateText = candidate.get("summary");
        if (goldenText.equals(candidateText)) return;

        String marker = "Morphology means: ";
        int goldenMarker = goldenText.indexOf(marker);
        int candidateMarker = candidateText.indexOf(marker);
        if (goldenMarker < 0 || candidateMarker < 0) {
            report.add(1, golden.id(), "summary",
                    "golden=" + goldenText + " candidate=" + candidateText);
            return;
        }
        if (!goldenText.substring(0, goldenMarker)
                .equals(candidateText.substring(0, candidateMarker))) {
            report.add(1, golden.id(), "summary preamble",
                    "golden=" + goldenText.substring(0, goldenMarker)
                            + " candidate=" + candidateText.substring(0, candidateMarker));
            return;
        }

        Map<String, String> goldenMeans = parseMeans(goldenText.substring(goldenMarker + marker.length()));
        Map<String, String> candidateMeans =
                parseMeans(candidateText.substring(candidateMarker + marker.length()));
        if (!new ArrayList<String>(goldenMeans.keySet())
                .equals(new ArrayList<String>(candidateMeans.keySet()))) {
            report.add(1, golden.id(), "summary fields",
                    "golden=" + ColumnContract.join(new ArrayList<String>(goldenMeans.keySet()), ",")
                            + " candidate="
                            + ColumnContract.join(new ArrayList<String>(candidateMeans.keySet()), ","));
            return;
        }

        for (Map.Entry<String, String> entry : goldenMeans.entrySet()) {
            String field = entry.getKey();
            String goldenValue = entry.getValue();
            String candidateValue = candidateMeans.get(field);
            if (goldenValue.equals(candidateValue)) continue;
            String heading = headingForSummaryField(field, golden.columns());
            ColumnContract.Entry contract = heading == null ? null
                    : ColumnContract.lookup(heading, harnessCase);
            if (contract == null) {
                report.add(1, golden.id(), "summary mean '" + field + "'",
                        "no column contract found; golden=" + goldenValue
                                + " candidate=" + candidateValue);
                continue;
            }
            compareCell(report, golden.id(), harnessCase, contract, heading,
                    "summary mean '" + field + "'", goldenValue, candidateValue, depth);
        }
    }

    /** Parses {@code "Size=36, Volume=36, ...."} preserving order. */
    private static Map<String, String> parseMeans(String text) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        String body = text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
        String[] parts = body.split(",");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) continue;
            int equals = part.indexOf('=');
            if (equals <= 0) {
                out.put(part, "");
                continue;
            }
            out.put(part.substring(0, equals).trim(), part.substring(equals + 1).trim());
        }
        return out;
    }

    /** The column heading a summary field is the mean of, resolving unit suffixes. */
    private static String headingForSummaryField(String field, List<String> columns) {
        for (int i = 0; i < SUMMARY_FIELD_COLUMNS.length; i++) {
            if (!SUMMARY_FIELD_COLUMNS[i][0].equals(field)) continue;
            String target = SUMMARY_FIELD_COLUMNS[i][1];
            if (!target.endsWith("(")) return target;
            for (int c = 0; c < columns.size(); c++) {
                if (columns.get(c).startsWith(target)) return columns.get(c);
            }
            return null;
        }
        return null;
    }

    private static void diffWarningText(CaptureRecord golden, CaptureRecord candidate, Report report) {
        int count = 0;
        try {
            count = Integer.parseInt(golden.get("warnings.count"));
        } catch (NumberFormatException absent) {
            return;
        }
        for (int i = 0; i < count; i++) {
            String key = "warnings." + i;
            if (!golden.get(key).equals(candidate.get(key))) {
                report.add(1, golden.id(), key,
                        "golden=" + golden.get(key) + " candidate=" + candidate.get(key));
            }
        }
    }

    /**
     * Batch CSV lines, already normalised of {@code BatchRunId},
     * {@code SourceLastModified} and {@code PluginVersion}. Every line is Tier 1;
     * {@code batch_scores.csv} especially, because within-batch z-scores and
     * percentiles are computed against the whole population, so one object
     * appearing, disappearing or changing volume shifts every score row.
     */
    private static void diffCsvLines(CaptureRecord golden, CaptureRecord candidate, Report report) {
        List<String> keys = golden.scalarKeys();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            if (!key.startsWith("csv.")) continue;
            if (!golden.get(key).equals(candidate.get(key))) {
                report.add(1, golden.id(), key,
                        "golden=" + golden.get(key) + " candidate=" + candidate.get(key));
            }
        }
        List<String> candidateKeys = candidate.scalarKeys();
        for (int i = 0; i < candidateKeys.size(); i++) {
            String key = candidateKeys.get(i);
            if (!key.startsWith("csv.") || golden.has(key)) continue;
            report.add(1, golden.id(), key, "line present in candidate only: " + candidate.get(key));
        }
    }

    /**
     * Is this added column a decision already recorded in TOLERANCES.md, rather
     * than an unexplained schema change?
     *
     * <p>Deliberately narrow: both the column name and the cases are pinned, so
     * every other added column - and every removed column, without exception -
     * stays Tier 1. This is not a tolerance and cannot be widened into one; it
     * records that a specific, user-decided addition was expected.
     *
     * <p>The single entry is {@code Median} on Cases B and C. TOLERANCES.md §4
     * records the user's decision to implement a per-object median rather than
     * lose the column from Case A output. Once one engine measures every input
     * shape there is one column set, and since Case A must not move that set is
     * Case A's - so B and C gain the column A already had. Additive: no column is
     * lost anywhere, which is why it is separable from a removal.
     */
    private static boolean isDeclaredAddition(String column, HarnessCase harnessCase) {
        return "Median".equals(column)
                && (harnessCase == HarnessCase.B || harnessCase == HarnessCase.C);
    }

    private static void diffColumns(CaptureRecord golden, CaptureRecord candidate, Report report) {
        List<String> goldenColumns = golden.columns();
        List<String> candidateColumns = candidate.columns();
        for (int i = 0; i < goldenColumns.size(); i++) {
            if (!candidateColumns.contains(goldenColumns.get(i))) {
                report.add(1, golden.id(), "column removed",
                        "'" + goldenColumns.get(i) + "' is in the golden and not in the candidate");
            }
        }
        for (int i = 0; i < candidateColumns.size(); i++) {
            String added = candidateColumns.get(i);
            if (goldenColumns.contains(added)) continue;
            if (isDeclaredAddition(added, golden.harnessCase)) {
                report.add(3, golden.id(), "column added (declared)",
                        "'" + added + "' is a signed-off addition for case "
                                + golden.harnessCase + "; see TOLERANCES.md section 4");
                continue;
            }
            report.add(1, golden.id(), "column added",
                    "'" + added + "' is in the candidate and not in the golden");
        }
        if (!goldenColumns.equals(candidateColumns)
                && goldenColumns.size() == candidateColumns.size()) {
            report.add(2, golden.id(), "column order",
                    "golden=" + ColumnContract.join(goldenColumns, ",")
                            + " candidate=" + ColumnContract.join(candidateColumns, ","));
        }
    }

    private static void diffRows(CaptureRecord golden,
                                 CaptureRecord candidate,
                                 Report report,
                                 HarnessCase harnessCase,
                                 int depth) {
        // Above Capture.MAX_RECORDED_ROWS the rows are held as a digest, already
        // compared as a Tier 1 scalar. There is nothing per-cell to compare.
        if (golden.has("stats.rowDigest") || candidate.has("stats.rowDigest")) {
            return;
        }
        if (golden.rows().size() != candidate.rows().size()) {
            report.add(1, golden.id(), "row count",
                    "golden=" + golden.rows().size() + " candidate=" + candidate.rows().size());
            return;
        }
        boolean canonical = harnessCase.comparison() == HarnessCase.Comparison.PARTITION;
        List<Integer> goldenOrder = canonical ? canonicalOrder(golden) : naturalOrder(golden);
        List<Integer> candidateOrder = canonical ? canonicalOrder(candidate) : naturalOrder(candidate);

        for (int i = 0; i < golden.columns().size(); i++) {
            String heading = golden.columns().get(i);
            if (!candidate.columns().contains(heading)) continue;
            ColumnContract.Entry contract = ColumnContract.lookup(heading, harnessCase);
            if (contract == null) {
                report.add(1, golden.id(), "uncontracted column",
                        "'" + heading + "' has no entry in TOLERANCES.md for case " + harnessCase);
                continue;
            }
            for (int row = 0; row < goldenOrder.size(); row++) {
                String goldenCell = golden.cell(heading, goldenOrder.get(row).intValue());
                String candidateCell = candidate.cell(heading, candidateOrder.get(row).intValue());
                compareCell(report, golden.id(), harnessCase, contract, heading,
                        heading + " row " + row, goldenCell, candidateCell, depth);
            }
        }
    }

    private static void compareCell(Report report,
                                    String recordId,
                                    HarnessCase harnessCase,
                                    ColumnContract.Entry contract,
                                    String heading,
                                    String where,
                                    String goldenCell,
                                    String candidateCell,
                                    int depth) {
        if (goldenCell == null || candidateCell == null) return;
        if (goldenCell.equals(candidateCell)) {
            recordDelta(report, harnessCase, contract, 0.0, false);
            return;
        }
        if (ColumnContract.isTextColumn(heading)) {
            report.add(contract.tier, recordId, where,
                    "golden='" + goldenCell + "' candidate='" + candidateCell + "'");
            return;
        }

        double goldenValue = parse(goldenCell);
        double candidateValue = parse(candidateCell);
        double relative = relativeDifference(goldenValue, candidateValue);
        String detail = "golden=" + goldenCell + " candidate=" + candidateCell
                + " relative=" + CaptureRecord.number(relative);

        switch (contract.rule) {
            case EXACT:
                report.add(contract.tier, recordId, where, detail);
                recordDelta(report, harnessCase, contract, relative, true);
                return;
            case EXACT_IF_3D:
                if (depth > 1) {
                    report.add(contract.tier, recordId, where, detail);
                    recordDelta(report, harnessCase, contract, relative, true);
                } else {
                    report.add(3, recordId, where,
                            "declared single-slice difference (TOLERANCES.md 0.3): " + detail);
                    recordDelta(report, harnessCase, contract, relative, false);
                }
                return;
            case FLOAT_NARROW:
                compareNarrowedToFloat(report, recordId, harnessCase, contract, where,
                        goldenValue, candidateValue, relative, detail);
                return;
            case FLOAT_NARROW_IF_3D:
                if (depth > 1) {
                    compareNarrowedToFloat(report, recordId, harnessCase, contract, where,
                            goldenValue, candidateValue, relative, detail);
                } else {
                    report.add(3, recordId, where,
                            "declared single-slice difference (TOLERANCES.md 0.3): " + detail);
                    recordDelta(report, harnessCase, contract, relative, false);
                }
                return;
            case RELATIVE:
                boolean outside = !(relative <= contract.bound);
                recordDelta(report, harnessCase, contract, relative, outside);
                if (outside) {
                    report.add(contract.tier, recordId, where,
                            "outside declared bound " + contract.bound + ": " + detail);
                }
                return;
            case ABSOLUTE:
                double absolute = Math.abs(goldenValue - candidateValue);
                boolean beyond = !(absolute <= contract.bound);
                recordDelta(report, harnessCase, contract, relative, beyond);
                if (beyond) {
                    report.add(contract.tier, recordId, where,
                            "outside declared absolute bound " + contract.bound + ": " + detail);
                }
                return;
            case SIGNOFF:
            case OPEN:
            default:
                report.add(contract.tier, recordId, where, detail);
                recordDelta(report, harnessCase, contract, relative, false);
        }
    }

    private static void compareNarrowedToFloat(Report report,
                                               String recordId,
                                               HarnessCase harnessCase,
                                               ColumnContract.Entry contract,
                                               String where,
                                               double goldenValue,
                                               double candidateValue,
                                               double relative,
                                               String detail) {
        if ((float) goldenValue == (float) candidateValue) {
            recordDelta(report, harnessCase, contract, relative, false);
            return;
        }
        report.add(contract.tier, recordId, where,
                "differs after narrowing to float: " + detail);
        recordDelta(report, harnessCase, contract, relative, true);
    }

    private static void recordDelta(Report report,
                                    HarnessCase harnessCase,
                                    ColumnContract.Entry contract,
                                    double relative,
                                    boolean outside) {
        Delta delta = report.delta(harnessCase, contract.column);
        delta.compared++;
        delta.relative.add(Double.valueOf(relative));
        if (relative != 0.0) delta.nonZero++;
        if (outside) delta.outsideBound++;
    }

    static double relativeDifference(double golden, double candidate) {
        if (Double.isNaN(golden) && Double.isNaN(candidate)) return 0.0;
        if (golden == candidate) return 0.0;
        double scale = Math.max(Math.abs(golden), Math.abs(candidate));
        if (scale == 0.0 || !isFinite(scale)) return Double.POSITIVE_INFINITY;
        return Math.abs(golden - candidate) / scale;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static double parse(String cell) {
        if (cell == null || cell.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(cell);
        } catch (NumberFormatException notNumeric) {
            return Double.NaN;
        }
    }

    private static int depthOf(CaptureRecord record) {
        String dims = record.get("input.dims");
        int at = dims.lastIndexOf('x');
        if (at < 0) return 0;
        try {
            return Integer.parseInt(dims.substring(at + 1));
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    private static List<Integer> naturalOrder(CaptureRecord record) {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < record.rows().size(); i++) out.add(Integer.valueOf(i));
        return out;
    }

    /** Rows sorted by Z, then Y, then X, then voxel count, then Label. */
    static List<Integer> canonicalOrder(final CaptureRecord record) {
        List<Integer> order = naturalOrder(record);
        Collections.sort(order, new Comparator<Integer>() {
            @Override public int compare(Integer left, Integer right) {
                String[] keys = {"Z", "Y", "X", "Nb of obj. voxels", "Label"};
                for (int i = 0; i < keys.length; i++) {
                    double a = parse(record.cell(keys[i], left.intValue()));
                    double b = parse(record.cell(keys[i], right.intValue()));
                    int comparison = Double.compare(a, b);
                    if (comparison != 0) return comparison;
                }
                return left.compareTo(right);
            }
        });
        return order;
    }
}
