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
            // "warnings.count" is compared by diffWarningText instead, which can
            // see whether an added warning is the declared channel/frame notice.
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
    /**
     * Records whose whole difference is a defect the user decided to fix.
     *
     * <p>Each entry names one fixture-and-configuration, the defect, and the
     * expected before and after. Nothing is matched loosely: if the numbers stop
     * being exactly these, the allowance does not apply and the record is compared
     * normally, so a second, unrelated change hiding behind a known one still
     * fails.
     *
     * <p>These are fixes, not tolerances. Each was measured on the shipped build,
     * written up in TOLERANCES.md §4, and ratified before it was declared here.
     */
    private static boolean isDeclaredDefectFix(CaptureRecord golden,
                                               CaptureRecord candidate,
                                               Report report) {
        String id = golden.id();

        // Hyperstack input measures one channel and one frame now. The old path
        // read the first nSlices planes of the stack and ignored the rest - on a
        // 2-channel 101-frame timelapse, one plane out of 202 - and it applied the
        // same truncation to a redirect image. Every measurement on a hyperstack
        // fixture therefore differs, by design and by decision, so each record is
        // reported once with its before and after rather than as a shower of
        // per-column Tier 1 findings that all have the same single cause.
        //
        // Scoped to input shape, which the golden records as corpus integrity data:
        // a single-volume record is still compared in full, column by column.
        if (isHyperstack(golden)) {
            report.add(3, id, "hyperstack channel/frame selection (declared)",
                    "golden measured " + golden.get("objectCount") + " object(s) from the "
                            + "first nSlices planes of a " + golden.get("input.channels")
                            + "-channel, " + golden.get("input.frames") + "-frame stack; "
                            + "the candidate measures one channel and one frame and finds "
                            + candidate.get("objectCount") + ". TOLERANCES.md section 4");
            return true;
        }

        // Zero is background whatever the threshold says, as Counter3D has always
        // treated it. The mcib3d path did not, so at threshold 0 it returned the
        // entire volume - background included - as one object.
        if (id.endsWith("/thr-all-foreground") && goldenObjectIsTheWholeVolume(golden)) {
            report.add(3, id, "threshold-0 foreground rule (declared)",
                    "at threshold 0 the golden returned a single object spanning every "
                            + "voxel of the volume, background included, because the mcib3d "
                            + "path did not treat zero as background; the candidate finds "
                            + candidate.get("objectCount") + " real object(s). "
                            + "TOLERANCES.md section 4");
            return true;
        }

        // mcib3d's getExcludeBorders(handler, false) excludes the X and Y borders
        // only. Counter3D excludes X, Y and - for a stack deeper than one slice -
        // Z, which is what the option documents and what Case A users have always
        // had. Unifying brings Case B into line, so an object touching only the
        // first or last slice is now dropped where mcib3d kept it.
        if (id.endsWith("/edges-on")
                && goldenKeptOnlyZBorderObjects(golden, candidate)) {
            report.add(3, id, "excludeOnEdges z rule (declared)",
                    "golden kept " + golden.get("objectCount") + " object(s) touching only "
                            + "the first or last slice, because mcib3d excludes X and Y "
                            + "borders only; the candidate drops them, matching Case A and "
                            + "the option's documented meaning. TOLERANCES.md section 4");
            return true;
        }

        // The 65 536th label wrapped to zero in a 16-bit label image, so the object
        // was counted in the table and absent from the map.
        if (id.startsWith("objects-65536/")
                && "65535".equals(golden.get("label.distinct"))
                && "65536".equals(candidate.get("label.distinct"))
                && golden.get("objectCount").equals(candidate.get("objectCount"))) {
            report.add(3, id, "16-bit label ceiling (declared)",
                    "golden counted " + golden.get("objectCount") + " objects but its label "
                            + "image held only 65535 of them; the candidate holds all "
                            + candidate.get("label.distinct") + ". TOLERANCES.md section 4");
            return true;
        }
        return false;
    }

    /**
     * Did the golden return exactly one object covering every voxel of the volume?
     *
     * <p>That is the fingerprint of counting the background: one object, and its
     * voxel count equal to width x height x depth. Keying the allowance on the
     * defect rather than on a fixture name means it covers each fixture that shows
     * it and nothing else - a record where the golden happens to hold one large
     * object that is <em>not</em> the whole volume is still compared normally.
     */
    /**
     * Under {@code excludeOnEdges}, did the golden keep objects that the candidate
     * drops, and does every one of them touch the first or last slice?
     *
     * <p>Checked from the bounding box the golden already records, so the claim is
     * about the objects themselves rather than about the fixture's name. If the
     * golden kept an object that does <em>not</em> touch a z border, this is some
     * other difference and the record is compared normally.
     */
    /** More than one channel or more than one frame in the recorded input. */
    private static boolean isHyperstack(CaptureRecord record) {
        return moreThanOne(record.get("input.channels")) || moreThanOne(record.get("input.frames"));
    }

    private static boolean moreThanOne(String text) {
        try {
            return Integer.parseInt(text.trim()) > 1;
        } catch (RuntimeException absent) {
            return false;
        }
    }

    private static boolean goldenKeptOnlyZBorderObjects(CaptureRecord golden,
                                                        CaptureRecord candidate) {
        int goldenCount;
        int candidateCount;
        try {
            goldenCount = Integer.parseInt(golden.get("objectCount"));
            candidateCount = Integer.parseInt(candidate.get("objectCount"));
        } catch (NumberFormatException absent) {
            return false;
        }
        if (goldenCount <= candidateCount || goldenCount == 0) return false;
        int depth = depthOf(golden);
        if (depth <= 1) return false;
        for (int row = 0; row < goldenCount; row++) {
            try {
                double originZ = Double.parseDouble(golden.cell("BZ", row));
                double extentZ = Double.parseDouble(golden.cell("B-depth", row));
                boolean touchesZ = originZ == 0.0 || (originZ + extentZ) >= depth;
                if (!touchesZ) return false;
            } catch (RuntimeException unreadable) {
                return false;
            }
        }
        return true;
    }

    private static boolean goldenObjectIsTheWholeVolume(CaptureRecord golden) {
        if (!"1".equals(golden.get("objectCount"))) return false;
        String dims = golden.get("label.dims");
        String[] parts = dims == null ? null : dims.split("x");
        if (parts == null || parts.length != 3) return false;
        long volume;
        double voxels;
        try {
            volume = Long.parseLong(parts[0].trim())
                    * Long.parseLong(parts[1].trim())
                    * Long.parseLong(parts[2].trim());
            voxels = Double.parseDouble(golden.cell("Nb of obj. voxels", 0));
        } catch (RuntimeException unreadable) {
            return false;
        }
        return volume > 0 && voxels == (double) volume;
    }

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

        if (isDeclaredDefectFix(golden, candidate, report)) {
            return;
        }

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

    private static String[] splitCsv(String line) {
        return line == null ? new String[0] : line.split(",", -1);
    }

    /**
     * Compares one CSV line field by field.
     *
     * @param header       the file's header line, naming each field
     * @param featureField index of a field naming the measurement this row is
     *                     about, for the long-format scores file, or {@code -1}
     *                     when each field is its own column as in the objects file
     */
    private static void diffCsvFields(CaptureRecord golden,
                                      CaptureRecord candidate,
                                      Report report,
                                      String key,
                                      String[] header,
                                      String goldenLine,
                                      String candidateLine,
                                      int featureField) {
        String[] goldenFields = splitCsv(goldenLine);
        String[] candidateFields = splitCsv(candidateLine);
        if (header.length == 0 || goldenFields.length != candidateFields.length) {
            report.add(1, golden.id(), key,
                    "field count differs, so this is a structural change: golden="
                            + goldenLine + " candidate=" + candidateLine);
            return;
        }
        String feature = featureField >= 0 && featureField < goldenFields.length
                ? goldenFields[featureField].trim() : null;
        for (int f = 0; f < goldenFields.length; f++) {
            if (goldenFields[f].equals(candidateFields[f])) continue;
            String columnName;
            if (featureField < 0) {
                columnName = f < header.length ? header[f].trim() : null;
            } else {
                // Only the two value fields carry a measurement; naming them
                // explicitly keeps identity and statistics fields exact.
                columnName = (f == featureField + 1 || f == featureField + 3)
                        ? headingForFeature(feature, header) : null;
            }
            ColumnContract.Entry contract = columnName == null ? null
                    : ColumnContract.lookup(columnName, golden.harnessCase);
            if (contract == null) {
                report.add(1, golden.id(), key + " field " + f
                                + (columnName == null ? "" : " '" + columnName + "'"),
                        "golden=" + goldenFields[f] + " candidate=" + candidateFields[f]);
                continue;
            }
            compareCell(report, golden.id(), golden.harnessCase, contract, columnName,
                    key + " '" + columnName + "'", goldenFields[f], candidateFields[f],
                    depthOf(golden));
        }
    }

    /** Resolves a scores-file feature name onto a column heading with its unit. */
    private static String headingForFeature(String feature, String[] header) {
        if (feature == null || feature.isEmpty()) return null;
        for (int i = 0; i < header.length; i++) {
            String name = header[i].trim();
            if (name.equals(feature) || name.startsWith(feature + " (")) return name;
        }
        return feature;
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

    /**
     * The one warning the unified engine adds that the old paths never could.
     *
     * <p>Hyperstack input now says which channel and frame it measured. That is
     * the visible half of the ratified fix for silent plane truncation, so a
     * candidate carrying exactly this extra warning is reported once at Tier 3.
     * Any other new warning, and any change to a warning the golden already had,
     * stays Tier 1.
     */
    private static final String CHANNEL_SELECTION_NOTICE = "measuring channel";

    private static void diffWarningText(CaptureRecord golden, CaptureRecord candidate, Report report) {
        int goldenCount;
        int candidateCount;
        try {
            goldenCount = Integer.parseInt(golden.get("warnings.count"));
            candidateCount = Integer.parseInt(candidate.get("warnings.count"));
        } catch (NumberFormatException absent) {
            return;
        }
        for (int i = 0; i < goldenCount; i++) {
            String key = "warnings." + i;
            if (!golden.get(key).equals(candidate.get(key))) {
                report.add(1, golden.id(), key,
                        "golden=" + golden.get(key) + " candidate=" + candidate.get(key));
            }
        }
        if (candidateCount == goldenCount) return;

        boolean everyExtraIsTheNotice = candidateCount > goldenCount;
        for (int i = goldenCount; i < candidateCount && everyExtraIsTheNotice; i++) {
            everyExtraIsTheNotice = candidate.get("warnings." + i)
                    .contains(CHANNEL_SELECTION_NOTICE);
        }
        if (everyExtraIsTheNotice) {
            report.add(3, golden.id(), "channel/frame notice (declared)",
                    "the unified engine reports which channel and frame it measured: "
                            + candidate.get("warnings." + goldenCount));
            return;
        }
        report.add(1, golden.id(), "warnings.count",
                "golden=" + goldenCount + " candidate=" + candidateCount);
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
        String[] objectHeader = splitCsv(golden.get("csv.objects.0"));
        String[] scoreHeader = splitCsv(golden.get("csv.scores.0"));
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            if (!key.startsWith("csv.")) continue;
            String goldenLine = golden.get(key);
            String candidateLine = candidate.get(key);
            if (goldenLine.equals(candidateLine)) continue;

            // TOLERANCES.md section 1 asks for batch_objects.csv to be "tiered per
            // column". Comparing whole lines as exact text did not do that, and it
            // double-counted: a declared Tier 2 column difference - the calibrated
            // Surface value, float-accumulated in the reference and double in the
            // candidate - reappeared here as a Tier 1 line mismatch.
            //
            // Structure is still Tier 1. Only a cell whose column has a contract is
            // tiered, and it is tiered by that contract.
            if (key.startsWith("csv.objects.") && !key.endsWith(".0")) {
                diffCsvFields(golden, candidate, report, key, objectHeader,
                        goldenLine, candidateLine, -1);
                continue;
            }
            if (key.startsWith("csv.scores.") && !key.endsWith(".0")) {
                // Field 4 names the feature; the value columns are 5 (RawValue) and
                // 7 (ScoringValue). Everything else - object identity, units,
                // z-score, percentile, reference statistics - stays exact, so a
                // changed object still fails as loudly as before.
                // The objects header is passed deliberately: it is where a feature
                // name such as "Surface" can be resolved to the heading that
                // carries its unit, and so to its column contract. The scores
                // header names positions, not measurements.
                diffCsvFields(golden, candidate, report, key, objectHeader,
                        goldenLine, candidateLine, 4);
                continue;
            }
            report.add(1, golden.id(), key,
                    "golden=" + goldenLine + " candidate=" + candidateLine);
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
