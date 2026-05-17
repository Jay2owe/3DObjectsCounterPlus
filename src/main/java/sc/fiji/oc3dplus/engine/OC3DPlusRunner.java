package sc.fiji.oc3dplus.engine;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import sc.fiji.oc3dplus.api.MorphPredicate;
import sc.fiji.oc3dplus.api.OC3DPlusParameters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Engine: run 3D object counting, compute per-object morphology features,
 * filter by a list of {@link MorphPredicate}s, relabel survivors
 * consecutively, and assemble final statistics. The preferred API is {@link #runResult},
 * which returns the label image and statistics as one value object so the
 * statistics table does not need to live in ImageJ image properties.
 */
public final class OC3DPlusRunner {

    public static final String OBJECT_STATS_PROPERTY = "sc.fiji.oc3dplus.objectStats";
    public static final String PREDICATE_COUNTS_PROPERTY = "sc.fiji.oc3dplus.predicateCounts";
    public static final String PREDICATE_LABELS_PROPERTY = "sc.fiji.oc3dplus.predicateLabels";

    public static final class Result {
        private final ResultsTable statistics;
        private final ImagePlus labelImage;
        private final int[] predicateCounts;
        private final String[] predicateLabels;

        Result(ResultsTable statistics,
               ImagePlus labelImage,
               int[] predicateCounts,
               String[] predicateLabels) {
            this.statistics = statistics == null ? new ResultsTable() : statistics;
            this.labelImage = labelImage;
            this.predicateCounts = predicateCounts == null ? new int[0] : predicateCounts.clone();
            this.predicateLabels = predicateLabels == null ? new String[0] : predicateLabels.clone();
        }

        public ResultsTable getStatistics() {
            return statistics;
        }

        public ImagePlus getLabelImage() {
            return labelImage;
        }

        public int[] getPredicateCounts() {
            return predicateCounts.clone();
        }

        public String[] getPredicateLabels() {
            return predicateLabels.clone();
        }
    }

    interface CounterBackend {
        ObjectsCounter3DWrapper.Result run(
                ImagePlus img,
                int threshold,
                int minSize,
                int maxSize,
                boolean excludeOnEdges,
                boolean redirect,
                boolean wantObjectsMap,
                boolean wantMaskedImage);

        ObjectsCounter3DWrapper.Result runNative(
                ImagePlus img,
                int threshold,
                int minSize,
                int maxSize,
                boolean excludeOnEdges,
                ImagePlus redirectImage,
                boolean wantObjectsMap,
                boolean wantMaskedImage,
                ProgressReporter progress,
                boolean finishProgress);

        ObjectsCounter3DWrapper.Result fromLabelImage(
                ImagePlus labelImage,
                ImagePlus redirectImage,
                int minSize,
                int maxSize,
                boolean wantObjectsMap,
                boolean wantMaskedImage,
                ProgressReporter progress,
                boolean finishProgress);
    }

    private static final class DefaultCounterBackend implements CounterBackend {
        private final ObjectsCounter3DWrapper wrapper = new ObjectsCounter3DWrapper();

        @Override public ObjectsCounter3DWrapper.Result run(
                ImagePlus img,
                int threshold,
                int minSize,
                int maxSize,
                boolean excludeOnEdges,
                boolean redirect,
                boolean wantObjectsMap,
                boolean wantMaskedImage) {
            return wrapper.run(img, threshold, minSize, maxSize, excludeOnEdges,
                    redirect, wantObjectsMap, wantMaskedImage);
        }

        @Override public ObjectsCounter3DWrapper.Result runNative(
                ImagePlus img,
                int threshold,
                int minSize,
                int maxSize,
                boolean excludeOnEdges,
                ImagePlus redirectImage,
                boolean wantObjectsMap,
                boolean wantMaskedImage,
                ProgressReporter progress,
                boolean finishProgress) {
            return wrapper.runNative(img, threshold, minSize, maxSize, excludeOnEdges,
                    redirectImage, wantObjectsMap, wantMaskedImage, progress, finishProgress);
        }

        @Override public ObjectsCounter3DWrapper.Result fromLabelImage(
                ImagePlus labelImage,
                ImagePlus redirectImage,
                int minSize,
                int maxSize,
                boolean wantObjectsMap,
                boolean wantMaskedImage,
                ProgressReporter progress,
                boolean finishProgress) {
            return wrapper.fromLabelImage(labelImage, redirectImage, minSize, maxSize,
                    wantObjectsMap, wantMaskedImage, progress, finishProgress);
        }
    }

    private static final class DetectionResult {
        final ResultsTable statistics;
        final ImagePlus labelImage;
        final boolean classic;

        DetectionResult(ResultsTable statistics, ImagePlus labelImage, boolean classic) {
            this.statistics = statistics == null ? new ResultsTable() : statistics;
            this.labelImage = labelImage;
            this.classic = classic;
        }
    }

    private final CounterBackend counterBackend;

    private static final String FEATURE_VOLUME = "volume";
    private static final String FEATURE_VOLUME_CALIBRATED = "volume_calibrated";
    private static final String FEATURE_SURFACE_AREA = "surface_area";
    private static final String FEATURE_SPHERICITY = "sphericity";
    private static final String FEATURE_ELONGATION = "elongation";
    private static final String FEATURE_COMPACTNESS = "compactness";
    private static final String FEATURE_MEAN_INTENSITY = "mean_intensity";
    private static final String FEATURE_MAX_INTENSITY = "max_intensity";
    private static final String FEATURE_FERET_DIAMETER_MAX = "feret_diameter_max";
    private static final int FEATURE_BIT_VOLUME = 1;
    private static final int FEATURE_BIT_VOLUME_CALIBRATED = 1 << 1;
    private static final int FEATURE_BIT_SURFACE_AREA = 1 << 2;
    private static final int FEATURE_BIT_SPHERICITY = 1 << 3;
    private static final int FEATURE_BIT_ELONGATION = 1 << 4;
    private static final int FEATURE_BIT_COMPACTNESS = 1 << 5;
    private static final int FEATURE_BIT_MEAN_INTENSITY = 1 << 6;
    private static final int FEATURE_BIT_MAX_INTENSITY = 1 << 7;
    private static final int FEATURE_BIT_FERET_DIAMETER_MAX = 1 << 8;
    private static final int MAX_LABEL_PALETTE_ENTRIES = 8 * 1024 * 1024;
    private static final String[] PLUS_MORPHOLOGY_COLUMNS = {
            "Morph_Sphericity",
            "Morph_Compactness",
            "Morph_Elongation",
            "Morph_Feret3D_um"
    };
    private static final String[] ALWAYS_REPORTED_MORPHOLOGY_FEATURES = {
            FEATURE_SPHERICITY,
            FEATURE_COMPACTNESS,
            FEATURE_ELONGATION,
            FEATURE_FERET_DIAMETER_MAX
    };

    private static final Set<String> SUPPORTED_FEATURES = new HashSet<String>(Arrays.asList(
            FEATURE_VOLUME,
            FEATURE_VOLUME_CALIBRATED,
            FEATURE_SURFACE_AREA,
            FEATURE_SPHERICITY,
            FEATURE_ELONGATION,
            FEATURE_COMPACTNESS,
            FEATURE_MEAN_INTENSITY,
            FEATURE_MAX_INTENSITY,
            FEATURE_FERET_DIAMETER_MAX));

    public OC3DPlusRunner() {
        this(new DefaultCounterBackend());
    }

    OC3DPlusRunner(CounterBackend counterBackend) {
        this.counterBackend = counterBackend == null
                ? new DefaultCounterBackend() : counterBackend;
    }

    /**
     * Run detection, optional morphology filtering, and final measurement.
     *
     * <p>Compatibility wrapper for callers that still expect result metadata
     * on ImageJ properties. New code should use {@link #runResult(ImagePlus,
     * OC3DPlusParameters)} to avoid duplicate statistics-table ownership.
     *
     * @param channelImage source 3D image; must not be {@code null}
     * @param params run parameters, or {@code null} to use engine defaults
     * @return filtered label image, or {@code null} only if no label image can
     *         be created for the input
     */
    public ImagePlus run(ImagePlus channelImage, OC3DPlusParameters params) {
        Result result = runResult(channelImage, params);
        if (result == null) return null;
        attachResultProperties(result);
        return result.getLabelImage();
    }

    /**
     * Run detection, optional morphology filtering, and final measurement.
     *
     * <p>Returns the label image, statistics table, and filter feedback as one
     * value object. This method does not show images, write to ImageJ's global
     * Results window, or store the statistics table on the returned image.
     *
     * @param channelImage source 3D image; must not be {@code null}
     * @param params run parameters, or {@code null} to use engine defaults
     * @return engine result, or {@code null} only if no label image can be
     *         created for the input
     */
    public Result runResult(ImagePlus channelImage, OC3DPlusParameters params) {
        if (channelImage == null) {
            throw new IllegalArgumentException(
                    "channelImage must not be null (channelImage=null; expected a 3D ImagePlus).");
        }
        String imageTitle = titleOf(channelImage);
        OC3DPlusParameters safe = params == null
                ? new OC3DPlusParameters(0, 0, Integer.MAX_VALUE, false,
                Collections.<MorphPredicate>emptyList(), null, null)
                : params;
        List<MorphPredicate> predicates = safe.morphPredicates;
        boolean hasMorphFilters = predicates != null && !predicates.isEmpty();
        ProgressReporter progress = ProgressReporter.steps(hasMorphFilters ? 7 : 4);
        ImagePlus labelImage = null;
        boolean returningLabel = false;

        try {
            DetectionResult detected = canUseClassicCounter(channelImage, safe)
                    ? detectWithClassicCounter(channelImage, safe, counterBackend, progress)
                    : detectWithNativeCounter(channelImage, safe, counterBackend, progress);

            labelImage = detected.labelImage;
            if (labelImage == null) {
                labelImage = emptyLabelMapLike(channelImage);
            }
            if (labelImage == null) {
                progress.error("Could not create label image for '" + imageTitle + "'");
                return null;
            }
            labelImage.setTitle("3D Objects Counter+ label image");

            ResultsTable detectedStats = detected.statistics;
            if (!hasMorphFilters) {
                progress.step("Measuring morphology");
                ResultsTable stats = detectedStats == null ? new ResultsTable() : detectedStats;
                appendEmptyMorphologyColumns(stats);
                ImagePlus featureIntensityImage = matchingIntensityImageOrNull(
                        labelImage, safe.intensityImage);
                FeatureContext featureContext = new FeatureContext(Collections.<MorphPredicate>emptyList(),
                        featureIntensityImage, safe.warningSink);
                Map<Integer, FeatureValues> features = computeFeaturesByLabel(labelImage, featureContext);
                appendReferencedMorphColumnsFromFeatures(stats, featureContext, features);
                if (featureIntensityImage != null) {
                    applyRedirectedIntensityColumns(stats, features);
                }
                progress.finishStep();
                progress.finish("Complete for '" + imageTitle + "'");
                returningLabel = true;
                return new Result(stats, labelImage, new int[0], new String[0]);
            }

            progress.step("Measuring morphology");
            ImagePlus requestedIntensityImage = safe.intensityImage == null
                    ? channelImage : safe.intensityImage;
            ImagePlus featureIntensityImage = matchingIntensityImageOrNull(
                    labelImage, requestedIntensityImage);
            FeatureContext featureContext = new FeatureContext(predicates,
                    featureIntensityImage, safe.warningSink);
            Map<Integer, FeatureValues> features = computeFeaturesByLabel(labelImage, featureContext);
            progress.finishStep();

            progress.step("Filtering objects");
            FilterResult filter = evaluateFilters(features, predicates, safe.warningSink);
            progress.finishStep();

            progress.step("Renumbering structures");
            ImagePlus filteredLabels = relabelConsecutivelyInPlace(labelImage, filter.oldToNewLabel);
            progress.finishStep();

            progress.step("Measuring filtered labels");
            ResultsTable stats = buildFilteredStatsFromDetectedStats(
                    detectedStats,
                    features,
                    filter.oldToNewLabel,
                    featureContext,
                    safe.intensityImage != null);
            progress.finishStep();
            progress.finish("Complete for '" + imageTitle + "'");
            returningLabel = true;
            return new Result(stats, filteredLabels,
                    toIntArray(filter.survivingAfterPredicate),
                    toStringArray(predicates));
        } catch (RuntimeException e) {
            progress.error("Error while counting '" + imageTitle + "'");
            throw e;
        } finally {
            if (!returningLabel) {
                discard(labelImage);
            }
        }
    }

    private static DetectionResult detectWithClassicCounter(ImagePlus channelImage,
                                                            OC3DPlusParameters safe,
                                                            CounterBackend counterBackend,
                                                            ProgressReporter progress) {
        ProgressReporter safeProgress = progress == null ? ProgressReporter.none() : progress;
        safeProgress.step("Finding structures with classic 3D OC");
        ObjectsCounter3DWrapper.Result detected = counterBackend.run(
                channelImage,
                safe.threshold,
                safe.minSize,
                safe.maxSize,
                safe.excludeOnEdges,
                false,
                true,
                false);
        safeProgress.finishStep();
        return new DetectionResult(
                detected == null ? null : detected.getStatistics(),
                detected == null ? null : detected.getObjectsMap(),
                true);
    }

    private static DetectionResult detectWithNativeCounter(ImagePlus channelImage,
                                                           OC3DPlusParameters safe,
                                                           CounterBackend counterBackend,
                                                           ProgressReporter progress) {
        ObjectsCounter3DWrapper.Result detected = counterBackend.runNative(
                channelImage,
                safe.threshold,
                safe.minSize,
                safe.maxSize,
                safe.excludeOnEdges,
                safe.intensityImage,
                true,
                false,
                progress,
                false);
        return new DetectionResult(
                detected == null ? null : detected.getStatistics(),
                detected == null ? null : detected.getObjectsMap(),
                false);
    }

    private static boolean canUseClassicCounter(ImagePlus image, OC3DPlusParameters params) {
        if (image == null || params == null) return false;
        // Redirect changes intensity measurement only; it must not force the
        // crash-prone native mcib3d detection path for compatible source stacks.
        int bitDepth = image.getBitDepth();
        if (bitDepth != 8 && bitDepth != 16) return false;
        int channels = Math.max(1, image.getNChannels());
        int frames = Math.max(1, image.getNFrames());
        return channels == 1 && frames == 1;
    }

    private static ImagePlus matchingIntensityImageOrNull(ImagePlus labelImage,
                                                          ImagePlus intensityImage) {
        if (labelImage == null || intensityImage == null) return null;
        if (labelImage.getWidth() != intensityImage.getWidth()
                || labelImage.getHeight() != intensityImage.getHeight()) {
            return null;
        }
        ImageStack labelStack = labelImage.getStack();
        ImageStack intensityStack = intensityImage.getStack();
        if (labelStack == null || intensityStack == null) return null;
        return labelStack.getSize() == intensityStack.getSize() ? intensityImage : null;
    }

    /**
     * Returns a copy of the statistics stored on a label image by
     * {@link #run(ImagePlus, OC3DPlusParameters)}.
     *
     * <p>If {@code labelImage} is {@code null}, or if it has no statistics
     * property, this method returns an empty {@link ResultsTable}. It never
     * returns {@code null}.
     */
    public static ResultsTable statsProperty(ImagePlus labelImage) {
        Object property = labelImage == null ? null : labelImage.getProperty(OBJECT_STATS_PROPERTY);
        return property instanceof ResultsTable ? copyOf((ResultsTable) property) : new ResultsTable();
    }

    private static void attachResultProperties(Result result) {
        if (result == null || result.getLabelImage() == null) return;
        ImagePlus labelImage = result.getLabelImage();
        labelImage.setProperty(OBJECT_STATS_PROPERTY, result.getStatistics());
        labelImage.setProperty(PREDICATE_COUNTS_PROPERTY, result.getPredicateCounts());
        labelImage.setProperty(PREDICATE_LABELS_PROPERTY, result.getPredicateLabels());
    }

    /**
     * Append the extra statistics columns that this runner exposes for
     * referenced morph features: sphericity, compactness, elongation, maximum
     * Feret diameter, and maximum intensity. Returns a copied table; the input
     * table is not mutated.
     */
    public static ResultsTable appendReferencedMorphColumns(ImagePlus labelImage,
                                                            ImagePlus intensityImage,
                                                            ResultsTable stats,
                                                            List<MorphPredicate> predicates,
                                                            OC3DPlusParameters.WarningSink warningSink) {
        ResultsTable safeStats = stats == null ? new ResultsTable() : copyOf(stats);
        appendEmptyMorphologyColumns(safeStats);
        if (labelImage == null || safeStats.size() == 0) {
            return safeStats;
        }
        FeatureContext featureContext = new FeatureContext(predicates,
                intensityImage, warningSink);
        Map<Integer, FeatureValues> features = computeFeaturesByLabel(labelImage, featureContext);
        return appendReferencedMorphColumnsToStats(safeStats, featureContext, features);
    }

    private static ResultsTable appendReferencedMorphColumnsFromFeatures(
            ResultsTable stats,
            FeatureContext featureContext,
            Map<Integer, FeatureValues> features) {
        ResultsTable safeStats = stats == null ? new ResultsTable() : stats;
        return appendReferencedMorphColumnsToStats(safeStats, featureContext, features);
    }

    private static void appendEmptyMorphologyColumns(ResultsTable stats) {
        if (stats == null) return;
        for (int i = 0; i < PLUS_MORPHOLOGY_COLUMNS.length; i++) {
            String column = PLUS_MORPHOLOGY_COLUMNS[i];
            if (stats.getColumnIndex(column) >= 0) continue;
            if (stats.size() == 0) {
                stats.setHeading(nextHeadingIndex(stats), column);
                continue;
            }
            for (int row = 0; row < stats.size(); row++) {
                stats.setValue(column, row, Double.NaN);
            }
        }
    }

    private static int nextHeadingIndex(ResultsTable stats) {
        String[] headings = stats == null ? null : stats.getHeadings();
        return headings == null ? 0 : headings.length;
    }

    private static ResultsTable appendReferencedMorphColumnsToStats(
            ResultsTable safeStats,
            FeatureContext featureContext,
            Map<Integer, FeatureValues> features) {
        if (featureContext == null || features == null || features.isEmpty() || safeStats.size() == 0) {
            return safeStats;
        }
        for (int row = 0; row < safeStats.size(); row++) {
            int label = labelForRow(safeStats, row);
            FeatureValues values = features.get(Integer.valueOf(label));
            if (values == null) continue;
            if (featureContext.needs(FEATURE_SPHERICITY)) {
                setFinite(safeStats, "Morph_Sphericity", row, values.sphericity);
            }
            if (featureContext.needs(FEATURE_COMPACTNESS)) {
                setFinite(safeStats, "Morph_Compactness", row, values.compactness);
            }
            if (featureContext.needs(FEATURE_ELONGATION)) {
                setFinite(safeStats, "Morph_Elongation", row, values.elongation);
            }
            if (featureContext.needs(FEATURE_FERET_DIAMETER_MAX)) {
                setFinite(safeStats, "Morph_Feret3D_um", row, values.feretDiameterMax);
            }
            if (featureContext.needs(FEATURE_MAX_INTENSITY)) {
                setFinite(safeStats, "Max", row, values.maxIntensity);
            }
        }
        return safeStats;
    }

    private static void applyRedirectedIntensityColumns(
            ResultsTable stats,
            Map<Integer, FeatureValues> features) {
        if (stats == null || features == null || features.isEmpty() || stats.size() == 0) {
            return;
        }
        for (int row = 0; row < stats.size(); row++) {
            int label = labelForRow(stats, row);
            FeatureValues values = features.get(Integer.valueOf(label));
            if (values == null) continue;
            setFiniteOrNaN(stats, "IntDen", row, values.intensitySum);
            setFiniteOrNaN(stats, "Mean", row, values.meanIntensity);
            setFiniteOrNaN(stats, "StdDev", row, values.intensityStdDev);
            setFiniteOrNaN(stats, "Min", row, values.minIntensity);
            setFiniteOrNaN(stats, "Max", row, values.maxIntensity);
            setFiniteOrNaN(stats, "XM", row, values.centerOfMassX);
            setFiniteOrNaN(stats, "YM", row, values.centerOfMassY);
            setFiniteOrNaN(stats, "ZM", row, values.centerOfMassZ);
        }
    }

    private static Map<Integer, FeatureValues> remapFeatures(
            Map<Integer, FeatureValues> features,
            Map<Integer, Integer> oldToNewLabel) {
        if (features == null || features.isEmpty()
                || oldToNewLabel == null || oldToNewLabel.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Integer, FeatureValues> out = new LinkedHashMap<Integer, FeatureValues>();
        for (Map.Entry<Integer, Integer> entry : oldToNewLabel.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) continue;
            int newLabel = entry.getValue().intValue();
            if (newLabel <= 0) continue;
            FeatureValues values = features.get(entry.getKey());
            if (values != null) {
                out.put(Integer.valueOf(newLabel), values);
            }
        }
        return out;
    }

    private static ResultsTable buildFilteredStatsFromDetectedStats(
            ResultsTable detectedStats,
            Map<Integer, FeatureValues> features,
            Map<Integer, Integer> oldToNewLabel,
            FeatureContext featureContext,
            boolean useRedirectedIntensityColumns) {
        ResultsTable out = new ResultsTable();
        if (detectedStats == null || oldToNewLabel == null || oldToNewLabel.isEmpty()) {
            appendEmptyMorphologyColumns(out);
            return out;
        }

        String[] headings = detectedStats.getHeadings();
        for (Map.Entry<Integer, Integer> entry : oldToNewLabel.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) continue;
            int oldLabel = entry.getKey().intValue();
            int newLabel = entry.getValue().intValue();
            if (oldLabel <= 0 || newLabel <= 0) continue;
            int sourceRow = rowForLabel(detectedStats, oldLabel);
            if (sourceRow < 0) continue;

            int row = out.size();
            out.incrementCounter();
            copyRow(detectedStats, sourceRow, out, row, headings);
            out.setValue("Label", row, newLabel);
        }

        appendEmptyMorphologyColumns(out);
        Map<Integer, FeatureValues> remappedFeatures = remapFeatures(features, oldToNewLabel);
        ResultsTable withMorphology = appendReferencedMorphColumnsFromFeatures(
                out,
                featureContext,
                remappedFeatures);
        if (useRedirectedIntensityColumns) {
            applyRedirectedIntensityColumns(withMorphology, remappedFeatures);
        }
        return withMorphology;
    }

    private static int rowForLabel(ResultsTable table, int label) {
        if (table == null) return -1;
        for (int row = 0; row < table.size(); row++) {
            if (labelForRow(table, row) == label) return row;
        }
        return -1;
    }

    private static void copyRow(ResultsTable src,
                                int srcRow,
                                ResultsTable dst,
                                int dstRow,
                                String[] headings) {
        if (src == null || dst == null || headings == null) return;
        for (int h = 0; h < headings.length; h++) {
            String heading = headings[h];
            if (heading == null || heading.trim().isEmpty()) continue;
            try {
                dst.setValue(heading, dstRow, src.getValue(heading, srcRow));
            } catch (RuntimeException unreadableCell) {
                // ResultsTable columns can be sparse; skip cells that cannot be copied.
            }
        }
    }

    private static Map<Integer, FeatureValues> computeFeaturesByLabel(ImagePlus labelImage,
                                                                      FeatureContext context) {
        if (labelImage == null || labelImage.getStack() == null) {
            return Collections.emptyMap();
        }
        if (context == null) {
            return Collections.emptyMap();
        }
        LabelFeatureAccumulator.Result measured = LabelFeatureAccumulator.scan(
                labelImage,
                context.intensityImage,
                labelImage.getCalibration());
        boolean calibratedVolumeAvailable = Double.isFinite(calibratedVoxelVolume(labelImage));

        Map<Integer, FeatureValues> out = new LinkedHashMap<Integer, FeatureValues>();
        List<Integer> labels = measured.labelsSorted();
        for (int i = 0; i < labels.size(); i++) {
            int label = labels.get(i).intValue();
            LabelFeatureAccumulator.FeatureValues src = measured.valuesForLabel(label);
            if (src == null) continue;
            FeatureValues values = new FeatureValues();

            if (context.needs(FEATURE_VOLUME)) {
                values.volume = src.voxelCount;
            }
            if (context.needs(FEATURE_VOLUME_CALIBRATED)) {
                if (calibratedVolumeAvailable) {
                    values.volumeCalibrated = src.calibratedVolume;
                } else {
                    values.markUnavailable(FEATURE_VOLUME_CALIBRATED);
                }
            }
            if (context.needs(FEATURE_SURFACE_AREA)) {
                values.surfaceArea = src.surfaceArea;
            }
            if (context.needs(FEATURE_SPHERICITY)) {
                values.sphericity = sphericity(src.voxelCount, src.surfaceArea);
            }
            if (context.needs(FEATURE_COMPACTNESS)) {
                values.compactness = compactness(src.voxelCount, src.surfaceArea);
            }
            if (context.needs(FEATURE_MEAN_INTENSITY)) {
                values.meanIntensity = src.meanIntensity();
                if (!Double.isFinite(values.meanIntensity)) {
                    values.markUnavailable(FEATURE_MEAN_INTENSITY);
                }
            }
            if (context.needs(FEATURE_MAX_INTENSITY)) {
                values.maxIntensity = src.maxIntensity();
                if (!Double.isFinite(values.maxIntensity)) {
                    values.markUnavailable(FEATURE_MAX_INTENSITY);
                }
            }
            if (context.needs(FEATURE_ELONGATION)) {
                values.elongation = src.elongation();
            }
            if (context.needs(FEATURE_FERET_DIAMETER_MAX)) {
                values.feretDiameterMax = src.feretDiameterMax();
            }
            if (context.hasIntensityImage()) {
                values.intensitySum = src.intensitySum();
                values.meanIntensity = src.meanIntensity();
                values.intensityStdDev = src.intensityStdDev();
                values.minIntensity = src.intensityMin();
                values.maxIntensity = src.intensityMax();
                values.centerOfMassX = src.centerOfMassX();
                values.centerOfMassY = src.centerOfMassY();
                values.centerOfMassZ = src.centerOfMassZ();
            }

            out.put(Integer.valueOf(label), values);
        }
        return out;
    }

    private static FilterResult evaluateFilters(Map<Integer, FeatureValues> features,
                                                List<MorphPredicate> predicates,
                                                OC3DPlusParameters.WarningSink warningSink) {
        FilterResult result = new FilterResult();
        if (features == null || features.isEmpty()) {
            if (predicates != null) {
                for (int i = 0; i < predicates.size(); i++) {
                    result.survivingAfterPredicate.add(Integer.valueOf(0));
                }
            }
            return result;
        }

        List<Integer> ordered = new ArrayList<Integer>(features.keySet());
        Collections.sort(ordered);
        Set<Integer> surviving = new HashSet<Integer>(ordered);
        Set<String> warnedUnavailablePredicates = new HashSet<String>();
        for (int i = 0; i < predicates.size(); i++) {
            MorphPredicate predicate = predicates.get(i);
            for (Integer label : ordered) {
                if (!surviving.contains(label)) continue;
                FeatureValues values = features.get(label);
                if (!predicatePasses(predicate, values, warningSink, warnedUnavailablePredicates)) {
                    surviving.remove(label);
                }
            }
            result.survivingAfterPredicate.add(Integer.valueOf(surviving.size()));
        }

        int next = 1;
        for (Integer label : ordered) {
            if (surviving.contains(label)) {
                result.oldToNewLabel.put(label, Integer.valueOf(next++));
            }
        }
        return result;
    }

    private static boolean predicatePasses(MorphPredicate predicate,
                                            FeatureValues values,
                                            OC3DPlusParameters.WarningSink warningSink,
                                            Set<String> warnedUnavailablePredicates) {
        if (predicate == null) return true;
        String feature = normalizeFeature(predicate.featureName);
        if (!SUPPORTED_FEATURES.contains(feature)) {
            // FeatureContext warns once per unknown predicate before filtering starts.
            return true;
        }
        if (values == null) return false;
        if (values.isUnavailable(feature)) {
            String warningKey = feature + "|" + predicate.format();
            if (warnedUnavailablePredicates == null
                    || warnedUnavailablePredicates.add(warningKey)) {
                warn(warningSink, "Warning: 3D Objects Counter+ feature '" + feature
                        + "' is unavailable for predicate '" + predicate.format()
                        + "'; predicate treated as true.");
            }
            return true;
        }
        return predicate.matches(values.value(feature));
    }

    private static ImagePlus relabelConsecutivelyInPlace(ImagePlus labelImage,
                                                         Map<Integer, Integer> oldToNewLabel) {
        if (labelImage == null) return null;
        ImageStack stack = labelImage.getStack();
        if (stack == null || oldToNewLabel == null || oldToNewLabel.isEmpty()) {
            zeroLabels(labelImage);
            return labelImage;
        }
        LabelLookup labelLookup = LabelLookup.from(oldToNewLabel);
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            if (processor == null) continue;
            for (int i = 0; i < processor.getPixelCount(); i++) {
                int oldLabel = labelFromPixel(processor.getf(i));
                processor.setf(i, labelLookup.get(oldLabel));
            }
        }
        return labelImage;
    }

    private static ImagePlus emptyLabelMapLike(ImagePlus source) {
        if (source == null) return null;
        int width = Math.max(1, source.getWidth());
        int height = Math.max(1, source.getHeight());
        int nC = Math.max(1, source.getNChannels());
        int nZ = Math.max(1, source.getNSlices());
        int nT = Math.max(1, source.getNFrames());
        ImageStack stack = new ImageStack(width, height);
        for (int t = 1; t <= nT; t++) {
            for (int z = 1; z <= nZ; z++) {
                for (int c = 1; c <= nC; c++) {
                    stack.addSlice(new ByteProcessor(width, height));
                }
            }
        }
        ImagePlus empty = new ImagePlus(source.getTitle(), stack);
        empty.setDimensions(nC, nZ, nT);
        empty.setOpenAsHyperStack(source.isHyperStack());
        Calibration cal = source.getCalibration();
        if (cal != null) empty.setCalibration(cal.copy());
        return empty;
    }

    private static void zeroLabels(ImagePlus image) {
        if (image == null || image.getStack() == null) return;
        ImageStack stack = image.getStack();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            if (processor == null) continue;
            processor.setValue(0.0);
            processor.fill();
        }
    }

    private static int labelFromPixel(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0;
        return value > Integer.MAX_VALUE ? 0 : Math.round(value);
    }

    private static int labelForRow(ResultsTable table, int row) {
        try {
            double label = table.getValue("Label", row);
            if (Double.isFinite(label) && label > 0 && label <= Integer.MAX_VALUE) {
                return (int) Math.round(label);
            }
        } catch (RuntimeException missingLabelColumn) {
            // Older/partial results tables may not expose a Label column; row order is the fallback.
        }
        return row + 1;
    }

    private static double sphericity(double volume, double surfaceArea) {
        if (volume <= 0.0 || surfaceArea <= 0.0) return Double.NaN;
        return Math.pow(Math.PI, 1.0 / 3.0)
                * Math.pow(6.0 * volume, 2.0 / 3.0)
                / surfaceArea;
    }

    private static double compactness(double volume, double surfaceArea) {
        if (volume <= 0.0 || surfaceArea <= 0.0) return Double.NaN;
        return (surfaceArea * surfaceArea * surfaceArea)
                / (36.0 * Math.PI * volume * volume);
    }

    private static void setFinite(ResultsTable table, String column, int row, double value) {
        if (table != null && Double.isFinite(value)) {
            table.setValue(column, row, value);
        }
    }

    private static void setFiniteOrNaN(ResultsTable table, String column, int row, double value) {
        if (table != null) {
            table.setValue(column, row, Double.isFinite(value) ? value : Double.NaN);
        }
    }

    private static double calibratedVoxelVolume(ImagePlus image) {
        if (image == null) return Double.NaN;
        Calibration cal = image.getCalibration();
        if (cal == null || !hasActualSpatialUnit(cal)) return Double.NaN;
        double width = cal.pixelWidth;
        double height = cal.pixelHeight;
        double depth = cal.pixelDepth;
        if (!Double.isFinite(width) || !Double.isFinite(height) || !Double.isFinite(depth)
                || width <= 0.0 || height <= 0.0 || depth <= 0.0) {
            return Double.NaN;
        }
        return width * height * depth;
    }

    private static boolean hasActualSpatialUnit(Calibration cal) {
        if (cal == null) return false;
        String unit = cal.getUnit();
        if (unit == null) return false;
        String normalized = unit.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.length() > 0
                && !"pixel".equals(normalized)
                && !"pixels".equals(normalized)
                && !"px".equals(normalized);
    }

    private static ResultsTable copyOf(ResultsTable src) {
        ResultsTable dst = new ResultsTable();
        if (src == null || src.size() == 0) return dst;
        String[] headings = src.getHeadings();
        for (int row = 0; row < src.size(); row++) {
            dst.incrementCounter();
            if (headings == null) continue;
            for (int h = 0; h < headings.length; h++) {
                String heading = headings[h];
                if (heading == null || heading.trim().isEmpty()) continue;
                try {
                    dst.setValue(heading, row, src.getValue(heading, row));
                } catch (RuntimeException unreadableCell) {
                    // ResultsTable columns can be sparse; skip cells that cannot be copied.
                }
            }
        }
        return dst;
    }

    private static int[] toIntArray(List<Integer> values) {
        if (values == null || values.isEmpty()) return new int[0];
        int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i).intValue();
        }
        return out;
    }

    private static String[] toStringArray(List<MorphPredicate> predicates) {
        if (predicates == null || predicates.isEmpty()) return new String[0];
        String[] out = new String[predicates.size()];
        for (int i = 0; i < predicates.size(); i++) {
            out[i] = predicates.get(i) == null ? "" : predicates.get(i).format();
        }
        return out;
    }

    private static String normalizeFeature(String feature) {
        return feature == null ? "" : feature.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static int featureBit(String feature) {
        if (FEATURE_VOLUME.equals(feature)) return FEATURE_BIT_VOLUME;
        if (FEATURE_VOLUME_CALIBRATED.equals(feature)) return FEATURE_BIT_VOLUME_CALIBRATED;
        if (FEATURE_SURFACE_AREA.equals(feature)) return FEATURE_BIT_SURFACE_AREA;
        if (FEATURE_SPHERICITY.equals(feature)) return FEATURE_BIT_SPHERICITY;
        if (FEATURE_ELONGATION.equals(feature)) return FEATURE_BIT_ELONGATION;
        if (FEATURE_COMPACTNESS.equals(feature)) return FEATURE_BIT_COMPACTNESS;
        if (FEATURE_MEAN_INTENSITY.equals(feature)) return FEATURE_BIT_MEAN_INTENSITY;
        if (FEATURE_MAX_INTENSITY.equals(feature)) return FEATURE_BIT_MAX_INTENSITY;
        if (FEATURE_FERET_DIAMETER_MAX.equals(feature)) return FEATURE_BIT_FERET_DIAMETER_MAX;
        return 0;
    }

    private static void warn(OC3DPlusParameters.WarningSink sink, String message) {
        OC3DPlusParameters.WarningSink safe = sink == null
                ? new OC3DPlusParameters.WarningSink() {
            @Override public void warn(String message) {
                IJ.log(message);
            }
        }
                : sink;
        safe.warn(message);
    }

    private static void discard(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }

    private static String titleOf(ImagePlus image) {
        if (image == null) return "null";
        String title = image.getTitle();
        return title == null || title.isEmpty() ? "<untitled>" : title;
    }

    private static final class FeatureContext {
        private final Set<String> requiredFeatures = new HashSet<String>();
        private final ImagePlus intensityImage;

        FeatureContext(List<MorphPredicate> predicates,
                       ImagePlus intensityImage,
                       OC3DPlusParameters.WarningSink warningSink) {
            if (predicates != null) {
                for (int i = 0; i < predicates.size(); i++) {
                    MorphPredicate predicate = predicates.get(i);
                    if (predicate == null) continue;
                    String feature = normalizeFeature(predicate.featureName);
                    if (SUPPORTED_FEATURES.contains(feature)) {
                        requiredFeatures.add(feature);
                    } else {
                        warn(warningSink, "Warning: unknown 3D Objects Counter+ morph feature '"
                                + predicate.featureName + "'; predicate treated as true.");
                    }
                }
            }
            // These columns are part of the public results-table contract. Do
            // not make their values depend on which filters the user selected.
            for (int i = 0; i < ALWAYS_REPORTED_MORPHOLOGY_FEATURES.length; i++) {
                requiredFeatures.add(ALWAYS_REPORTED_MORPHOLOGY_FEATURES[i]);
            }
            this.intensityImage = intensityImage;
        }

        boolean needs(String feature) {
            return requiredFeatures.contains(feature);
        }

        boolean hasIntensityImage() {
            return intensityImage != null;
        }
    }

    private static final class FeatureValues {
        double volume = Double.NaN;
        double volumeCalibrated = Double.NaN;
        double surfaceArea = Double.NaN;
        double sphericity = Double.NaN;
        double elongation = Double.NaN;
        double compactness = Double.NaN;
        double meanIntensity = Double.NaN;
        double maxIntensity = Double.NaN;
        double intensitySum = Double.NaN;
        double intensityStdDev = Double.NaN;
        double minIntensity = Double.NaN;
        double centerOfMassX = Double.NaN;
        double centerOfMassY = Double.NaN;
        double centerOfMassZ = Double.NaN;
        double feretDiameterMax = Double.NaN;
        private int unavailableMask;

        void markUnavailable(String feature) {
            unavailableMask |= featureBit(feature);
        }

        boolean isUnavailable(String feature) {
            int bit = featureBit(feature);
            return bit != 0 && (unavailableMask & bit) != 0;
        }

        double value(String feature) {
            if (FEATURE_VOLUME.equals(feature)) return volume;
            if (FEATURE_VOLUME_CALIBRATED.equals(feature)) return volumeCalibrated;
            if (FEATURE_SURFACE_AREA.equals(feature)) return surfaceArea;
            if (FEATURE_SPHERICITY.equals(feature)) return sphericity;
            if (FEATURE_ELONGATION.equals(feature)) return elongation;
            if (FEATURE_COMPACTNESS.equals(feature)) return compactness;
            if (FEATURE_MEAN_INTENSITY.equals(feature)) return meanIntensity;
            if (FEATURE_MAX_INTENSITY.equals(feature)) return maxIntensity;
            if (FEATURE_FERET_DIAMETER_MAX.equals(feature)) return feretDiameterMax;
            return Double.NaN;
        }
    }

    private static final class FilterResult {
        final Map<Integer, Integer> oldToNewLabel = new LinkedHashMap<Integer, Integer>();
        final List<Integer> survivingAfterPredicate = new ArrayList<Integer>();
    }

    private static final class LabelLookup {
        private final int[] palette;
        private final int[] keys;
        private final int[] values;
        private final int mask;

        private LabelLookup(int[] palette, int[] keys, int[] values, int mask) {
            this.palette = palette;
            this.keys = keys;
            this.values = values;
            this.mask = mask;
        }

        static LabelLookup from(Map<Integer, Integer> oldToNewLabel) {
            int maxLabel = 0;
            int count = 0;
            for (Map.Entry<Integer, Integer> entry : oldToNewLabel.entrySet()) {
                if (entry == null || entry.getKey() == null || entry.getValue() == null) continue;
                int label = entry.getKey().intValue();
                int next = entry.getValue().intValue();
                if (label <= 0 || next <= 0) continue;
                count++;
                if (label > maxLabel) maxLabel = label;
            }

            if (count == 0) {
                return new LabelLookup(new int[0], null, null, 0);
            }
            if (maxLabel >= 0 && maxLabel < MAX_LABEL_PALETTE_ENTRIES) {
                int[] palette = new int[maxLabel + 1];
                for (Map.Entry<Integer, Integer> entry : oldToNewLabel.entrySet()) {
                    if (entry == null || entry.getKey() == null || entry.getValue() == null) continue;
                    int label = entry.getKey().intValue();
                    int next = entry.getValue().intValue();
                    if (label > 0 && next > 0) {
                        palette[label] = next;
                    }
                }
                return new LabelLookup(palette, null, null, 0);
            }

            int needed = count > (1 << 29) ? (1 << 30) : count * 2;
            int capacity = 1;
            while (capacity < needed) {
                capacity <<= 1;
            }
            int[] keys = new int[capacity];
            int[] values = new int[capacity];
            int mask = capacity - 1;
            for (Map.Entry<Integer, Integer> entry : oldToNewLabel.entrySet()) {
                if (entry == null || entry.getKey() == null || entry.getValue() == null) continue;
                int label = entry.getKey().intValue();
                int next = entry.getValue().intValue();
                if (label <= 0 || next <= 0) continue;
                int slot = mix(label) & mask;
                while (keys[slot] != 0 && keys[slot] != label) {
                    slot = (slot + 1) & mask;
                }
                keys[slot] = label;
                values[slot] = next;
            }
            return new LabelLookup(null, keys, values, mask);
        }

        int get(int label) {
            if (label <= 0) return 0;
            if (palette != null) {
                return label < palette.length ? palette[label] : 0;
            }
            int slot = mix(label) & mask;
            while (keys[slot] != 0) {
                if (keys[slot] == label) {
                    return values[slot];
                }
                slot = (slot + 1) & mask;
            }
            return 0;
        }

        private static int mix(int value) {
            value ^= (value >>> 16);
            value *= 0x7feb352d;
            value ^= (value >>> 15);
            value *= 0x846ca68b;
            value ^= (value >>> 16);
            return value;
        }
    }
}
