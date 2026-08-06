package sc.fiji.oc3dplus.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Measurement of a pre-computed label image, and the size-filtering that goes with it.
 *
 * <p>This class used to hold two labelling engines as well: the classic
 * {@code Utilities.Counter3D} and an mcib3d one, selected by input shape. Stage 03 of
 * the oc3d-core migration replaced both with a single streaming path in
 * {@link OC3DPlusRunner}, and Stage 04 removed the two GPL dependencies they needed. The
 * engines themselves are kept as test-only reference code in {@code src/test-oracle}, so
 * the goldens they produced can still be re-derived; nothing in {@code src/main} calls
 * them, and the shipped jar links neither library.
 *
 * <p>What remains is thread-safe and touches no global state: no {@code Prefs}, no
 * {@code WindowManager}, so it is safe to call concurrently.
 */
public final class ObjectsCounter3DWrapper {

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
     * Thread-safe 3D object counting from a pre-computed label image (e.g.
     * from StarDist 3D, Cellpose, or after morphology filtering). Skips the
     * labelling step entirely — the label image already contains unique
     * integer labels per object.
     *
     * <p>Measured by {@code oc3d-core}'s {@code LabelFeatureAccumulator}, so this
     * path needs no third-party dependency and works on a bare Fiji without the
     * 3D ImageJ Suite update site.
     *
     * <p>Memory use is proportional to the number of objects rather than to the
     * volume: the accumulator keeps running totals per label and never holds a
     * per-object voxel list.
     *
     * <p>{@code redirectImage}, when supplied, must match the label image in
     * width, height and slice count; a mismatch is rejected with an
     * {@link IllegalArgumentException} naming the offending dimension.
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
        ProgressReporter safeProgress = progress == null ? ProgressReporter.none() : progress;

        safeProgress.step("Preparing labelled image");
        ImagePlus filteredLabelImage = filterLabelImageBySize(labelImage, minSize, maxSize);
        boolean closeFiltered = filteredLabelImage != labelImage;

        try {
            safeProgress.finishStep();
            safeProgress.step("Measuring labelled objects");
            // Measured by oc3d-core's accumulator rather than by wrapping the
            // stack into mcib3d. The label image already carries unique integer
            // labels, so there was never anything for a labelling library to do
            // here - only measurement, which needs no dependency at all. This is
            // also what makes label-image input work on a bare Fiji, where the 3D
            // ImageJ Suite update site is not installed.
            sc.fiji.oc3d.core.measure.LabelFeatureAccumulator.Result measured =
                    sc.fiji.oc3d.core.measure.LabelFeatureAccumulator.scan(
                            filteredLabelImage,
                            redirectImage,
                            filteredLabelImage.getCalibration());
            ResultsTable stats = measured.toStatisticsTable();
            int nbObjects = measured.objectCount();

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

    static ImagePlus buildMaskedImage(ImagePlus redirectImage, ImagePlus labelledImage) {
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

    static void discard(ImagePlus image) {
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

    static int labelFromPixel(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0;
        return value > Integer.MAX_VALUE ? 0 : Math.round(value);
    }

    static String titleOf(ImagePlus image) {
        if (image == null) return "null";
        String title = image.getTitle();
        return title == null || title.isEmpty() ? "<untitled>" : title;
    }

}
