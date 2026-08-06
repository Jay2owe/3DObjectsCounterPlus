package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;
import ij.measure.ResultsTable;
import sc.fiji.oc3d.core.map.ObjectMapBuilder;
import sc.fiji.oc3dplus.engine.ObjectsCounter3DWrapper;
import sc.fiji.oc3dplus.engine.SummaryReporter;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusParameters;
import sc.fiji.oc3dplus.api.OC3DPlusResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runs one (fixture, configuration) pair against the current build and records
 * every user-visible output (harness section 4).
 *
 * <p>A run that throws is <b>recorded, not skipped</b>. The classic
 * {@code Counter3D} throws on a legal image - a stack whose final voxel is an
 * isolated object - and that behaviour is part of what the current build does, so
 * it belongs in the goldens with its exception text rather than being left as a
 * hole in the corpus.
 */
public final class Capture {

    /**
     * Fixed overlay budget and zero memory reserve, so map construction does not
     * depend on how much heap happens to be free. Without this the overlay is
     * skipped or kept according to runtime memory and the harness stops being
     * deterministic.
     */
    private static final String OVERLAY_LABEL_BUDGET = "100000";

    private Capture() {}

    public static CaptureRecord capture(Fixture fixture, RunConfig config) {
        CaptureRecord record = new CaptureRecord(fixture.name, fixture.harnessCase, config.name);
        record.put("note", fixture.note());
        record.putLines(config.describe());
        record.put("sweep", fixture.sweep.name());

        String previousOverlayBudget = System.getProperty(ObjectMapBuilder.MAX_OVERLAY_LABELS_PROPERTY);
        String previousReserve =
                System.getProperty(ObjectMapBuilder.OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY);
        System.setProperty(ObjectMapBuilder.MAX_OVERLAY_LABELS_PROPERTY, OVERLAY_LABEL_BUDGET);
        System.setProperty(ObjectMapBuilder.OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY, "0");
        try {
            if (fixture.harnessCase == HarnessCase.C) {
                captureLabelImagePath(fixture, config, record);
            } else {
                captureDetectionPath(fixture, config, record);
            }
        } catch (RuntimeException failure) {
            recordFailure(record, failure);
        } catch (Error failure) {
            recordFailure(record, failure);
        } finally {
            restore(ObjectMapBuilder.MAX_OVERLAY_LABELS_PROPERTY, previousOverlayBudget);
            restore(ObjectMapBuilder.OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY, previousReserve);
        }
        return record;
    }

    // ── Cases A and B: threshold, label, measure ─────────────────────────

    private static void captureDetectionPath(Fixture fixture,
                                             RunConfig config,
                                             CaptureRecord record) {
        ImagePlus input = null;
        ImagePlus redirect = null;
        ImagePlus labelImage = null;
        try {
            input = fixture.createInput();
            record.put("input.dims", ImageDigest.dimensions(input));
            record.put("input.bitDepth", input.getBitDepth());
            record.put("input.channels", Math.max(1, input.getNChannels()));
            record.put("input.frames", Math.max(1, input.getNFrames()));
            record.put("input.calibration", calibrationOf(input));

            redirect = config.redirect ? fixture.createRedirectImage() : null;
            final List<String> warnings = new ArrayList<String>();
            OC3DPlusParameters.WarningSink sink = new OC3DPlusParameters.WarningSink() {
                @Override public void warn(String message) {
                    warnings.add(message);
                }
            };

            OC3DPlus.Builder builder = OC3DPlus.builder()
                    .threshold(config.threshold)
                    .minSize(config.minSize)
                    .maxSize(config.maxSize)
                    .excludeOnEdges(config.excludeOnEdges)
                    .intensityImage(redirect)
                    .measureFractalXY(config.fractal)
                    .measureCompositeIndices(config.composites)
                    .measureArborization(config.arborization)
                    .warningSink(sink);
            for (int i = 0; i < config.filters.size(); i++) {
                RunConfig.Filter filter = config.filters.get(i);
                builder.addFilter(filter.feature, filter.operator, filter.value);
            }

            OC3DPlusResult result = OC3DPlus.count(input, builder.build());
            labelImage = result.labelImage();

            record.put("outcome", "ok");
            record.put("objectCount", result.objectCount());
            record.put("foundObjects", result.foundObjects());
            record.put("survivingPerFilter", intList(result.survivingPerFilter()));
            record.put("filterLabels", ColumnContract.join(list(result.filterLabels()), " "));
            recordWarnings(record, warnings);
            recordStatistics(record, result.statistics());
            recordLabelImage(record, labelImage);
            record.put("summary", SummaryReporter.format(
                    fixture.name, result, config.minSize, config.maxSize, config.threshold));
            if (config.maps) {
                recordMaps(record, labelImage, result.statistics(), fixture.name);
            }
        } finally {
            Stacks.discard(labelImage);
            Stacks.discard(redirect);
            Stacks.discard(input);
        }
    }

    // ── Case C: measure a supplied label image ──────────────────────────

    private static void captureLabelImagePath(Fixture fixture,
                                              RunConfig config,
                                              CaptureRecord record) {
        ImagePlus input = null;
        ImagePlus redirect = null;
        ObjectsCounter3DWrapper.Result result = null;
        try {
            input = fixture.createInput();
            record.put("input.dims", ImageDigest.dimensions(input));
            record.put("input.bitDepth", input.getBitDepth());
            record.put("input.channels", Math.max(1, input.getNChannels()));
            record.put("input.frames", Math.max(1, input.getNFrames()));
            record.put("input.calibration", calibrationOf(input));
            record.put("input.labelDigest", ImageDigest.labelDigest(input));

            redirect = config.redirect || config.maps ? fixture.createRedirectImage() : null;
            record.put("redirect.calibration", redirect == null ? "none" : calibrationOf(redirect));

            result = new ObjectsCounter3DWrapper().fromLabelImage(
                    input,
                    config.redirect ? redirect : null,
                    config.minSize,
                    config.maxSize,
                    true,
                    false);

            ResultsTable statistics = result.getStatistics();
            record.put("outcome", "ok");
            record.put("objectCount", statistics == null ? 0 : statistics.size());
            record.put("foundObjects", result.isFoundObjects());
            recordStatistics(record, statistics);
            recordLabelImage(record, result.getObjectsMap());
            if (config.maps) {
                recordMaps(record, result.getObjectsMap(), statistics, fixture.name);
            }
        } finally {
            if (result != null) {
                Stacks.discard(result.getObjectsMap());
                Stacks.discard(result.getMaskedImage());
            }
            Stacks.discard(redirect);
            Stacks.discard(input);
        }
    }

    // ── recording helpers ───────────────────────────────────────────────

    /**
     * HotSpot's {@code OmitStackTraceInFastThrow} optimisation replaces a hot
     * implicit-exception throw site with a preallocated instance carrying no
     * message and no stack trace. So for those exception types the message depends
     * on how warm the JIT is, and recording it verbatim makes the golden depend on
     * JVM state rather than on the plugin - the harness's own determinism check
     * caught exactly that on the {@code corner-x1y1z1} fixture, where the message
     * was present on the first pass and null on the second.
     *
     * <p>The class is stable under the optimisation and is recorded and compared.
     * The message text for the {@code Counter3D} defect is pinned in
     * {@link Counter3DDefectTest} instead, which is a better home for it: one
     * place, with the explanation, rather than repeated across 15 configurations.
     */
    private static final List<String> JIT_ERASED_MESSAGE_TYPES = Collections.unmodifiableList(
            java.util.Arrays.asList(
                    "java.lang.NullPointerException",
                    "java.lang.ArithmeticException",
                    "java.lang.ArrayIndexOutOfBoundsException",
                    "java.lang.IndexOutOfBoundsException",
                    "java.lang.StringIndexOutOfBoundsException",
                    "java.lang.ArrayStoreException",
                    "java.lang.ClassCastException",
                    "java.lang.NegativeArraySizeException"));

    private static void recordFailure(CaptureRecord record, Throwable failure) {
        record.put("outcome", "exception");
        String type = failure.getClass().getName();
        record.put("exception.class", type);
        if (JIT_ERASED_MESSAGE_TYPES.contains(type)) {
            record.put("exception.message",
                    "<not recorded: HotSpot OmitStackTraceInFastThrow makes the message of this "
                            + "exception type depend on JIT state; see Counter3DDefectTest>");
        } else {
            String message = failure.getMessage();
            record.put("exception.message", message == null ? "<null>" : oneLine(message));
        }
        Throwable cause = failure.getCause();
        record.put("exception.cause", cause == null ? "none" : cause.getClass().getName());
    }

    /**
     * Above this many objects the rows are replaced by a digest. Three fixtures in
     * the corpus reach it - the 65,534/65,535/65,536 processor-boundary ladder -
     * and recording every row for those would put roughly 30 MB of text per fixture
     * into a Dropbox-synced git repository. The digest still detects any change to
     * any cell; what it costs is per-cell diagnosis, and the record says so rather
     * than truncating quietly.
     */
    static final int MAX_RECORDED_ROWS = 2000;

    private static void recordStatistics(CaptureRecord record, ResultsTable statistics) {
        if (statistics == null) {
            record.setColumns(Collections.<String>emptyList());
            return;
        }
        List<String> headings = new ArrayList<String>();
        String[] raw = statistics.getHeadings();
        if (raw != null) {
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] != null && !raw[i].trim().isEmpty()) headings.add(raw[i]);
            }
        }
        record.setColumns(headings);
        record.put("stats.rowCount", statistics.size());

        boolean digestOnly = statistics.size() > MAX_RECORDED_ROWS;
        List<String> serialisedRows = digestOnly ? new ArrayList<String>() : null;
        for (int row = 0; row < statistics.size(); row++) {
            List<String> cells = new ArrayList<String>(headings.size());
            for (int i = 0; i < headings.size(); i++) {
                cells.add(cell(statistics, headings.get(i), row));
            }
            if (digestOnly) {
                serialisedRows.add(ColumnContract.join(cells, "|"));
            } else {
                record.addRow(cells);
            }
        }
        if (digestOnly) {
            record.put("stats.rowDigest", ImageDigest.textDigest(serialisedRows));
            record.put("stats.rowCapNotice", "rows replaced by stats.rowDigest above "
                    + MAX_RECORDED_ROWS + " objects; any cell change is still detected, but "
                    + "per-column tolerance and per-object diagnosis are not applied here");
        }
    }

    /**
     * Mirrors {@code OC3DPlusBatchRunner.cell}: a finite number is written
     * losslessly, anything else falls back to the table's text, so string
     * columns such as {@code Morph_ArborizationBackend} survive.
     */
    private static String cell(ResultsTable table, String heading, int row) {
        if (!ColumnContract.isTextColumn(heading)) {
            double numeric = Double.NaN;
            try {
                numeric = table.getValue(heading, row);
            } catch (RuntimeException unreadable) {
                numeric = Double.NaN;
            }
            if (Double.isFinite(numeric)) return CaptureRecord.number(numeric);
        }
        try {
            String text = table.getStringValue(heading, row);
            return text == null ? "" : oneLine(text);
        } catch (RuntimeException unreadable) {
            return "";
        }
    }

    private static void recordLabelImage(CaptureRecord record, ImagePlus labelImage) {
        record.put("label.dims", ImageDigest.dimensions(labelImage));
        if (labelImage == null) {
            record.put("label.present", false);
            return;
        }
        record.put("label.present", true);
        record.put("label.bitDepth", labelImage.getBitDepth());
        record.put("label.digest", ImageDigest.labelDigest(labelImage));
        record.put("label.partition", ImageDigest.partitionDigest(labelImage));
        List<Integer> labels = ImageDigest.positiveLabels(labelImage);
        record.put("label.distinct", labels.size());
        record.put("label.dense", ImageDigest.labelsAreDense(labelImage));
        record.put("label.max", labels.isEmpty()
                ? "0" : Integer.toString(labels.get(labels.size() - 1).intValue()));
        record.put("label.rle", ImageDigest.runLengths(labelImage));
    }

    private static void recordMaps(CaptureRecord record,
                                   ImagePlus labelImage,
                                   ResultsTable statistics,
                                   String sourceTitle) {
        if (labelImage == null) {
            record.put("maps.built", false);
            return;
        }
        ImagePlus objects = null;
        ImagePlus surfaces = null;
        ImagePlus centroids = null;
        ImagePlus centersOfMass = null;
        try {
            objects = ObjectMapBuilder.objectMap(labelImage, statistics, sourceTitle);
            surfaces = ObjectMapBuilder.surfaceMap(labelImage, statistics, sourceTitle);
            centroids = ObjectMapBuilder.centroidMap(labelImage, statistics, sourceTitle);
            centersOfMass = ObjectMapBuilder.centerOfMassMap(labelImage, statistics, sourceTitle);
            record.put("maps.built", true);
            recordMap(record, "objects", objects);
            recordMap(record, "surfaces", surfaces);
            recordMap(record, "centroids", centroids);
            recordMap(record, "centersOfMass", centersOfMass);
        } catch (ObjectMapBuilder.OptionalMapMemoryException guard) {
            record.put("maps.built", false);
            record.put("maps.guard", guard.getClass().getSimpleName());
        } finally {
            Stacks.discard(objects);
            Stacks.discard(surfaces);
            Stacks.discard(centroids);
            Stacks.discard(centersOfMass);
        }
    }

    private static void recordMap(CaptureRecord record, String name, ImagePlus map) {
        record.put("map." + name + ".dims", ImageDigest.dimensions(map));
        record.put("map." + name + ".pixels", ImageDigest.pixelDigest(map));
        record.put("map." + name + ".partition", ImageDigest.partitionDigest(map));
        record.put("map." + name + ".overlay", ImageDigest.overlaySummary(map));
        record.put("map." + name + ".overlaySkipped",
                map == null ? "none" : String.valueOf(ObjectMapBuilder.overlaySkippedReason(map)));
    }

    private static void recordWarnings(CaptureRecord record, List<String> warnings) {
        List<String> sorted = new ArrayList<String>(warnings);
        Collections.sort(sorted);
        record.put("warnings.count", sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            record.put("warnings." + i, oneLine(sorted.get(i)));
        }
    }

    private static String calibrationOf(ImagePlus image) {
        if (image == null || image.getCalibration() == null) return "none";
        return image.getCalibration().getUnit()
                + " " + CaptureRecord.number(image.getCalibration().pixelWidth)
                + "/" + CaptureRecord.number(image.getCalibration().pixelHeight)
                + "/" + CaptureRecord.number(image.getCalibration().pixelDepth);
    }

    private static String intList(int[] values) {
        List<String> parts = new ArrayList<String>(values.length);
        for (int i = 0; i < values.length; i++) parts.add(Integer.toString(values[i]));
        return ColumnContract.join(parts, ",");
    }

    private static List<String> list(String[] values) {
        List<String> out = new ArrayList<String>(values.length);
        for (int i = 0; i < values.length; i++) out.add(oneLine(values[i]));
        return out;
    }

    private static String oneLine(String text) {
        if (text == null) return "";
        return text.replace('\r', ' ').replace('\n', ' ').replace('|', '/');
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
