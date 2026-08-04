package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;
import ij.io.FileSaver;
import sc.fiji.oc3dplus.batch.OC3DPlusBatchParameters;
import sc.fiji.oc3dplus.batch.OC3DPlusBatchRunner;
import sc.fiji.oc3dplus.ui.OC3DPlusDialogModel;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Captures the three batch CSVs, which are user-visible outputs in their own
 * right (harness section 4).
 *
 * <p>{@code batch_scores.csv} is the highest-sensitivity output in the plugin:
 * within-batch z-scores and percentiles are computed against the whole
 * population, so a single object appearing, disappearing or changing volume
 * shifts the mean and standard deviation and therefore <b>every</b> score row.
 * Its diffs are Tier 1 always and are traced back to the causing object, never
 * toleranced away.
 *
 * <p>{@code BatchRunId}, {@code SourceLastModified} and {@code PluginVersion} are
 * blanked before recording. They are the only fields that legitimately change
 * between two identical runs.
 */
public final class BatchCapture {

    /** One folder of inputs plus the settings to run over it. */
    public static final class Scenario {
        public final String name;
        public final List<String> fixtures;
        public final boolean fractal;
        public final boolean composites;
        public final boolean arborization;
        public final boolean mixedUnits;

        Scenario(String name,
                 List<String> fixtures,
                 boolean fractal,
                 boolean composites,
                 boolean arborization,
                 boolean mixedUnits) {
            this.name = name;
            this.fixtures = Collections.unmodifiableList(new ArrayList<String>(fixtures));
            this.fractal = fractal;
            this.composites = composites;
            this.arborization = arborization;
            this.mixedUnits = mixedUnits;
        }

        public String recordName() {
            return "batch-" + name;
        }
    }

    private BatchCapture() {}

    public static List<Scenario> scenarios() {
        List<Scenario> out = new ArrayList<Scenario>();
        out.add(new Scenario("legacy-columns",
                names("blobs-8bit", "blobs-16bit", "sphere-solid"),
                false, false, false, false));
        // One uncalibrated input among calibrated ones makes the batch drop the
        // physical-unit score columns across the whole batch - a cross-image
        // interaction that only the batch outputs can show.
        out.add(new Scenario("mixed-units",
                names("sphere-iso-um", "sphere-aniso-z5", "sphere-solid"),
                false, false, false, true));
        out.add(new Scenario("extended-columns",
                names("blobs-8bit", "sphere-solid"),
                true, true, true, false));
        return Collections.unmodifiableList(out);
    }

    private static List<String> names(String... values) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < values.length; i++) out.add(values[i]);
        return out;
    }

    public static CaptureRecord capture(Scenario scenario, File workingRoot) throws IOException {
        CaptureRecord record = new CaptureRecord(scenario.recordName(), HarnessCase.A, "batch");
        record.put("note", "Batch CSV outputs. BatchRunId, SourceLastModified and "
                + "PluginVersion are blanked; every remaining line is Tier 1.");
        record.put("config.fractal", scenario.fractal);
        record.put("config.composites", scenario.composites);
        record.put("config.arborization", scenario.arborization);
        record.put("config.mixedUnits", scenario.mixedUnits);
        record.put("config.inputs", ColumnContract.join(scenario.fixtures, " "));

        File input = new File(workingRoot, scenario.name);
        if (!input.isDirectory() && !input.mkdirs()) {
            throw new IOException("Could not create batch input directory " + input);
        }
        List<File> files = new ArrayList<File>();
        for (int i = 0; i < scenario.fixtures.size(); i++) {
            String fixtureName = scenario.fixtures.get(i);
            Fixture fixture = FixtureCorpus.byName(fixtureName);
            ImagePlus image = fixture.createInput();
            try {
                // Two-digit prefix keeps the on-disk order independent of the
                // filesystem's own ordering, so the manifest row order is fixed.
                File file = new File(input, pad(i) + "-" + fixtureName + ".tif");
                if (!new FileSaver(image).saveAsTiffStack(file.getAbsolutePath())) {
                    throw new IOException("Could not write batch fixture " + file);
                }
                files.add(file);
            } finally {
                Stacks.discard(image);
            }
        }

        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.threshold = 100;
        model.minSize = 1;
        model.maxSize = Integer.MAX_VALUE;
        model.measureFractalXY = scenario.fractal;
        model.measureComposites = scenario.composites;
        model.measureArborization = scenario.arborization;

        OC3DPlusBatchRunner.Result result = OC3DPlusBatchRunner.run(
                new OC3DPlusBatchParameters(input, files, model), null);

        record.put("outcome", "ok");
        record.put("inputCount", result.inputCount);
        record.put("successfulCount", result.successfulCount);
        record.put("failedCount", result.failedCount);
        record.put("objectCount", result.objectCount);

        recordCsv(record, "manifest", new File(result.outputDirectory, "batch_manifest.csv"),
                names("BatchRunId", "SourceLastModified", "PluginVersion"));
        recordCsv(record, "objects", new File(result.outputDirectory, "batch_objects.csv"),
                names("BatchRunId"));
        recordCsv(record, "scores", new File(result.outputDirectory, "batch_scores.csv"),
                names("BatchRunId"));
        return record;
    }

    private static String pad(int index) {
        return index < 10 ? "0" + index : Integer.toString(index);
    }

    private static void recordCsv(CaptureRecord record,
                                  String name,
                                  File file,
                                  List<String> blankedColumns) throws IOException {
        if (!file.isFile()) {
            record.put("csv." + name, "absent");
            return;
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String[] lines = text.split("\\R", -1);
        List<Integer> blanked = new ArrayList<Integer>();
        int emitted = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            List<String> cells = parseCsvLine(lines[i]);
            if (i == 0) {
                for (int c = 0; c < cells.size(); c++) {
                    if (blankedColumns.contains(cells.get(c))) blanked.add(Integer.valueOf(c));
                }
            }
            for (int b = 0; b < blanked.size(); b++) {
                int column = blanked.get(b).intValue();
                if (column < cells.size()) cells.set(column, "<normalised>");
            }
            record.put("csv." + name + "." + emitted, join(cells));
            emitted++;
        }
        record.put("csv." + name + ".lines", emitted);
    }

    /** RFC 4180 field splitter, matching how {@code BatchCsvWriter} quotes. */
    static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private static String join(List<String> cells) {
        List<String> safe = new ArrayList<String>(cells.size());
        for (int i = 0; i < cells.size(); i++) {
            safe.add(cells.get(i).replace('|', '/'));
        }
        return ColumnContract.join(safe, ",");
    }
}
