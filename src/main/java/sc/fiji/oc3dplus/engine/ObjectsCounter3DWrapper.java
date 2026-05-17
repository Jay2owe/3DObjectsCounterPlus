package sc.fiji.oc3dplus.engine;

import Utilities.Counter3D;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.WindowManager;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

/**
 * Wrapper for 3D object counting that provides two implementations:
 *
 * <ol>
 *   <li><b>Native mcib3d</b> ({@link #runNative}) — uses mcib3d-core's {@code ImageLabeller},
 *       {@code Objects3DIntPopulation}, and per-object measurement classes. Fully instance-based
 *       with no global state (Prefs, WindowManager), making it <b>thread-safe</b> and suitable
 *       for parallel execution without locks.</li>
 *   <li><b>Legacy Counter3D</b> ({@link #run}) — thin wrapper around Fiji's "3D Objects Counter".
 *       Uses global Prefs and WindowManager; requires external synchronisation.</li>
 * </ol>
 *
 * <p>Both return the same {@link Result} type so callers can switch transparently.
 */
public final class ObjectsCounter3DWrapper {

    static final String DIRECT_INTENSITY_MAX_DENSE_LABEL_PROPERTY =
            "sc.fiji.oc3dplus.maxDenseDirectIntensityLabel";
    private static final int DEFAULT_DIRECT_INTENSITY_MAX_DENSE_LABEL = 8 * 1024 * 1024;
    private static final int DIRECT_INTENSITY_INITIAL_DENSE_LABELS = 1024;

    public static final class Result {
        private final ResultsTable statistics;
        private final ImagePlus objectsMap;
        private final ImagePlus maskedImage;
        private final boolean foundObjects;

        public Result(ResultsTable statistics, ImagePlus objectsMap, ImagePlus maskedImage, boolean foundObjects) {
            this.statistics = statistics;
            this.objectsMap = objectsMap;
            this.maskedImage = maskedImage;
            this.foundObjects = foundObjects;
        }

        public ResultsTable getStatistics() {
            return statistics;
        }

        public ImagePlus getObjectsMap() {
            return objectsMap;
        }

        public ImagePlus getMaskedImage() {
            return maskedImage;
        }

        public boolean isFoundObjects() {
            return foundObjects;
        }
    }

    /**
     * Runs 3D object counting on {@code img} via the legacy {@code Counter3D}.
     *
     * @param img thresholded-by-intensity image
     * @param threshold intensity threshold
     * @param minSize minimum object size (voxels)
     * @param maxSize maximum object size (voxels)
     * @param excludeOnEdges whether to exclude objects touching edges
     * @param redirect whether to redirect intensity measurements
     * @param wantObjectsMap whether to compute an objects map image
     * @param wantMaskedImage whether to compute/show a masked image
     */
    public Result run(
            ImagePlus img,
            int threshold,
            int minSize,
            int maxSize,
            boolean excludeOnEdges,
            boolean redirect,
            boolean wantObjectsMap,
            boolean wantMaskedImage
    ) {
        return run(img, threshold, minSize, maxSize, excludeOnEdges, redirect, null, wantObjectsMap, wantMaskedImage);
    }

    public Result run(
            ImagePlus img,
            int threshold,
            int minSize,
            int maxSize,
            boolean excludeOnEdges,
            boolean redirect,
            String redirectToTitle,
            boolean wantObjectsMap,
            boolean wantMaskedImage
    ) {
        synchronized (ObjectsCounter3DWrapper.class) {
            if (img == null) {
                throw new IllegalArgumentException(
                        "img must not be null (img=null; expected a 3D ImagePlus).");
            }

            IJ.showStatus("3D Objects Counter+: running legacy counter on '" + titleOf(img) + "'...");
            Prefs.set("3D-OC-Options_showMaskedImg.boolean", wantMaskedImage);

            if (redirectToTitle != null && !redirectToTitle.trim().isEmpty()) {
                Prefs.set("3D-OC-Options_redirectTo.string", redirectToTitle);
            }

            boolean safeRedirect = redirect;
            if (safeRedirect) {
                String redirTitle = Prefs.get("3D-OC-Options_redirectTo.string", "none");
                ImagePlus redir = (redirTitle == null || "none".equalsIgnoreCase(redirTitle))
                        ? null : WindowManager.getImage(redirTitle);
                if (redir == null) {
                    safeRedirect = false;
                }
            }

            Counter3D oc = new Counter3D(img, threshold, minSize, maxSize, excludeOnEdges, safeRedirect);

            ResultsTable stats = buildStatisticsTable(oc, img.getCalibration());

            ImagePlus objectsMap = null;
            if (wantObjectsMap) {
                try {
                    objectsMap = oc.getObjMap();
                } catch (NullPointerException missingObjectsMap) {
                    // Legacy Counter3D throws here when no object map is available.
                    objectsMap = null;
                }
            }

            ImagePlus masked = null;
            if (wantMaskedImage) {
                masked = findOpenImageTitleContains("masked image for ");
            }

            boolean foundObjects = stats != null && stats.size() > 0;
            return new Result(stats, objectsMap, masked, foundObjects);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Native mcib3d implementation — fully thread-safe, no global state
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Thread-safe 3D object counting using mcib3d-core. Does NOT touch
     * {@link Prefs} or {@link WindowManager}; safe for concurrent calls
     * from multiple threads without locks.
     *
     * <p>mcib3d wraps the full stack for labelling and measurement. The
     * transient 8-bit threshold mask is released as soon as the independent
     * label image has been created.
     */
    public Result runNative(
            ImagePlus img,
            int threshold,
            int minSize,
            int maxSize,
            boolean excludeOnEdges,
            ImagePlus redirectImage,
            boolean wantObjectsMap,
            boolean wantMaskedImage
    ) {
        return runNative(img, threshold, minSize, maxSize, excludeOnEdges,
                redirectImage, wantObjectsMap, wantMaskedImage,
                ProgressReporter.steps(3), true);
    }

    Result runNative(
            ImagePlus img,
            int threshold,
            int minSize,
            int maxSize,
            boolean excludeOnEdges,
            ImagePlus redirectImage,
            boolean wantObjectsMap,
            boolean wantMaskedImage,
            ProgressReporter progress,
            boolean finishProgress
    ) {
        if (img == null) {
            throw new IllegalArgumentException(
                    "img must not be null (img=null; expected a 3D ImagePlus).");
        }
        requireMcib3dAvailable("runNative");
        ProgressReporter safeProgress = progress == null ? ProgressReporter.none() : progress;

        safeProgress.step("Finding structures");
        ImagePlus thresholded = ImageOps.thresholdBinaryMaskCopy(img, threshold);
        ImagePlus labelledImp = null;

        try {
            mcib3d.image3d.ImageHandler threshIH = mcib3d.image3d.ImageHandler.wrap(thresholded);
            safeProgress.finishStep();
            safeProgress.step("Connecting structures");
            mcib3d.image3d.ImageLabeller labeller = new mcib3d.image3d.ImageLabeller();
            labeller.setMinSize(minSize);
            labeller.setMaxSize(maxSize);
            mcib3d.image3d.ImageHandler labelledIH = labeller.getLabels(threshIH);
            threshIH = null;

            labelledImp = labelledIH.getImagePlus();
            if (labelledImp != thresholded) {
                discard(thresholded);
            }
            thresholded = null;
            labelledImp.setCalibration(img.getCalibration() != null ? img.getCalibration().copy() : null);

            mcib3d.geom2.Objects3DIntPopulation population =
                    new mcib3d.geom2.Objects3DIntPopulation(labelledIH);

            if (excludeOnEdges) {
                safeProgress.detail("Connecting structures, excluding edge objects");
                mcib3d.geom2.Objects3DIntPopulationComputation popComp =
                        new mcib3d.geom2.Objects3DIntPopulationComputation(population);
                population = popComp.getExcludeBorders(labelledIH, false);
                retainPopulationLabels(labelledImp, population);
            }
            safeProgress.finishStep();

            int nbObjects = population.getNbObjects();

            Calibration cal = img.getCalibration();
            ImagePlus measurementImage = redirectImage == null ? img : redirectImage;
            mcib3d.image3d.ImageHandler measurementIH =
                    mcib3d.image3d.ImageHandler.wrap(measurementImage);
            safeProgress.step("Measuring " + nbObjects + " object"
                    + (nbObjects == 1 ? "" : "s"));
            ResultsTable stats = buildNativeStatisticsTable(population, cal, measurementIH,
                    labelledImp, measurementImage);

            ImagePlus masked = null;
            if (wantMaskedImage && redirectImage != null && nbObjects > 0) {
                masked = buildMaskedImage(redirectImage, labelledImp);
                masked.setTitle("Masked image for " + img.getTitle());
            }

            ImagePlus objectsMap = null;
            if (wantObjectsMap) {
                objectsMap = labelledImp;
                if (labelledImp == thresholded) {
                    thresholded = null;
                }
                labelledImp = null;
                objectsMap.setTitle("Objects map of " + img.getTitle());
            }

            boolean foundObjects = nbObjects > 0;
            safeProgress.finishStep();
            if (finishProgress) {
                safeProgress.finish("Complete for '" + titleOf(img) + "' (" + nbObjects
                        + " object" + (nbObjects == 1 ? "" : "s") + ")");
            }
            return new Result(stats, objectsMap, masked, foundObjects);
        } catch (RuntimeException e) {
            if (finishProgress) {
                safeProgress.error("Error while counting '" + titleOf(img) + "'");
            }
            throw e;
        } finally {
            discard(thresholded);
            if (labelledImp != thresholded) {
                discard(labelledImp);
            }
        }
    }

    /**
     * Thread-safe 3D object counting from a pre-computed label image (e.g.
     * from StarDist 3D, Cellpose, or after morphology filtering). Skips the
     * labelling step entirely — the label image already contains unique
     * integer labels per object.
     *
     * <p>mcib3d wraps the full label stack for measurement, so callers should
     * expect memory use proportional to the complete image volume.
     */
    public Result fromLabelImage(
            ImagePlus labelImage,
            ImagePlus redirectImage,
            boolean wantObjectsMap,
            boolean wantMaskedImage
    ) {
        return fromLabelImage(labelImage, redirectImage, 0, Integer.MAX_VALUE,
                wantObjectsMap, wantMaskedImage);
    }

    public Result fromLabelImage(
            ImagePlus labelImage,
            ImagePlus redirectImage,
            int minSize,
            int maxSize,
            boolean wantObjectsMap,
            boolean wantMaskedImage
    ) {
        return fromLabelImage(labelImage, redirectImage, minSize, maxSize,
                wantObjectsMap, wantMaskedImage, ProgressReporter.steps(2), true);
    }

    Result fromLabelImage(
            ImagePlus labelImage,
            ImagePlus redirectImage,
            int minSize,
            int maxSize,
            boolean wantObjectsMap,
            boolean wantMaskedImage,
            ProgressReporter progress,
            boolean finishProgress
    ) {
        if (labelImage == null) {
            throw new IllegalArgumentException(
                    "labelImage must not be null (labelImage=null; expected a labelled 3D ImagePlus).");
        }
        requireMcib3dAvailable("fromLabelImage");
        ProgressReporter safeProgress = progress == null ? ProgressReporter.none() : progress;

        safeProgress.step("Preparing labelled image");
        ImagePlus filteredLabelImage = filterLabelImageBySize(labelImage, minSize, maxSize);
        boolean closeFiltered = filteredLabelImage != labelImage;

        try {
            safeProgress.finishStep();
            mcib3d.image3d.ImageHandler labelledIH = mcib3d.image3d.ImageHandler.wrap(filteredLabelImage);

            mcib3d.geom2.Objects3DIntPopulation population =
                    new mcib3d.geom2.Objects3DIntPopulation(labelledIH);

            int nbObjects = population.getNbObjects();

            Calibration cal = filteredLabelImage.getCalibration();
            mcib3d.image3d.ImageHandler measurementIH =
                    (redirectImage != null) ? mcib3d.image3d.ImageHandler.wrap(redirectImage) : null;
            safeProgress.step("Measuring labelled objects");
            ResultsTable stats = buildNativeStatisticsTable(population, cal, measurementIH,
                    filteredLabelImage, redirectImage);

            ImagePlus objectsMap = null;
            if (wantObjectsMap) {
                objectsMap = ImageOps.duplicateThreadSafe(filteredLabelImage);
                objectsMap.setTitle("Objects map of " + labelImage.getTitle());
            }

            ImagePlus masked = null;
            if (wantMaskedImage && redirectImage != null && nbObjects > 0) {
                masked = buildMaskedImage(redirectImage, filteredLabelImage);
                masked.setTitle("Masked image for " + labelImage.getTitle());
            }

            boolean foundObjects = nbObjects > 0;
            safeProgress.finishStep();
            if (finishProgress) {
                safeProgress.finish("Label measurement complete for '" + titleOf(labelImage)
                        + "' (" + nbObjects + " object" + (nbObjects == 1 ? "" : "s") + ")");
            }
            return new Result(stats, objectsMap, masked, foundObjects);
        } catch (RuntimeException e) {
            if (finishProgress) {
                safeProgress.error("Error while measuring labels in '" + titleOf(labelImage) + "'");
            }
            throw e;
        } finally {
            if (closeFiltered) {
                discard(filteredLabelImage);
            }
        }
    }

    private static ImagePlus filterLabelImageBySize(ImagePlus labelImage, int minSize, int maxSize) {
        if (labelImage == null || labelImage.getStack() == null) return labelImage;
        int safeMin = Math.max(0, minSize);
        int safeMax = Math.max(safeMin, maxSize);
        if (safeMin <= 0 && safeMax == Integer.MAX_VALUE) return labelImage;

        Map<Integer, Integer> voxelsByLabel = new HashMap<Integer, Integer>();
        ImageStack stack = labelImage.getStack();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            if (processor == null) continue;
            for (int i = 0; i < processor.getPixelCount(); i++) {
                int label = labelFromPixel(processor.getf(i));
                if (label <= 0) continue;
                Integer previous = voxelsByLabel.get(Integer.valueOf(label));
                voxelsByLabel.put(Integer.valueOf(label),
                        Integer.valueOf(previous == null ? 1 : incrementVoxelCount(previous.intValue())));
            }
        }
        if (voxelsByLabel.isEmpty()) return labelImage;

        Set<Integer> labelsToRemove = new HashSet<Integer>();
        for (Map.Entry<Integer, Integer> entry : voxelsByLabel.entrySet()) {
            int voxels = entry.getValue().intValue();
            if (voxels < safeMin || voxels > safeMax) {
                labelsToRemove.add(entry.getKey());
            }
        }
        if (labelsToRemove.isEmpty()) return labelImage;

        ImagePlus filtered = ImageOps.duplicateThreadSafe(labelImage);
        filtered.setTitle(labelImage.getTitle() + " size-filtered");
        ImageStack filteredStack = filtered.getStack();
        for (int slice = 1; slice <= filteredStack.getSize(); slice++) {
            ImageProcessor processor = filteredStack.getProcessor(slice);
            if (processor == null) continue;
            for (int i = 0; i < processor.getPixelCount(); i++) {
                int label = labelFromPixel(processor.getf(i));
                if (label > 0 && labelsToRemove.contains(Integer.valueOf(label))) {
                    processor.setf(i, 0f);
                }
            }
        }
        return filtered;
    }

    private static void retainPopulationLabels(ImagePlus labelImage,
            mcib3d.geom2.Objects3DIntPopulation population) {
        if (labelImage == null || labelImage.getStack() == null) return;
        Set<Integer> labelsToKeep = new HashSet<Integer>();
        if (population != null) {
            java.util.List<mcib3d.geom2.Object3DInt> objects = population.getObjects3DInt();
            for (int i = 0; i < objects.size(); i++) {
                mcib3d.geom2.Object3DInt object = objects.get(i);
                if (object == null) continue;
                int label = labelFromPixel(object.getLabel());
                if (label > 0) labelsToKeep.add(Integer.valueOf(label));
            }
        }

        ImageStack stack = labelImage.getStack();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
            if (processor == null) continue;
            for (int i = 0; i < processor.getPixelCount(); i++) {
                int label = labelFromPixel(processor.getf(i));
                if (label > 0 && !labelsToKeep.contains(Integer.valueOf(label))) {
                    processor.setf(i, 0f);
                }
            }
        }
    }

    /** Returns true if mcib3d-core classes are available at runtime. */
    public static boolean isMcib3dAvailable() {
        try {
            Class.forName("mcib3d.image3d.ImageLabeller");
            Class.forName("mcib3d.geom2.Objects3DIntPopulation");
            return true;
        } catch (ClassNotFoundException e) {
            // Availability probe: callers report the missing mcib3d dependency with operation context.
            return false;
        } catch (LinkageError e) {
            // Availability probe: callers report the broken mcib3d dependency with operation context.
            return false;
        }
    }

    private static void requireMcib3dAvailable(String operation) {
        if (!isMcib3dAvailable()) {
            throw new IllegalStateException("mcib3d-core is required for "
                    + operation + " but was not found on the runtime classpath");
        }
    }

    private static ResultsTable buildNativeStatisticsTable(
            mcib3d.geom2.Objects3DIntPopulation population,
            Calibration cal,
            mcib3d.image3d.ImageHandler measurementIH,
            ImagePlus labelImage,
            ImagePlus measurementImage) {

        ResultsTable rt = new ResultsTable();
        if (population == null || population.getNbObjects() == 0) return rt;

        String unit = cal == null ? "pixel" : cal.getUnit();
        double voxelVol = 1.0;
        if (cal != null) voxelVol = cal.pixelWidth * cal.pixelHeight * cal.pixelDepth;

        String volCol = "Volume (" + unit + "^3)";
        String surfCol = "Surface (" + unit + "^2)";
        initialiseNativeStatisticsHeadings(rt, volCol, surfCol);
        DirectIntensityStatsByLabel directIntensity =
                computeDirectIntensityStats(labelImage, measurementImage);

        java.util.List<mcib3d.geom2.Object3DInt> objects = sortedObjectsByLabel(population);
        for (int i = 0; i < objects.size(); i++) {
            mcib3d.geom2.Object3DInt obj = objects.get(i);
            int label = labelFromPixel(obj.getLabel());
            rt.incrementCounter();

            mcib3d.geom2.measurements.MeasureVolume mv = new mcib3d.geom2.measurements.MeasureVolume(obj);
            double volumePix = mv.getVolumePix();
            rt.setValue(volCol, i, volumePix * voxelVol);
            rt.setValue("Nb of obj. voxels", i, volumePix);

            mcib3d.geom2.measurements.MeasureSurface ms = new mcib3d.geom2.measurements.MeasureSurface(obj);
            double surfUnit = ms.getSurfaceContactUnit();
            rt.setValue(surfCol, i, surfUnit);
            setFiniteOrNaN(rt, "Nb of surf. voxels", i, ms.getSurfaceNbVoxelsContours());
            addNativeMorphologyColumns(rt, i, obj);

            DirectIntensityStats intensityStats = directIntensity.get(label);
            double intDen = Double.NaN;
            double mean = Double.NaN;
            double min = Double.NaN;
            double max = Double.NaN;
            double stdDev = Double.NaN;
            if (intensityStats != null && intensityStats.count > 0) {
                intDen = intensityStats.sum;
                mean = intensityStats.mean();
                min = intensityStats.min;
                max = intensityStats.max;
                stdDev = intensityStats.stdDev();
            } else if (measurementIH != null) {
                mcib3d.geom2.measurements.MeasureIntensity mi =
                        new mcib3d.geom2.measurements.MeasureIntensity(obj);
                mi.setIntensityImage(measurementIH);
                intDen = safeMeasurement(mi,
                        mcib3d.geom2.measurements.MeasureIntensity.INTENSITY_SUM, Double.NaN);
                mean = safeMeasurement(mi,
                        mcib3d.geom2.measurements.MeasureIntensity.INTENSITY_AVG, Double.NaN);
                min = safeMeasurement(mi,
                        mcib3d.geom2.measurements.MeasureIntensity.INTENSITY_MIN, Double.NaN);
                max = safeMeasurement(mi,
                        mcib3d.geom2.measurements.MeasureIntensity.INTENSITY_MAX, Double.NaN);
                stdDev = safeMeasurement(mi,
                        mcib3d.geom2.measurements.MeasureIntensity.INTENSITY_SD, Double.NaN);
            }
            setFiniteOrNaN(rt, "IntDen", i, intDen);
            setFiniteOrNaN(rt, "Mean", i, mean);
            setFiniteOrNaN(rt, "StdDev", i, stdDev);
            setFiniteOrNaN(rt, "Min", i, min);
            setFiniteOrNaN(rt, "Max", i, max);

            double centroidX = Double.NaN, centroidY = Double.NaN, centroidZ = Double.NaN;
            try {
                mcib3d.geom2.measurements.MeasureCentroid mc =
                        new mcib3d.geom2.measurements.MeasureCentroid(obj);
                mcib3d.geom.Point3D centroid = mc.getCentroidAsPoint();
                if (centroid != null) {
                    centroidX = centroid.getX();
                    centroidY = centroid.getY();
                    centroidZ = centroid.getZ();
                }
            } catch (RuntimeException centroidUnavailable) {
                // Leave centroid values unavailable when mcib3d cannot measure a degenerate object.
            }
            setFiniteOrNaN(rt, "X", i, centroidX);
            setFiniteOrNaN(rt, "Y", i, centroidY);
            setFiniteOrNaN(rt, "Z", i, centroidZ);

            double comX = centroidX, comY = centroidY, comZ = centroidZ;
            if (intensityStats != null && intensityStats.hasWeightedCenter()) {
                comX = intensityStats.weightedX / intensityStats.sum;
                comY = intensityStats.weightedY / intensityStats.sum;
                comZ = intensityStats.weightedZ / intensityStats.sum;
            } else if (measurementIH != null) {
                try {
                    mcib3d.geom2.measurements.MeasureCenterOfMass mcom =
                            new mcib3d.geom2.measurements.MeasureCenterOfMass(obj, measurementIH);
                    comX = safeMeasurement(mcom,
                            mcib3d.geom2.measurements.MeasureCenterOfMass.MASS_CENTER_X_PIX, comX);
                    comY = safeMeasurement(mcom,
                            mcib3d.geom2.measurements.MeasureCenterOfMass.MASS_CENTER_Y_PIX, comY);
                    comZ = safeMeasurement(mcom,
                            mcib3d.geom2.measurements.MeasureCenterOfMass.MASS_CENTER_Z_PIX, comZ);
                } catch (Exception centerOfMassUnavailable) {
                    // Keep the centroid fallback for center of mass.
                }
            }
            setFiniteOrNaN(rt, "XM", i, comX);
            setFiniteOrNaN(rt, "YM", i, comY);
            setFiniteOrNaN(rt, "ZM", i, comZ);

            mcib3d.geom2.BoundingBox bb = obj.getBoundingBox();
            if (bb != null) {
                int[] sizes = bb.getSizes();
                rt.setValue("BX", i, bb.xmin);
                rt.setValue("BY", i, bb.ymin);
                rt.setValue("BZ", i, bb.zmin);
                if (sizes != null && sizes.length >= 3) {
                    rt.setValue("B-width", i, sizes[0]);
                    rt.setValue("B-height", i, sizes[1]);
                    rt.setValue("B-depth", i, sizes[2]);
                }
            }

            rt.setValue("Label", i, label);
        }

        return rt;
    }

    private static void initialiseNativeStatisticsHeadings(ResultsTable rt,
                                                           String volCol,
                                                           String surfCol) {
        if (rt == null) return;
        rt.setHeading(0, volCol);
        rt.setHeading(1, surfCol);
        rt.setHeading(2, "Nb of obj. voxels");
        rt.setHeading(3, "Nb of surf. voxels");
        rt.setHeading(4, "IntDen");
        rt.setHeading(5, "Mean");
        rt.setHeading(6, "StdDev");
        rt.setHeading(7, "Min");
        rt.setHeading(8, "Max");
        rt.setHeading(9, "X");
        rt.setHeading(10, "Y");
        rt.setHeading(11, "Z");
        rt.setHeading(12, "XM");
        rt.setHeading(13, "YM");
        rt.setHeading(14, "ZM");
        rt.setHeading(15, "Morph_Sphericity");
        rt.setHeading(16, "Morph_Compactness");
        rt.setHeading(17, "Morph_Elongation");
        rt.setHeading(18, "Morph_Feret3D_um");
        rt.setHeading(19, "BX");
        rt.setHeading(20, "BY");
        rt.setHeading(21, "BZ");
        rt.setHeading(22, "B-width");
        rt.setHeading(23, "B-height");
        rt.setHeading(24, "B-depth");
        rt.setHeading(25, "Label");
    }

    private static DirectIntensityStatsByLabel computeDirectIntensityStats(
            ImagePlus labelImage,
            ImagePlus measurementImage) {
        DirectIntensityStatsByLabel statsByLabel = new DirectIntensityStatsByLabel();
        if (labelImage == null || measurementImage == null
                || labelImage.getStack() == null || measurementImage.getStack() == null) {
            return statsByLabel;
        }
        ImageStack labelStack = labelImage.getStack();
        ImageStack measurementStack = measurementImage.getStack();
        int sharedSlices = Math.min(labelStack.size(), measurementStack.size());
        int width = Math.min(labelImage.getWidth(), measurementImage.getWidth());
        int height = Math.min(labelImage.getHeight(), measurementImage.getHeight());
        if (sharedSlices <= 0 || width <= 0 || height <= 0) return statsByLabel;

        int expectedPixels = width * height;
        for (int slice = 1; slice <= sharedSlices; slice++) {
            ImageProcessor labelProcessor = labelStack.getProcessor(slice);
            ImageProcessor measurementProcessor = measurementStack.getProcessor(slice);
            if (labelProcessor == null || measurementProcessor == null) continue;
            int sharedPixels = Math.min(expectedPixels,
                    Math.min(labelProcessor.getPixelCount(), measurementProcessor.getPixelCount()));
            double z = slice - 1;
            for (int i = 0; i < sharedPixels; i++) {
                int label = labelFromPixel(labelProcessor.getf(i));
                if (label <= 0) continue;
                float rawValue = measurementProcessor.getf(i);
                if (!Float.isFinite(rawValue)) continue;
                DirectIntensityStats stats = statsByLabel.getOrCreate(label);
                int x = i % width;
                int y = i / width;
                stats.add(rawValue, x, y, z);
            }
        }
        return statsByLabel;
    }

    private static void addNativeMorphologyColumns(ResultsTable rt,
                                                   int row,
                                                   mcib3d.geom2.Object3DInt obj) {
        if (rt == null || obj == null) return;
        try {
            mcib3d.geom2.measurements.MeasureCompactness compactness =
                    new mcib3d.geom2.measurements.MeasureCompactness(obj);
            setFinite(rt, "Morph_Sphericity", row, safeMeasurement(compactness,
                    mcib3d.geom2.measurements.MeasureCompactness.SPHER_CORRECTED, Double.NaN));
            setFinite(rt, "Morph_Compactness", row, safeMeasurement(compactness,
                    mcib3d.geom2.measurements.MeasureCompactness.COMP_CORRECTED, Double.NaN));
        } catch (RuntimeException unavailable) {
            // Degenerate objects can make some mcib3d morphology measurements unavailable.
        }
        try {
            mcib3d.geom2.measurements.MeasureEllipsoid ellipsoid =
                    new mcib3d.geom2.measurements.MeasureEllipsoid(obj);
            setFinite(rt, "Morph_Elongation", row, safeMeasurement(ellipsoid,
                    mcib3d.geom2.measurements.MeasureEllipsoid.ELL_ELONGATION, Double.NaN));
        } catch (RuntimeException unavailable) {
            // Keep the rest of the statistics table available.
        }
        try {
            mcib3d.geom2.measurements.MeasureFeret feret =
                    new mcib3d.geom2.measurements.MeasureFeret(obj);
            setFinite(rt, "Morph_Feret3D_um", row, safeMeasurement(feret,
                    mcib3d.geom2.measurements.MeasureFeret.FERET_UNIT, Double.NaN));
        } catch (RuntimeException unavailable) {
            // Keep the rest of the statistics table available.
        }
    }

    private static java.util.List<mcib3d.geom2.Object3DInt> sortedObjectsByLabel(
            mcib3d.geom2.Objects3DIntPopulation population) {
        if (population == null) {
            return Collections.emptyList();
        }
        java.util.List<mcib3d.geom2.Object3DInt> objects =
                new ArrayList<mcib3d.geom2.Object3DInt>(population.getObjects3DInt());
        Collections.sort(objects, new Comparator<mcib3d.geom2.Object3DInt>() {
            @Override public int compare(mcib3d.geom2.Object3DInt a,
                                         mcib3d.geom2.Object3DInt b) {
                return Integer.compare(labelFromObject(a), labelFromObject(b));
            }
        });
        return objects;
    }

    private static int labelFromObject(mcib3d.geom2.Object3DInt object) {
        return object == null ? 0 : labelFromPixel(object.getLabel());
    }

    private static ImagePlus buildMaskedImage(ImagePlus redirectImage, ImagePlus labelledImage) {
        ImagePlus masked = ImageOps.duplicateThreadSafe(redirectImage);
        masked.setTitle("Masked image");
        ImageStack maskedStack = masked.getStack();
        ImageStack labelStack = labelledImage.getStack();
        int nSlices = Math.min(maskedStack.size(), labelStack.size());
        for (int s = 1; s <= nSlices; s++) {
            ImageProcessor mp = maskedStack.getProcessor(s);
            ImageProcessor lp = labelStack.getProcessor(s);
            int labelledPixels = lp.getPixelCount();
            int sharedPixels = Math.min(mp.getPixelCount(), labelledPixels);
            for (int i = 0; i < sharedPixels; i++) {
                if (lp.getf(i) == 0) {
                    mp.setf(i, 0f);
                }
            }
            for (int i = sharedPixels; i < mp.getPixelCount(); i++) {
                mp.setf(i, 0f);
            }
        }
        for (int s = nSlices + 1; s <= maskedStack.size(); s++) {
            ImageProcessor mp = maskedStack.getProcessor(s);
            if (mp == null) continue;
            for (int i = 0; i < mp.getPixelCount(); i++) {
                mp.setf(i, 0f);
            }
        }
        return masked;
    }

    private static void discard(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }

    /** Converts a token like {@code "Infinity"} or {@code "inf"} into a voxel count. */
    public static int parseMaxSizeVoxels(String token, ImagePlus reference) {
        if (token == null) return maxPossibleVoxels(reference);
        String t = token.trim();
        if (t.isEmpty()) return maxPossibleVoxels(reference);
        if ("infinity".equalsIgnoreCase(t) || "inf".equalsIgnoreCase(t)) return maxPossibleVoxels(reference);
        return parseFiniteVoxelCount(t, maxPossibleVoxels(reference));
    }

    public static int parseMinSizeVoxels(String token, int fallback) {
        if (token == null) return fallback;
        String t = token.trim();
        if (t.isEmpty()) return fallback;
        return parseFiniteVoxelCount(t, fallback);
    }

    private static int maxPossibleVoxels(ImagePlus imp) {
        if (imp == null) return Integer.MAX_VALUE;
        long vox = (long) imp.getWidth() * (long) imp.getHeight() * (long) imp.getNSlices();
        return vox > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) vox;
    }

    private static int parseFiniteVoxelCount(String token, int fallback) {
        double parsed = Double.parseDouble(token);
        if (!Double.isFinite(parsed)) return fallback;
        if (parsed <= 0) return 0;
        if (parsed >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.round(parsed);
    }

    private static int incrementVoxelCount(int current) {
        return current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current + 1;
    }

    private static int labelFromPixel(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0;
        return value > Integer.MAX_VALUE ? 0 : Math.round(value);
    }

    private static double safeMeasurement(mcib3d.geom2.measurements.MeasureAbstract measure,
                                          String name,
                                          double fallback) {
        if (measure == null || name == null) return fallback;
        try {
            Double value = measure.getValueMeasurement(name);
            return value == null || !Double.isFinite(value.doubleValue())
                    ? fallback : value.doubleValue();
        } catch (RuntimeException measurementUnavailable) {
            return fallback;
        }
    }

    private static void setFinite(ResultsTable table, String column, int row, double value) {
        if (table != null && column != null && Double.isFinite(value)) {
            table.setValue(column, row, value);
        }
    }

    private static void setFiniteOrNaN(ResultsTable table, String column, int row, double value) {
        if (table != null && column != null) {
            table.setValue(column, row, Double.isFinite(value) ? value : Double.NaN);
        }
    }

    private static final class DirectIntensityStatsByLabel {
        private final int maxDenseLabel;
        private DirectIntensityStats[] dense;
        private Map<Integer, DirectIntensityStats> sparse;

        DirectIntensityStatsByLabel() {
            this.maxDenseLabel = configuredMaxDenseDirectIntensityLabel();
            if (maxDenseLabel > 0) {
                int initialLength = (int) Math.min((long) DIRECT_INTENSITY_INITIAL_DENSE_LABELS,
                        (long) maxDenseLabel + 1L);
                this.dense = new DirectIntensityStats[Math.max(1, initialLength)];
            } else {
                this.sparse = new HashMap<Integer, DirectIntensityStats>();
            }
        }

        DirectIntensityStats get(int label) {
            if (label <= 0) return null;
            if (dense != null) {
                return label < dense.length ? dense[label] : null;
            }
            return sparse == null ? null : sparse.get(Integer.valueOf(label));
        }

        DirectIntensityStats getOrCreate(int label) {
            if (label <= 0) return null;
            if (dense != null && label <= maxDenseLabel) {
                if (ensureDenseCapacity(label)) {
                    DirectIntensityStats stats = dense[label];
                    if (stats == null) {
                        stats = new DirectIntensityStats();
                        dense[label] = stats;
                    }
                    return stats;
                }
            }
            switchToSparse();
            Integer key = Integer.valueOf(label);
            DirectIntensityStats stats = sparse.get(key);
            if (stats == null) {
                stats = new DirectIntensityStats();
                sparse.put(key, stats);
            }
            return stats;
        }

        private boolean ensureDenseCapacity(int label) {
            if (label < dense.length) return true;
            long targetLength = Math.min((long) maxDenseLabel + 1L,
                    Math.max((long) label + 1L, (long) dense.length * 2L));
            while (targetLength <= label && targetLength <= maxDenseLabel) {
                targetLength *= 2L;
            }
            if (targetLength > Integer.MAX_VALUE) return false;
            try {
                dense = Arrays.copyOf(dense, (int) targetLength);
                return true;
            } catch (OutOfMemoryError oom) {
                switchToSparse();
                System.gc();
                return false;
            }
        }

        private void switchToSparse() {
            if (sparse != null) return;
            Map<Integer, DirectIntensityStats> replacement = new HashMap<Integer, DirectIntensityStats>();
            if (dense != null) {
                for (int label = 1; label < dense.length; label++) {
                    DirectIntensityStats stats = dense[label];
                    if (stats != null) {
                        replacement.put(Integer.valueOf(label), stats);
                    }
                }
            }
            sparse = replacement;
            dense = null;
        }
    }

    private static int configuredMaxDenseDirectIntensityLabel() {
        String configured = System.getProperty(DIRECT_INTENSITY_MAX_DENSE_LABEL_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            return DEFAULT_DIRECT_INTENSITY_MAX_DENSE_LABEL;
        }
        try {
            int parsed = Integer.parseInt(configured.trim());
            return parsed < 0 ? 0 : parsed;
        } catch (NumberFormatException invalidValue) {
            return DEFAULT_DIRECT_INTENSITY_MAX_DENSE_LABEL;
        }
    }

    private static final class DirectIntensityStats {
        long count;
        double sum;
        double sumSquares;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double weightedX;
        double weightedY;
        double weightedZ;

        void add(double value, double x, double y, double z) {
            count++;
            sum += value;
            sumSquares += value * value;
            if (value < min) min = value;
            if (value > max) max = value;
            weightedX += value * x;
            weightedY += value * y;
            weightedZ += value * z;
        }

        double mean() {
            return count <= 0 ? Double.NaN : sum / (double) count;
        }

        double stdDev() {
            if (count <= 0) return Double.NaN;
            double mean = mean();
            double variance = (sumSquares / (double) count) - (mean * mean);
            if (variance < 0 && variance > -1.0e-9) variance = 0.0;
            return variance < 0 ? Double.NaN : Math.sqrt(variance);
        }

        boolean hasWeightedCenter() {
            return count > 0 && Double.isFinite(sum) && sum != 0.0;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ResultsTable buildStatisticsTable(Counter3D oc, Calibration cal) {
        ResultsTable rt = new ResultsTable();
        if (oc == null) return rt;

        Vector objs;
        try {
            objs = oc.getObjectsList();
        } catch (Throwable objectListUnavailable) {
            // Legacy Counter3D exposes object data through internals; empty stats is the safe fallback.
            return rt;
        }
        if (objs == null || objs.isEmpty()) return rt;

        String unit = cal == null ? "pixel" : cal.getUnit();
        double voxelVol = 1.0;
        if (cal != null) voxelVol = cal.pixelWidth * cal.pixelHeight * cal.pixelDepth;

        rt.setHeading(0, "Volume (" + unit + "^3)");
        rt.setHeading(1, "Surface (" + unit + "^2)");
        rt.setHeading(2, "Nb of obj. voxels");
        rt.setHeading(3, "Nb of surf. voxels");
        rt.setHeading(4, "IntDen");
        rt.setHeading(5, "Mean");
        rt.setHeading(6, "StdDev");
        rt.setHeading(7, "Median");
        rt.setHeading(8, "Min");
        rt.setHeading(9, "Max");
        rt.setHeading(10, "X");
        rt.setHeading(11, "Y");
        rt.setHeading(12, "Z");
        rt.setHeading(13, "XM");
        rt.setHeading(14, "YM");
        rt.setHeading(15, "ZM");

        for (int i = 0; i < objs.size(); i++) {
            Object o = objs.get(i);
            if (o == null) continue;

            rt.incrementCounter();

            double size = getDoubleField(o, "size", 0);
            double volume = size * voxelVol;
            rt.setValue("Volume (" + unit + "^3)", i, volume);
            rt.setValue("Nb of obj. voxels", i, size);

            double surface = getDoubleField(o, "surf_cal", 0);
            rt.setValue("Surface (" + unit + "^2)", i, surface);
            rt.setValue("Nb of surf. voxels", i, getDoubleField(o, "surf_size", 0));

            double intDen = getDoubleField(o, "int_dens", 0);
            rt.setValue("IntDen", i, intDen);

            double mean = getDoubleField(o, "mean_gray", 0);
            rt.setValue("Mean", i, mean);
            rt.setValue("StdDev", i, getDoubleField(o, "SD", 0));
            rt.setValue("Median", i, getDoubleField(o, "median", 0));
            rt.setValue("Min", i, getDoubleField(o, "min", 0));
            rt.setValue("Max", i, getDoubleField(o, "max", 0));

            double[] centroid = getDoubleArrayField(o, "centroid");
            if (centroid != null) {
                if (centroid.length > 0) rt.setValue("X", i, centroid[0]);
                if (centroid.length > 1) rt.setValue("Y", i, centroid[1]);
                if (centroid.length > 2) rt.setValue("Z", i, zeroBasedLegacyZ(centroid[2]));
            } else {
                rt.setValue("X", i, 0);
                rt.setValue("Y", i, 0);
                rt.setValue("Z", i, 0);
            }

            double[] com = getDoubleArrayField(o, "c_mass");
            if (com != null) {
                if (com.length > 0) rt.setValue("XM", i, com[0]);
                if (com.length > 1) rt.setValue("YM", i, com[1]);
                if (com.length > 2) rt.setValue("ZM", i, zeroBasedLegacyZ(com[2]));
            } else {
                rt.setValue("XM", i, 0);
                rt.setValue("YM", i, 0);
                rt.setValue("ZM", i, 0);
            }

            double[] bbOrigin = getDoubleArrayField(o, "bound_cube_TL");
            if (bbOrigin != null) {
                if (bbOrigin.length > 0) rt.setValue("BX", i, bbOrigin[0]);
                if (bbOrigin.length > 1) rt.setValue("BY", i, bbOrigin[1]);
                if (bbOrigin.length > 2) rt.setValue("BZ", i, zeroBasedLegacyZ(bbOrigin[2]));
            }
            rt.setValue("B-width", i, getDoubleField(o, "bound_cube_width", 0));
            rt.setValue("B-height", i, getDoubleField(o, "bound_cube_height", 0));
            rt.setValue("B-depth", i, getDoubleField(o, "bound_cube_depth", 0));

            rt.setValue("Label", i, i + 1);
        }

        return rt;
    }

    private static double getDoubleField(Object obj, String fieldName, double fallback) {
        try {
            Field f = obj.getClass().getField(fieldName);
            Object v = f.get(obj);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Exception fieldUnavailable) {
            // Legacy Counter3D fields vary by version; use the supplied fallback.
        }
        return fallback;
    }

    private static double[] getDoubleArrayField(Object obj, String fieldName) {
        try {
            Field f = obj.getClass().getField(fieldName);
            Object v = f.get(obj);
            if (v instanceof float[]) {
                float[] a = (float[]) v;
                double[] d = new double[a.length];
                for (int i = 0; i < a.length; i++) d[i] = a[i];
                return d;
            }
            if (v instanceof double[]) {
                return (double[]) v;
            }
            if (v instanceof int[]) {
                int[] a = (int[]) v;
                double[] d = new double[a.length];
                for (int i = 0; i < a.length; i++) d[i] = a[i];
                return d;
            }
        } catch (Exception fieldUnavailable) {
            // Legacy Counter3D fields vary by version; missing arrays remain unavailable.
        }
        return null;
    }

    private static double zeroBasedLegacyZ(double z) {
        return Double.isFinite(z) ? z - 1.0 : z;
    }

    private static String titleOf(ImagePlus image) {
        if (image == null) return "null";
        String title = image.getTitle();
        return title == null || title.isEmpty() ? "<untitled>" : title;
    }

    private static String maxSizeText(int maxSize) {
        return maxSize == Integer.MAX_VALUE ? "Infinity" : Integer.toString(maxSize);
    }

    private static ImagePlus findOpenImageTitleContains(String needleLower) {
        if (needleLower == null) return null;
        int[] ids = WindowManager.getIDList();
        if (ids == null) return null;
        for (int id : ids) {
            ImagePlus imp = WindowManager.getImage(id);
            if (imp == null) continue;
            String title = imp.getTitle();
            if (title == null) continue;
            if (title.toLowerCase(Locale.ROOT).contains(needleLower)) return imp;
        }
        return null;
    }
}
