package sc.fiji.oc3dplus.batch;

import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import sc.fiji.oc3dplus.ObjectsCounter3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusParameters;
import sc.fiji.oc3dplus.api.OC3DPlusResult;
import sc.fiji.oc3dplus.ui.OC3DPlusDialogModel;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Streams a folder batch one image at a time and writes auditable CSV files. */
public final class OC3DPlusBatchRunner {

    public interface ProgressListener {
        void progress(int completed, int total, String relativePath);
    }

    public static final class Result {
        public final String batchRunId;
        public final File outputDirectory;
        public final int inputCount;
        public final int successfulCount;
        public final int failedCount;
        public final int objectCount;

        Result(String batchRunId,
               File outputDirectory,
               int inputCount,
               int successfulCount,
               int failedCount,
               int objectCount) {
            this.batchRunId = batchRunId;
            this.outputDirectory = outputDirectory;
            this.inputCount = inputCount;
            this.successfulCount = successfulCount;
            this.failedCount = failedCount;
            this.objectCount = objectCount;
        }
    }

    private OC3DPlusBatchRunner() {}

    public static Result run(OC3DPlusBatchParameters parameters,
                             ProgressListener progress) throws IOException {
        if (parameters == null) {
            throw new IllegalArgumentException("parameters must not be null.");
        }
        String initialRunId = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
                .format(new Date());
        File root = new File(parameters.inputDirectory, "3D Objects Counter Plus Batch");
        File output = createUniqueOutputDirectory(root, initialRunId);
        String runId = output.getName();
        File incompleteMarker = new File(output, ".incomplete");
        Files.createFile(incompleteMarker.toPath());

        List<ManifestEntry> manifest = new ArrayList<ManifestEntry>();
        List<ObjectRecord> objects = new ArrayList<ObjectRecord>();
        Set<String> objectHeadings = new LinkedHashSet<String>();
        int successful = 0;
        int failed = 0;
        OC3DPlusDialogModel settings = parameters.settings();
        String macroOptions = settings.toMacroOptions();
        String version = pluginVersion();

        for (int i = 0; i < parameters.inputFiles.size(); i++) {
            File file = parameters.inputFiles.get(i);
            String relative = relativePath(parameters.inputDirectory, file);
            if (progress != null) progress.progress(i, parameters.inputFiles.size(), relative);
            checkInterrupted();
            ManifestEntry entry = new ManifestEntry(
                    relative,
                    file.lastModified(),
                    i + 1,
                    macroOptions,
                    settings.measureFractalXY,
                    settings.measureComposites,
                    settings.measureArborization,
                    version);
            manifest.add(entry);
            ImagePlus input = null;
            OC3DPlusResult result = null;
            final List<String> warnings = new ArrayList<String>();
            try {
                input = IJ.openImage(file.getAbsolutePath());
                if (input == null) {
                    throw new IOException("Fiji could not open this image.");
                }
                captureCalibration(entry, input.getCalibration());
                OC3DPlusParameters runParameters = settings.toParameters(
                        null, new OC3DPlusParameters.WarningSink() {
                            @Override public void warn(String message) {
                                if (message != null && !message.trim().isEmpty()) {
                                    warnings.add(message.trim());
                                }
                            }
                        });
                result = OC3DPlus.count(input, runParameters);
                ResultsTable table = result.statistics();
                collectHeadings(objectHeadings, table);
                entry.objectCount = result.objectCount();
                entry.status = entry.objectCount == 0 ? "zero_objects" : "success";
                entry.error = joinWarnings(warnings);
                addObjectRecords(objects, relative, i + 1, table,
                        input.getCalibration(), warnings);
                entry.arborizationBackend = arborizationBackends(table);
                entry.error = joinWarnings(warnings);
                successful++;
            } catch (Exception failure) {
                if (Thread.currentThread().isInterrupted()) {
                    InterruptedIOException interrupted = new InterruptedIOException(
                            "Batch cancelled while processing " + relative + ".");
                    interrupted.initCause(failure);
                    throw interrupted;
                }
                entry.status = "failed";
                entry.error = messageOf(failure);
                failed++;
            } finally {
                if (result != null) discard(result.labelImage());
                discard(input);
            }
        }
        if (progress != null) {
            progress.progress(parameters.inputFiles.size(),
                    parameters.inputFiles.size(), "Writing CSV files");
        }
        checkInterrupted();
        excludeIncompatiblePhysicalFeatures(manifest, objects);
        recalculateContributedScoreRows(manifest, objects);
        addConstantScoreWarnings(manifest, objects);

        File manifestTemp = new File(output, ".batch_manifest.csv.tmp");
        File objectsTemp = new File(output, ".batch_objects.csv.tmp");
        File scoresTemp = new File(output, ".batch_scores.csv.tmp");
        BatchCsvWriter.writeManifest(manifestTemp, runId, manifest);
        checkInterrupted();
        BatchCsvWriter.writeObjects(
                objectsTemp, runId, objects, objectHeadings);
        checkInterrupted();
        BatchCsvWriter.writeScores(scoresTemp, runId, objects);
        checkInterrupted();
        moveIntoPlace(manifestTemp, new File(output, "batch_manifest.csv"));
        checkInterrupted();
        moveIntoPlace(objectsTemp, new File(output, "batch_objects.csv"));
        checkInterrupted();
        moveIntoPlace(scoresTemp, new File(output, "batch_scores.csv"));
        Files.delete(incompleteMarker.toPath());

        return new Result(runId, output, parameters.inputFiles.size(),
                successful, failed, objects.size());
    }

    static File createUniqueOutputDirectory(File root, String initialRunId)
            throws IOException {
        Files.createDirectories(root.toPath());
        int suffix = 1;
        while (true) {
            String name = suffix == 1
                    ? initialRunId : initialRunId + "-" + suffix;
            File candidate = new File(root, name);
            try {
                Files.createDirectory(candidate.toPath());
                return candidate;
            } catch (FileAlreadyExistsException collision) {
                suffix++;
            }
        }
    }

    private static void addObjectRecords(List<ObjectRecord> output,
                                         String relativePath,
                                         int sourceImageIndex,
                                         ResultsTable table,
                                         Calibration calibration,
                                         List<String> warnings) {
        if (table == null || table.size() == 0) return;
        double micronsPerUnit = micronsPerUnit(calibration);
        Set<String> warnedUnits = new LinkedHashSet<String>();
        String spatialUnit = calibrationUnit(calibration);
        String[] headings = table.getHeadings();
        for (int row = 0; row < table.size(); row++) {
            String label = cell(table, "Label", row);
            if (label.isEmpty()) label = Integer.toString(row + 1);
            ObjectRecord record = new ObjectRecord(
                    relativePath, sourceImageIndex, label);
            if (headings != null) {
                for (int h = 0; h < headings.length; h++) {
                    String heading = headings[h];
                    if (heading == null || heading.trim().isEmpty()) continue;
                    String value = cell(table, heading, row);
                    record.cells.put(heading, value);
                    String feature = ScoreFeatureCatalog.canonicalFeature(heading);
                    if (feature != null) {
                        double numeric = numericCell(table, heading, row);
                        record.numericCells.put(feature, Double.valueOf(numeric));
                        record.rawUnits.put(feature,
                                rawUnit(feature, spatialUnit));
                        double scoreValue = numeric;
                        int dimensionPower =
                                ScoreFeatureCatalog.physicalDimensionPower(feature);
                        if (dimensionPower > 0 && Double.isFinite(numeric)) {
                            if (Double.isFinite(micronsPerUnit)) {
                                scoreValue = numeric * Math.pow(
                                        micronsPerUnit, dimensionPower);
                            } else {
                                scoreValue = Double.NaN;
                                if (warnings != null && warnedUnits.add(feature)) {
                                    warnings.add(feature
                                            + " scores were excluded because spatial unit '"
                                            + spatialUnit
                                            + "' cannot be converted to micrometres.");
                                }
                            }
                        }
                        record.scoreCells.put(feature, Double.valueOf(scoreValue));
                    }
                }
            }
            output.add(record);
        }
    }

    private static void collectHeadings(Set<String> output,
                                        ResultsTable table) {
        if (output == null || table == null) return;
        String[] headings = table.getHeadings();
        if (headings == null) return;
        for (int i = 0; i < headings.length; i++) {
            String heading = headings[i];
            if (heading != null && !heading.trim().isEmpty()) {
                output.add(heading);
            }
        }
    }

    private static String rawUnit(String feature, String spatialUnit) {
        int power = ScoreFeatureCatalog.physicalDimensionPower(feature);
        if (power == 1) return spatialUnit;
        if (power > 1) return spatialUnit + "^" + power;
        return ScoreFeatureCatalog.scoringUnit(feature);
    }

    private static void excludeIncompatiblePhysicalFeatures(
            List<ManifestEntry> manifest,
            List<ObjectRecord> objects) {
        for (String feature : ScoreFeatureCatalog.features()) {
            if (ScoreFeatureCatalog.physicalDimensionPower(feature) <= 0) continue;
            boolean hasIncompatibleValue = false;
            for (int i = 0; i < objects.size(); i++) {
                ObjectRecord record = objects.get(i);
                Double raw = record.numericCells.get(feature);
                Double scoring = record.scoreCells.get(feature);
                if (raw != null && Double.isFinite(raw.doubleValue())
                        && (scoring == null || !Double.isFinite(scoring.doubleValue()))) {
                    hasIncompatibleValue = true;
                    break;
                }
            }
            if (!hasIncompatibleValue) continue;
            for (int i = 0; i < objects.size(); i++) {
                ObjectRecord record = objects.get(i);
                if (record.numericCells.containsKey(feature)) {
                    record.scoreCells.put(feature, Double.valueOf(Double.NaN));
                }
            }
            String warning = feature + " scores were excluded across the batch because "
                    + "at least one successful image had an absent or incompatible "
                    + "spatial unit.";
            for (int i = 0; i < manifest.size(); i++) {
                ManifestEntry entry = manifest.get(i);
                if (!"failed".equals(entry.status)) entry.appendWarning(warning);
            }
        }
    }

    private static void recalculateContributedScoreRows(
            List<ManifestEntry> manifest,
            List<ObjectRecord> objects) {
        int emittedFeaturesPerObject = 0;
        for (String feature : ScoreFeatureCatalog.features()) {
            for (int i = 0; i < objects.size(); i++) {
                if (objects.get(i).numericCells.containsKey(feature)) {
                    emittedFeaturesPerObject++;
                    break;
                }
            }
        }
        for (int i = 0; i < manifest.size(); i++) {
            manifest.get(i).contributedScoreRows = 0;
        }
        for (int i = 0; i < objects.size(); i++) {
            ObjectRecord record = objects.get(i);
            int manifestIndex = record.sourceImageIndex - 1;
            if (manifestIndex < 0 || manifestIndex >= manifest.size()) continue;
            manifest.get(manifestIndex).contributedScoreRows +=
                    emittedFeaturesPerObject;
        }
    }

    private static void addConstantScoreWarnings(List<ManifestEntry> manifest,
                                                 List<ObjectRecord> objects) {
        for (String feature : ScoreFeatureCatalog.features()) {
            double first = Double.NaN;
            int finite = 0;
            boolean differs = false;
            for (int i = 0; i < objects.size(); i++) {
                Double value = objects.get(i).scoreCells.get(feature);
                if (value == null || !Double.isFinite(value.doubleValue())) continue;
                if (finite == 0) first = value.doubleValue();
                else if (Double.compare(first, value.doubleValue()) != 0) differs = true;
                finite++;
            }
            if (finite < 3 || differs) continue;
            String warning = "Within-batch z-score unavailable for constant feature "
                    + feature + "; tied finite values receive percentile 50.";
            for (int i = 0; i < manifest.size(); i++) {
                ManifestEntry entry = manifest.get(i);
                if (!"failed".equals(entry.status)) entry.appendWarning(warning);
            }
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException(
                    "Batch cancelled; incomplete output remains marked in the output folder.");
        }
    }

    private static void moveIntoPlace(File temporary, File destination)
            throws IOException {
        try {
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String cell(ResultsTable table, String heading, int row) {
        double numeric = numericCell(table, heading, row);
        if (Double.isFinite(numeric)) return Double.toString(numeric);
        try {
            String text = table.getStringValue(heading, row);
            if (text == null) return "";
            return text;
        } catch (RuntimeException unreadable) {
            return "";
        }
    }

    private static double numericCell(ResultsTable table, String heading, int row) {
        try {
            double value = table.getValue(heading, row);
            return Double.isFinite(value) ? value : Double.NaN;
        } catch (RuntimeException unreadable) {
            return Double.NaN;
        }
    }

    private static String arborizationBackends(ResultsTable table) {
        if (table == null || table.getColumnIndex("Morph_ArborizationBackend") < 0) return "";
        Set<String> backends = new LinkedHashSet<String>();
        for (int row = 0; row < table.size(); row++) {
            String value = cell(table, "Morph_ArborizationBackend", row).trim();
            if (!value.isEmpty()) backends.add(value);
        }
        StringBuilder joined = new StringBuilder();
        for (String backend : backends) {
            if (joined.length() > 0) joined.append("; ");
            joined.append(backend);
        }
        return joined.toString();
    }

    private static void captureCalibration(ManifestEntry entry, Calibration calibration) {
        if (calibration == null) return;
        entry.spatialUnit = calibrationUnit(calibration);
        entry.pixelWidth = calibration.pixelWidth;
        entry.pixelHeight = calibration.pixelHeight;
        entry.pixelDepth = calibration.pixelDepth;
    }

    private static String calibrationUnit(Calibration calibration) {
        if (calibration == null || calibration.getUnit() == null
                || calibration.getUnit().trim().isEmpty()) {
            return "pixel";
        }
        return calibration.getUnit().trim();
    }

    private static double micronsPerUnit(Calibration calibration) {
        String unit = calibrationUnit(calibration).toLowerCase(Locale.ROOT)
                .replace('\u00b5', 'u').replace('\u03bc', 'u');
        if ("um".equals(unit) || "micron".equals(unit) || "microns".equals(unit)
                || "micrometer".equals(unit) || "micrometers".equals(unit)
                || "micrometre".equals(unit) || "micrometres".equals(unit)) {
            return 1.0;
        }
        if ("nm".equals(unit) || "nanometer".equals(unit) || "nanometers".equals(unit)
                || "nanometre".equals(unit) || "nanometres".equals(unit)) {
            return 0.001;
        }
        if ("mm".equals(unit) || "millimeter".equals(unit) || "millimeters".equals(unit)
                || "millimetre".equals(unit) || "millimetres".equals(unit)) {
            return 1000.0;
        }
        if ("cm".equals(unit)) return 10000.0;
        if ("m".equals(unit) || "meter".equals(unit) || "metre".equals(unit)) return 1000000.0;
        return Double.NaN;
    }

    private static String relativePath(File root, File file) {
        try {
            return root.toPath().toAbsolutePath().normalize()
                    .relativize(file.toPath().toAbsolutePath().normalize())
                    .toString().replace(File.separatorChar, '/');
        } catch (RuntimeException outsideRoot) {
            return file.getName();
        }
    }

    private static String pluginVersion() {
        Package pluginPackage = ObjectsCounter3DPlus.class.getPackage();
        String version = pluginPackage == null ? null : pluginPackage.getImplementationVersion();
        return version == null || version.trim().isEmpty() ? "development" : version;
    }

    private static String joinWarnings(List<String> warnings) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < warnings.size(); i++) {
            if (i > 0) joined.append(" | ");
            joined.append(warnings.get(i));
        }
        return joined.toString();
    }

    private static String messageOf(Throwable failure) {
        if (failure == null) return "Unknown error";
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }

    private static void discard(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }

    static final class ManifestEntry {
        final String relativePath;
        final long sourceLastModified;
        final int sourceImageIndex;
        final String macroOptions;
        final boolean fractalXYEnabled;
        final boolean compositesEnabled;
        final boolean arborizationEnabled;
        final String pluginVersion;
        String status = "failed";
        String error = "";
        int objectCount;
        int contributedScoreRows;
        String spatialUnit = "";
        double pixelWidth = Double.NaN;
        double pixelHeight = Double.NaN;
        double pixelDepth = Double.NaN;
        String arborizationBackend = "";

        ManifestEntry(String relativePath,
                      long sourceLastModified,
                      int sourceImageIndex,
                      String macroOptions,
                      boolean fractalXYEnabled,
                      boolean compositesEnabled,
                      boolean arborizationEnabled,
                      String pluginVersion) {
            this.relativePath = relativePath;
            this.sourceLastModified = sourceLastModified;
            this.sourceImageIndex = sourceImageIndex;
            this.macroOptions = macroOptions;
            this.fractalXYEnabled = fractalXYEnabled;
            this.compositesEnabled = compositesEnabled;
            this.arborizationEnabled = arborizationEnabled;
            this.pluginVersion = pluginVersion;
        }

        void appendWarning(String warning) {
            if (warning == null || warning.trim().isEmpty()) return;
            error = error == null || error.isEmpty()
                    ? warning.trim() : error + " | " + warning.trim();
        }
    }

    static final class ObjectRecord {
        final String relativePath;
        final int sourceImageIndex;
        final String sourceObjectLabel;
        final Map<String, String> cells = new LinkedHashMap<String, String>();
        final Map<String, Double> numericCells = new LinkedHashMap<String, Double>();
        final Map<String, Double> scoreCells = new LinkedHashMap<String, Double>();
        final Map<String, String> rawUnits = new LinkedHashMap<String, String>();

        ObjectRecord(String relativePath,
                     int sourceImageIndex,
                     String sourceObjectLabel) {
            this.relativePath = relativePath;
            this.sourceImageIndex = sourceImageIndex;
            this.sourceObjectLabel = sourceObjectLabel;
        }
    }
}
