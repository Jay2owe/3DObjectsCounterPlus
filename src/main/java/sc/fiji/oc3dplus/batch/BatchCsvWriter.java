package sc.fiji.oc3dplus.batch;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BatchCsvWriter {

    private BatchCsvWriter() {}

    static void writeManifest(File file,
                              String batchRunId,
                              List<OC3DPlusBatchRunner.ManifestEntry> entries) throws IOException {
        BufferedWriter writer = writer(file);
        try {
            row(writer, "BatchRunId", "SourceRelativePath", "SourceLastModified",
                    "Status", "Error", "ObjectCount", "ContributedScoreRows",
                    "PixelWidth", "PixelHeight", "PixelDepth", "SpatialUnit",
                    "MacroOptions", "FractalXYEnabled", "CompositesEnabled",
                    "ArborizationEnabled",
                    "ArborizationBackend", "PluginVersion");
            for (OC3DPlusBatchRunner.ManifestEntry entry : entries) {
                row(writer, batchRunId, entry.relativePath,
                        Long.toString(entry.sourceLastModified),
                        entry.status, entry.error,
                        Integer.toString(entry.objectCount),
                        Integer.toString(entry.contributedScoreRows),
                        number(entry.pixelWidth), number(entry.pixelHeight),
                        number(entry.pixelDepth),
                        entry.spatialUnit, entry.macroOptions,
                        Boolean.toString(entry.fractalXYEnabled),
                        Boolean.toString(entry.compositesEnabled),
                        Boolean.toString(entry.arborizationEnabled),
                        entry.arborizationBackend, entry.pluginVersion);
            }
        } finally {
            writer.close();
        }
    }

    static void writeObjects(File file,
                             String batchRunId,
                             List<OC3DPlusBatchRunner.ObjectRecord> records,
                             Iterable<String> tableHeadings) throws IOException {
        Set<String> legacyHeadings = new LinkedHashSet<String>();
        Set<String> extendedHeadings = new LinkedHashSet<String>();
        addHeadings(tableHeadings, legacyHeadings, extendedHeadings);
        for (OC3DPlusBatchRunner.ObjectRecord record : records) {
            addHeadings(record.cells.keySet(), legacyHeadings, extendedHeadings);
        }
        List<String> headings = new ArrayList<String>(legacyHeadings);
        headings.addAll(extendedHeadings);
        List<String> header = new ArrayList<String>();
        header.add("BatchRunId");
        header.add("SourceRelativePath");
        header.add("SourceImageIndex");
        header.add("SourceObjectLabel");
        header.addAll(headings);

        BufferedWriter writer = writer(file);
        try {
            row(writer, header.toArray(new String[header.size()]));
            for (OC3DPlusBatchRunner.ObjectRecord record : records) {
                List<String> values = new ArrayList<String>();
                values.add(batchRunId);
                values.add(record.relativePath);
                values.add(Integer.toString(record.sourceImageIndex));
                values.add(record.sourceObjectLabel);
                for (String heading : headings) {
                    String value = record.cells.get(heading);
                    values.add(value == null ? "" : value);
                }
                row(writer, values.toArray(new String[values.size()]));
            }
        } finally {
            writer.close();
        }
    }

    private static void addHeadings(Iterable<String> headings,
                                    Set<String> legacy,
                                    Set<String> extended) {
        if (headings == null) return;
        for (String heading : headings) {
            if (heading == null || heading.trim().isEmpty()) continue;
            if (isExtendedHeading(heading)) extended.add(heading);
            else legacy.add(heading);
        }
    }

    private static boolean isExtendedHeading(String heading) {
        if (heading == null) return false;
        return "Morph_FractalDim_XY".equals(heading)
                || "Morph_FractalDim_XY_R2".equals(heading)
                || "Morph_LacunarityMean_XY".equals(heading)
                || "Morph_LacunaritySpread_XY".equals(heading)
                || "Morph_RI".equals(heading)
                || "Morph_SRI".equals(heading)
                || "Morph_PB".equals(heading)
                || "Morph_MP".equals(heading)
                || "Morph_VSD".equals(heading)
                || heading.startsWith("Morph_Sholl")
                || heading.startsWith("Morph_Skeleton")
                || "Morph_ArborizationBackend".equals(heading);
    }

    static void writeScores(File file,
                            String batchRunId,
                            List<OC3DPlusBatchRunner.ObjectRecord> records) throws IOException {
        List<String> presentFeatures = new ArrayList<String>();
        for (String feature : ScoreFeatureCatalog.features()) {
            if (isPresent(records, feature)) presentFeatures.add(feature);
        }

        BufferedWriter writer = writer(file);
        try {
            row(writer, "BatchRunId", "SourceRelativePath", "SourceImageIndex",
                    "SourceObjectLabel", "Feature", "RawValue", "RawUnit",
                    "ScoringValue", "ScoringUnit",
                    "WithinBatchZ", "WithinBatchPercentile",
                    "ValidN", "ReferenceMean", "ReferenceSD", "ReferenceScope");
            for (String feature : presentFeatures) {
                double[] raw = rawValues(records, feature);
                double[] referenceValues = scoreValues(records, feature);
                WithinBatchScorer.Result scored = WithinBatchScorer.score(referenceValues);
                double[] z = scored.zScores();
                double[] percentile = scored.percentiles();
                Reference reference = reference(referenceValues);
                for (int i = 0; i < records.size(); i++) {
                    OC3DPlusBatchRunner.ObjectRecord record = records.get(i);
                    row(writer, batchRunId, record.relativePath,
                            Integer.toString(record.sourceImageIndex),
                            record.sourceObjectLabel, feature, number(raw[i]),
                            rawUnit(record, feature), number(referenceValues[i]),
                            ScoreFeatureCatalog.scoringUnit(feature),
                            number(z[i]), number(percentile[i]),
                            Integer.toString(scored.finiteCount()), number(reference.mean),
                            number(reference.standardDeviation),
                            "all successful objects in this BatchRunId");
                }
            }
        } finally {
            writer.close();
        }
    }

    private static String rawUnit(OC3DPlusBatchRunner.ObjectRecord record,
                                  String feature) {
        String unit = record.rawUnits.get(feature);
        return unit == null ? "" : unit;
    }

    private static boolean isPresent(List<OC3DPlusBatchRunner.ObjectRecord> records,
                                     String feature) {
        for (OC3DPlusBatchRunner.ObjectRecord record : records) {
            if (record.numericCells.containsKey(feature)) return true;
        }
        return false;
    }

    private static double[] rawValues(List<OC3DPlusBatchRunner.ObjectRecord> records,
                                      String feature) {
        double[] values = new double[records.size()];
        java.util.Arrays.fill(values, Double.NaN);
        for (int i = 0; i < records.size(); i++) {
            Double value = records.get(i).numericCells.get(feature);
            if (value != null) values[i] = value.doubleValue();
        }
        return values;
    }

    private static double[] scoreValues(List<OC3DPlusBatchRunner.ObjectRecord> records,
                                        String feature) {
        double[] values = new double[records.size()];
        java.util.Arrays.fill(values, Double.NaN);
        for (int i = 0; i < records.size(); i++) {
            Double value = records.get(i).scoreCells.get(feature);
            if (value != null) values[i] = value.doubleValue();
        }
        return values;
    }

    private static Reference reference(double[] values) {
        int count = 0;
        double mean = 0.0;
        for (int i = 0; i < values.length; i++) {
            if (!Double.isFinite(values[i])) continue;
            count++;
            mean += (values[i] - mean) / count;
        }
        if (count == 0) return new Reference(Double.NaN, Double.NaN);
        double sumSquares = 0.0;
        for (int i = 0; i < values.length; i++) {
            if (!Double.isFinite(values[i])) continue;
            double difference = values[i] - mean;
            sumSquares += difference * difference;
        }
        return new Reference(mean, Math.sqrt(sumSquares / count));
    }

    private static BufferedWriter writer(File file) throws IOException {
        return Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
    }

    private static void row(BufferedWriter writer, String... values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) writer.write(',');
            writer.write(quote(values[i]));
        }
        writer.newLine();
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value;
        boolean quote = safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0
                || safe.indexOf('\r') >= 0 || safe.indexOf('\n') >= 0;
        if (!quote) return safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static String number(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "NaN";
    }

    private static final class Reference {
        final double mean;
        final double standardDeviation;

        Reference(double mean, double standardDeviation) {
            this.mean = mean;
            this.standardDeviation = standardDeviation;
        }
    }
}
