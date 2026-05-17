package sc.fiji.oc3dplus.api;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import sc.fiji.oc3dplus.engine.OC3DPlusRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Public static façade for 3D Objects Counter+. Use this from Java; the
 * macro grammar in the {@code ObjectsCounter3DPlus} plugin forwards to the
 * same engine.
 *
 * <p>Thread-safe: every call constructs a fresh engine instance and uses
 * the thread-safe mcib3d path internally. The facade methods do not show
 * images, open results windows, write to ImageJ's global Results window, or
 * mutate the input image; callers decide what to display or save.
 *
 * <p>Example:
 * <pre>{@code
 * OC3DPlusParameters params = OC3DPlus.builder()
 *     .threshold(128)
 *     .minSize(20)
 *     .addFilter("sphericity", ">=", 0.6)
 *     .build();
 * OC3DPlusResult result = OC3DPlus.count(imp, params);
 * }</pre>
 */
public final class OC3DPlus {

    private OC3DPlus() {}

    /**
     * Run the full pipeline: detect with 3D OC, then filter by morphology.
     *
     * <p>This method returns a new label image and statistics table in the
     * result. It does not mutate {@code image}, show windows, or write to
     * ImageJ's global Results window. The engine may update ImageJ status text
     * while it runs.
     *
     * @param image source 3D image; must contain at least one non-empty slice
     * @param params run parameters, or {@code null} to use builder defaults
     * @throws IllegalArgumentException if {@code image} is null or empty, or
     *         if an intensity image has incompatible dimensions
     */
    public static OC3DPlusResult count(ImagePlus image, OC3DPlusParameters params) {
        if (image == null) {
            throw new IllegalArgumentException(
                    "image must not be null (image=null; expected a 3D ImagePlus).");
        }
        OC3DPlusParameters safeParams = params == null
                ? builder().build()
                : params;
        validateImage(image, "image");
        validateIntensityImage(image, safeParams.intensityImage);
        OC3DPlusRunner.Result engineResult = new OC3DPlusRunner().runResult(image, safeParams);
        if (engineResult == null || engineResult.getLabelImage() == null) {
            return new OC3DPlusResult(new ResultsTable(), null, new int[0], new String[0]);
        }
        return new OC3DPlusResult(
                engineResult.getStatistics(),
                engineResult.getLabelImage(),
                engineResult.getPredicateCounts(),
                engineResult.getPredicateLabels());
    }

    /**
     * Detect-only shortcut: native 3D Objects Counter without any morphology
     * filtering. Equivalent to
     * {@link #count(ImagePlus, OC3DPlusParameters)} with no filters, but skips
     * the relabel/recount step.
     *
     * <p>This method returns its label image and statistics table in the
     * result. It does not mutate {@code image}, show windows, or write to
     * ImageJ's global Results window. The engine may update ImageJ status text
     * while it runs.
     *
     * @param image source 3D image; must contain at least one non-empty slice
     * @param threshold intensity cutoff; voxels below this value are ignored
     * @param minSize minimum object size in voxels
     * @param maxSize maximum object size in voxels
     * @param excludeOnEdges whether to exclude objects touching image borders
     * @param intensityImage optional source for intensity measurements; may be
     *        {@code null}
     * @throws IllegalArgumentException if {@code image} is null or empty, or
     *         if {@code intensityImage} has incompatible dimensions
     */
    public static OC3DPlusResult detect(ImagePlus image,
                                        int threshold,
                                        int minSize,
                                        int maxSize,
                                        boolean excludeOnEdges,
                                        ImagePlus intensityImage) {
        if (image == null) {
            throw new IllegalArgumentException(
                    "image must not be null (image=null; expected a 3D ImagePlus).");
        }
        validateImage(image, "image");
        validateIntensityImage(image, intensityImage);
        OC3DPlusParameters params = new OC3DPlusParameters(
                threshold,
                minSize,
                maxSize,
                excludeOnEdges,
                Collections.<MorphPredicate>emptyList(),
                intensityImage,
                null);
        return count(image, params);
    }

    /**
     * Run {@link #count(ImagePlus, OC3DPlusParameters)} on every image in
     * {@code images} concurrently and return the results in input order.
     *
     * <p>Each call uses the thread-safe mcib3d engine path and constructs a
     * fresh engine instance, so the calls share no state. {@code params} is
     * immutable and shared across threads safely.
     *
     * <p>Like {@code count}, this method returns results directly and does not
     * show windows, mutate input images, or write to ImageJ's global Results
     * window.
     *
     * @param images input images; must not be {@code null}
     * @param params shared immutable parameters, or {@code null} to use
     *        builder defaults for each image
     * @param threads pool size, or {@code <= 0} to use
     *                {@link Runtime#availableProcessors()}.
     * @throws IllegalArgumentException if {@code images} is {@code null}
     * @throws RuntimeException wrapping the first worker failure (the pool is
     *         still shut down before the throw)
     */
    public static List<OC3DPlusResult> countAll(List<ImagePlus> images,
                                                final OC3DPlusParameters params,
                                                int threads) {
        if (images == null) {
            throw new IllegalArgumentException("images must not be null (images=null).");
        }
        if (images.isEmpty()) {
            return Collections.emptyList();
        }
        int poolSize = threads > 0 ? threads : Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            List<Future<OC3DPlusResult>> futures = new ArrayList<Future<OC3DPlusResult>>();
            for (final ImagePlus image : images) {
                futures.add(pool.submit(new Callable<OC3DPlusResult>() {
                    @Override public OC3DPlusResult call() {
                        return count(image, params);
                    }
                }));
            }
            List<OC3DPlusResult> results = new ArrayList<OC3DPlusResult>(futures.size());
            for (int i = 0; i < futures.size(); i++) {
                Future<OC3DPlusResult> future = futures.get(i);
                try {
                    results.add(future.get());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while running parallel count", ie);
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                    throw new RuntimeException("Parallel count failed for images[" + i + "] "
                            + imageSummary(images.get(i)) + ": " + throwableMessage(cause), cause);
                }
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    /** Fluent builder for {@link OC3DPlusParameters}. */
    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder. Not thread-safe; use one per construction. */
    public static final class Builder {
        private int threshold = 0;
        private int minSize = 10;
        private int maxSize = Integer.MAX_VALUE;
        private boolean excludeOnEdges = false;
        private ImagePlus intensityImage = null;
        private final List<MorphPredicate> filters = new ArrayList<MorphPredicate>();
        private OC3DPlusParameters.WarningSink warningSink = null;

        private Builder() {}

        public Builder threshold(int t) {
            this.threshold = t;
            return this;
        }

        public Builder minSize(int v) {
            if (v < 0) {
                throw new IllegalArgumentException("minSize must be >= 0 (minSize=" + v + ").");
            }
            this.minSize = v;
            return this;
        }

        public Builder maxSize(int v) {
            if (v < 0) {
                throw new IllegalArgumentException("maxSize must be >= 0 (maxSize=" + v + ").");
            }
            this.maxSize = v;
            return this;
        }

        public Builder excludeOnEdges(boolean b) {
            this.excludeOnEdges = b;
            return this;
        }

        public Builder intensityImage(ImagePlus imp) {
            this.intensityImage = imp;
            return this;
        }

        public Builder addFilter(MorphPredicate predicate) {
            if (predicate == null) {
                throw new IllegalArgumentException("predicate must not be null (predicate=null).");
            }
            filters.add(predicate);
            return this;
        }

        public Builder addFilter(String feature, String operator, double value) {
            return addFilter(new MorphPredicate(feature, operatorFromSymbol(operator), value));
        }

        public Builder warningSink(OC3DPlusParameters.WarningSink sink) {
            this.warningSink = sink;
            return this;
        }

        public OC3DPlusParameters build() {
            if (maxSize < minSize) {
                throw new IllegalStateException(
                        "maxSize (" + maxSize + ") must be >= minSize (" + minSize + ")");
            }
            return new OC3DPlusParameters(
                    threshold, minSize, maxSize, excludeOnEdges,
                    Collections.unmodifiableList(new ArrayList<MorphPredicate>(filters)),
                    intensityImage,
                    warningSink);
        }
    }

    private static MorphPredicate.Operator operatorFromSymbol(String symbol) {
        if (symbol == null) {
            throw new IllegalArgumentException("operator must not be null (operator=null).");
        }
        String s = symbol.trim();
        if (">=".equals(s)) return MorphPredicate.Operator.GE;
        if ("<=".equals(s)) return MorphPredicate.Operator.LE;
        if (">".equals(s)) return MorphPredicate.Operator.GT;
        if ("<".equals(s)) return MorphPredicate.Operator.LT;
        throw new IllegalArgumentException("Unknown operator (operator='" + symbol
                + "'); expected one of >=, <=, >, <");
    }

    private static void validateImage(ImagePlus image, String name) {
        if (image == null) {
            throw new IllegalArgumentException(name + " must not be null ("
                    + name + "=null; expected a non-empty ImagePlus).");
        }
        ImageStack stack = image.getImageStack();
        if (image.getWidth() <= 0 || image.getHeight() <= 0
                || stack == null || stack.getSize() <= 0) {
            throw new IllegalArgumentException(name
                    + " must contain at least one non-empty image slice ("
                    + name + "=" + imageSummary(image) + ").");
        }
    }

    private static void validateIntensityImage(ImagePlus image, ImagePlus intensityImage) {
        if (intensityImage == null) return;
        validateImage(intensityImage, "intensityImage");
        int imageDepth = stackSize(image);
        int intensityDepth = stackSize(intensityImage);
        if (image.getWidth() != intensityImage.getWidth()
                || image.getHeight() != intensityImage.getHeight()
                || imageDepth != intensityDepth) {
            throw new IllegalArgumentException("intensityImage dimensions ("
                    + intensityImage.getWidth() + "x" + intensityImage.getHeight()
                    + "x" + intensityDepth + ") must match image dimensions ("
                    + image.getWidth() + "x" + image.getHeight()
                    + "x" + imageDepth + ") for intensityImage="
                    + imageSummary(intensityImage) + " and image=" + imageSummary(image) + ".");
        }
    }

    private static int stackSize(ImagePlus image) {
        ImageStack stack = image == null ? null : image.getImageStack();
        return stack == null ? 0 : stack.getSize();
    }

    private static String imageSummary(ImagePlus image) {
        if (image == null) return "null";
        String title = image.getTitle();
        return "'" + (title == null ? "" : title) + "' "
                + image.getWidth() + "x" + image.getHeight() + "x" + stackSize(image);
    }

    private static String throwableMessage(Throwable cause) {
        if (cause == null) return "unknown error";
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return cause.getClass().getName();
        }
        return message;
    }
}
