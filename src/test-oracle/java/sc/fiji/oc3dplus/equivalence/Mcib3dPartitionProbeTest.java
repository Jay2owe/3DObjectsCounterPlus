package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import org.junit.Test;
import sc.fiji.oc3d.core.label.Connectivity;
import sc.fiji.oc3d.core.label.LabelParameters;
import sc.fiji.oc3d.core.label.LabelResult;
import sc.fiji.oc3d.core.label.StreamingLabeller;
import sc.fiji.oc3dplus.engine.ObjectsCounter3DWrapper;
import sc.fiji.oc3dplus.engine.ReferenceEngines;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertTrue;

/**
 * Does {@code StreamingLabeller} produce the same object partition as mcib3d's
 * {@code ImageLabeller}? Measured on the current build, before Stage 03 changes
 * anything.
 *
 * <p>This gap is not covered by anything that already exists.
 * {@code Counter3DOracleTest} proved {@code StreamingLabeller} byte-identical to
 * {@code Counter3D} — that is <b>Case A</b>. Case B's reference is mcib3d, and no
 * test in this repository has ever compared the two. Stage 03's exit gate
 * nevertheless requires Case B to be Tier 1 exact as a partition, so the claim
 * has to be measured rather than inherited.
 *
 * <h2>The hyperstack question</h2>
 *
 * {@code ObjectsCounter3DWrapper.runNative} thresholds the whole image and hands
 * the result to {@code ImageHandler.wrap}, so a 2-channel, 4-slice stack is
 * labelled as one <b>8-plane z-series</b>: objects merge across channels and
 * across time points. oc3d-core refuses to do that —
 * {@code LabelImages.requireLabellableVolume} rejects a hyperstack outright,
 * saying that treating it as a z-series "would merge objects across channels and
 * multiply every volume".
 *
 * <p>Both cannot be satisfied at once. To preserve Case B bit-for-bit, the
 * labeller must be shown the same flat plane sequence mcib3d saw, which is what
 * {@link #flatVolume} constructs: the same {@link ImageStack}, presented with
 * hyperstack dimensions dropped. That reproduces the existing behaviour
 * <b>including the cross-channel merge</b>. Whether to keep or fix that is a
 * user decision, and this probe exists to put numbers under it.
 */
public class Mcib3dPartitionProbeTest {

    /** One detection configuration. */
    private static final class Config {
        final String name;
        final int threshold;
        final int minSize;
        final int maxSize;
        final boolean excludeOnEdges;

        Config(String name, int threshold, int minSize, int maxSize, boolean excludeOnEdges) {
            this.name = name;
            this.threshold = threshold;
            this.minSize = minSize;
            this.maxSize = maxSize;
            this.excludeOnEdges = excludeOnEdges;
        }

        @Override public String toString() {
            return name;
        }
    }

    private static final List<Config> CONFIGS = Arrays.asList(
            new Config("default", 100, 0, Integer.MAX_VALUE, false),
            new Config("thr-1", 1, 0, Integer.MAX_VALUE, false),
            new Config("minSize-5", 100, 5, Integer.MAX_VALUE, false),
            new Config("maxSize-50", 100, 0, 50, false),
            new Config("excludeEdges", 100, 0, Integer.MAX_VALUE, true));

    /**
     * The same planes mcib3d labels, presented as a plain volume.
     *
     * <p>The {@link ImageStack} is shared, not copied: this is a re-presentation
     * of the caller's pixels, so the returned image must not be flushed
     * independently of its source.
     */
    static ImagePlus flatVolume(ImagePlus image) {
        if (image == null) return null;
        int channels = Math.max(1, image.getNChannels());
        int frames = Math.max(1, image.getNFrames());
        if (channels == 1 && frames == 1) return image;
        ImagePlus flat = new ImagePlus(image.getTitle(), image.getStack());
        if (image.getCalibration() != null) {
            flat.setCalibration(image.getCalibration().copy());
        }
        return flat;
    }

    /** label -> sorted linear voxel indices, for every label above zero. */
    private static Map<Integer, long[]> partitionOf(ImagePlus labelImage) {
        Map<Integer, List<Long>> collected = new TreeMap<Integer, List<Long>>();
        if (labelImage == null) return new TreeMap<Integer, long[]>();
        ImageStack stack = labelImage.getStack();
        int pixels = labelImage.getWidth() * labelImage.getHeight();
        for (int z = 1; z <= stack.getSize(); z++) {
            ImageProcessor processor = stack.getProcessor(z);
            if (processor == null) continue;
            long planeBase = (long) (z - 1) * pixels;
            for (int i = 0; i < pixels; i++) {
                int label = (int) processor.getf(i);
                if (label <= 0) continue;
                List<Long> voxels = collected.get(Integer.valueOf(label));
                if (voxels == null) {
                    voxels = new ArrayList<Long>();
                    collected.put(Integer.valueOf(label), voxels);
                }
                voxels.add(Long.valueOf(planeBase + i));
            }
        }
        Map<Integer, long[]> out = new TreeMap<Integer, long[]>();
        for (Map.Entry<Integer, List<Long>> entry : collected.entrySet()) {
            List<Long> voxels = entry.getValue();
            long[] array = new long[voxels.size()];
            for (int i = 0; i < array.length; i++) array[i] = voxels.get(i).longValue();
            Arrays.sort(array);
            out.put(entry.getKey(), array);
        }
        return out;
    }

    /**
     * A partition as a label-independent multiset: each object becomes a digest
     * of its sorted voxel indices, so two partitions match iff they group the
     * same voxels, whatever they number them.
     */
    private static List<String> partitionDigests(Map<Integer, long[]> partition) {
        List<String> out = new ArrayList<String>();
        for (long[] voxels : partition.values()) {
            long hash = 1125899906842597L;
            for (int i = 0; i < voxels.length; i++) {
                hash = hash * 31L + voxels[i];
            }
            out.add(voxels.length + ":" + Long.toHexString(hash));
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static int[] voxelCounts(Map<Integer, long[]> partition) {
        int[] out = new int[partition.size()];
        int i = 0;
        for (long[] voxels : partition.values()) out[i++] = voxels.length;
        Arrays.sort(out);
        return out;
    }

    @Test
    public void streamingLabellerAgainstMcib3dOnCaseBFixtures() {
        List<Fixture> caseB = new ArrayList<Fixture>();
        for (Fixture fixture : FixtureCorpus.all()) {
            if (fixture.harnessCase == HarnessCase.B) caseB.add(fixture);
        }
        assertTrue("no Case B fixtures found; the corpus changed shape", !caseB.isEmpty());

        System.out.println("=== StreamingLabeller vs mcib3d ImageLabeller, Case B partitions ===");
        System.out.println("    (mcib3d labels the flattened plane sequence; the labeller is");
        System.out.println("     shown the same sequence so the comparison is apples to apples)");
        System.out.println();

        Map<String, String> verdicts = new LinkedHashMap<String, String>();

        for (Fixture fixture : caseB) {
            for (Config config : CONFIGS) {
                String key = fixture.name + " / " + config.name;
                ImagePlus input = fixture.createInput();
                ImagePlus mcib3dLabels = null;
                ImagePlus coreLabels = null;
                try {
                    System.out.println("-- " + key);
                    System.out.println("   input      " + input.getWidth() + "x" + input.getHeight()
                            + " stack=" + input.getStack().getSize()
                            + " c=" + Math.max(1, input.getNChannels())
                            + " z=" + Math.max(1, input.getNSlices())
                            + " t=" + Math.max(1, input.getNFrames())
                            + " bitDepth=" + input.getBitDepth());

                    ObjectsCounter3DWrapper.Result native3d = new ReferenceEngines().runNative(
                            input, config.threshold, config.minSize, config.maxSize,
                            config.excludeOnEdges, null, true, false);
                    mcib3dLabels = native3d == null ? null : native3d.getObjectsMap();

                    ImagePlus flat = flatVolume(input);
                    LabelResult labelled = StreamingLabeller.label(flat, new LabelParameters()
                            .threshold(config.threshold)
                            .minSize(config.minSize)
                            .maxSize(config.maxSize)
                            .excludeOnEdges(config.excludeOnEdges)
                            .connectivity(Connectivity.TWENTY_SIX));
                    coreLabels = labelled.labelImage();

                    Map<Integer, long[]> mcib3dPartition = partitionOf(mcib3dLabels);
                    Map<Integer, long[]> corePartition = partitionOf(coreLabels);

                    List<String> mcib3dDigests = partitionDigests(mcib3dPartition);
                    List<String> coreDigests = partitionDigests(corePartition);
                    int[] mcib3dSizes = voxelCounts(mcib3dPartition);
                    int[] coreSizes = voxelCounts(corePartition);

                    System.out.println("   mcib3d     objects=" + mcib3dPartition.size()
                            + " sizes=" + summarise(mcib3dSizes));
                    System.out.println("   core       objects=" + corePartition.size()
                            + " sizes=" + summarise(coreSizes));

                    String verdict;
                    if (mcib3dDigests.equals(coreDigests)) {
                        verdict = "IDENTICAL PARTITION";
                    } else if (Arrays.equals(mcib3dSizes, coreSizes)) {
                        verdict = "same object sizes, DIFFERENT grouping";
                    } else if (mcib3dPartition.size() == corePartition.size()) {
                        verdict = "same object COUNT, different sizes";
                    } else {
                        verdict = "DIFFERENT object count: mcib3d=" + mcib3dPartition.size()
                                + " core=" + corePartition.size();
                    }
                    System.out.println("   verdict    " + verdict);
                    verdicts.put(key, verdict);
                } catch (RuntimeException failure) {
                    String verdict = "THREW " + failure.getClass().getSimpleName()
                            + ": " + failure.getMessage();
                    System.out.println("   verdict    " + verdict);
                    verdicts.put(key, verdict);
                } finally {
                    Stacks.discard(mcib3dLabels);
                    Stacks.discard(coreLabels);
                    Stacks.discard(input);
                }
                System.out.println();
            }
        }

        System.out.println("=== summary ===");
        int identical = 0;
        for (Map.Entry<String, String> entry : verdicts.entrySet()) {
            System.out.println("  " + pad(entry.getKey(), 34) + entry.getValue());
            if ("IDENTICAL PARTITION".equals(entry.getValue())) identical++;
        }
        System.out.println("  " + identical + " of " + verdicts.size()
                + " configurations produce an identical partition");
    }

    /**
     * Which planes of a hyperstack does the mcib3d path actually read?
     *
     * <p>The partition comparison above showed mcib3d finding 36 of 72 foreground
     * voxels on a 2-channel 4-slice stack, and 18 of 72 on a 2-channel 2-slice
     * 2-frame one — in both cases exactly the count that {@code nSlices} planes
     * would hold. That points at truncation rather than the cross-channel merge
     * oc3d-core warns about, but "points at" is not evidence.
     *
     * <p>So: one uniquely placed marker voxel per plane, then read back which
     * markers survive. Whichever planes mcib3d read, their markers appear.
     */
    @Test
    public void whichPlanesOfAHyperstackDoesMcib3dRead() {
        int width = 16;
        int height = 16;
        int channels = 2;
        int slices = 4;
        int planes = channels * slices;

        ImagePlus input = Stacks.bytes("plane-markers", width, height, planes);
        // Marker for stack plane p at (p, p): unique, isolated, unambiguous.
        for (int p = 0; p < planes; p++) {
            Stacks.set(input, p + 1, p + 1, p, Stacks.FOREGROUND);
        }
        input.setDimensions(channels, slices, 1);

        ImagePlus labels = null;
        try {
            System.out.println("=== which planes does the mcib3d path read? ===");
            System.out.println("  input        " + width + "x" + height
                    + " stack=" + planes + " c=" + channels + " z=" + slices + " t=1");
            System.out.println("  markers      one per stack plane, at (p+1, p+1) on plane p");

            ObjectsCounter3DWrapper.Result result = new ReferenceEngines().runNative(
                    input, 100, 0, Integer.MAX_VALUE, false, null, true, false);
            labels = result == null ? null : result.getObjectsMap();

            int labelDepth = labels == null || labels.getStack() == null
                    ? -1 : labels.getStack().getSize();
            System.out.println("  label depth  " + labelDepth
                    + "  (input stack was " + planes + " planes)");

            Map<Integer, long[]> partition = partitionOf(labels);
            List<Integer> planesSeen = new ArrayList<Integer>();
            int pixels = width * height;
            for (long[] voxels : partition.values()) {
                for (int i = 0; i < voxels.length; i++) {
                    planesSeen.add(Integer.valueOf((int) (voxels[i] / pixels)));
                }
            }
            java.util.Collections.sort(planesSeen);
            System.out.println("  objects      " + partition.size() + " of " + planes + " markers");
            System.out.println("  planes read  " + planesSeen);
            System.out.println("  planes lost  " + missing(planes, planesSeen));

            ImagePlus flat = flatVolume(input);
            LabelResult core = StreamingLabeller.label(flat, new LabelParameters()
                    .threshold(100).connectivity(Connectivity.TWENTY_SIX));
            System.out.println("  core objects " + core.objectCount() + " of " + planes);
            Stacks.discard(core.labelImage());
        } finally {
            Stacks.discard(labels);
            Stacks.discard(input);
        }
    }

    /**
     * Does {@code excludeOnEdges} mean the same thing on Case B as in oc3d-core?
     *
     * <p>{@code runNative} calls {@code getExcludeBorders(labelledIH, false)}. The
     * flag's meaning is not documented in the jar, and the partition run above
     * showed a z-spanning object surviving mcib3d's filter while the labeller
     * dropped it. This isolates the question on a plain single volume, so the
     * hyperstack truncation cannot confound it: one object touching only the z
     * border, one touching only an xy border, neither touching both.
     */
    @Test
    public void edgeRuleOnASingleVolume() {
        int width = 16;
        int height = 16;
        int depth = 8;

        ImagePlus input = Stacks.bytes("edge-rule", width, height, depth);
        // Touches z=0 only: interior in x and y, starts on the first slice.
        Stacks.box(input, 4, 7, 4, 7, 0, 2, Stacks.FOREGROUND);
        // Touches x=0 only: interior in y and z.
        Stacks.box(input, 0, 2, 10, 13, 3, 5, Stacks.FOREGROUND);
        // Touches nothing: the control.
        Stacks.box(input, 9, 12, 4, 7, 3, 5, Stacks.FOREGROUND);

        ImagePlus mcib3dLabels = null;
        ImagePlus coreLabels = null;
        try {
            System.out.println("=== excludeOnEdges, single volume, three objects ===");
            System.out.println("  A touches z=0 only, B touches x=0 only, C touches no border");

            ObjectsCounter3DWrapper.Result result = new ReferenceEngines().runNative(
                    input, 100, 0, Integer.MAX_VALUE, true, null, true, false);
            mcib3dLabels = result == null ? null : result.getObjectsMap();

            ImagePlus flat = flatVolume(input);
            LabelResult core = StreamingLabeller.label(flat, new LabelParameters()
                    .threshold(100).excludeOnEdges(true)
                    .connectivity(Connectivity.TWENTY_SIX));
            coreLabels = core.labelImage();

            Map<Integer, long[]> mcib3dPartition = partitionOf(mcib3dLabels);
            Map<Integer, long[]> corePartition = partitionOf(coreLabels);

            System.out.println("  mcib3d kept  " + mcib3dPartition.size()
                    + " objects, sizes " + summarise(voxelCounts(mcib3dPartition)));
            System.out.println("  core kept    " + corePartition.size()
                    + " objects, sizes " + summarise(voxelCounts(corePartition)));
            System.out.println("  survivors are identified by which border they touch:");
            describeSurvivors("  mcib3d", mcib3dPartition, width, height, depth);
            describeSurvivors("  core  ", corePartition, width, height, depth);
        } finally {
            Stacks.discard(mcib3dLabels);
            Stacks.discard(coreLabels);
            Stacks.discard(input);
        }
    }

    /**
     * Does the truncation reach real user data through the documented API?
     *
     * <p>The synthetic finding is only as interesting as its reach.
     * {@code OC3DPlusBatchRunner} calls {@code IJ.openImage} and hands the result
     * straight to {@code OC3DPlus.count} with no channel extraction
     * (`OC3DPlusBatchRunner:103,116`), so if a multichannel TIFF loses planes here
     * it loses them in a real batch run too.
     *
     * <pre>
     * mvn -o -B test -Dtest=Mcib3dPartitionProbeTest#truncationOnRealMultichannelData \
     *     -Doc3dplus.realCorpus=docs/migration/real-corpus.txt
     * </pre>
     */
    @Test
    public void truncationOnRealMultichannelData() {
        String configured = System.getProperty("oc3dplus.realCorpus");
        org.junit.Assume.assumeTrue("set -Doc3dplus.realCorpus=docs/migration/real-corpus.txt",
                configured != null && !configured.trim().isEmpty());

        List<java.io.File> files = new ArrayList<java.io.File>();
        java.io.File list = new java.io.File(configured.trim());
        org.junit.Assume.assumeTrue("corpus list not found: " + list, list.isFile());
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(list));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    java.io.File file = new java.io.File(trimmed);
                    if (file.isFile()) files.add(file);
                }
            } finally {
                reader.close();
            }
        } catch (java.io.IOException failure) {
            org.junit.Assume.assumeNoException(failure);
        }

        System.out.println("=== plane truncation on real multichannel data ===");
        System.out.println("    via OC3DPlus.count, the same entry point the batch runner uses");
        System.out.println();

        int examined = 0;
        int truncated = 0;
        // Smallest first, so the cheapest evidence arrives first and a heap limit
        // cannot silently skip the whole check.
        java.util.Collections.sort(files, new java.util.Comparator<java.io.File>() {
            @Override public int compare(java.io.File a, java.io.File b) {
                return Long.compare(a.length(), b.length());
            }
        });

        for (java.io.File file : files) {
            if (examined >= 3) break;
            ImagePlus input = null;
            ImagePlus labels = null;
            try {
                input = ij.IJ.openImage(file.getAbsolutePath());
                if (input == null || input.getStack() == null) continue;
                int stackSize = input.getStack().getSize();
                int channels = Math.max(1, input.getNChannels());
                int slices = Math.max(1, input.getNSlices());
                int frames = Math.max(1, input.getNFrames());
                if (channels == 1 && frames == 1) continue;

                examined++;
                System.out.println("-- " + file.getName());
                System.out.println("   input      " + input.getWidth() + "x" + input.getHeight()
                        + " stack=" + stackSize + " c=" + channels + " z=" + slices
                        + " t=" + frames + " bitDepth=" + input.getBitDepth());

                // A high threshold and a large minimum size deliberately: the
                // question is how many PLANES reach the engine, and object count
                // does not bear on it. Leaving them at 100/1 on real 16-bit data
                // produces millions of noise objects and the run never finishes,
                // which measures the wrong thing slowly.
                int threshold = Integer.getInteger("oc3dplus.probeThreshold", 2000).intValue();
                int minSize = Integer.getInteger("oc3dplus.probeMinSize", 500).intValue();
                System.out.println("   settings   threshold=" + threshold
                        + " minSize=" + minSize);
                sc.fiji.oc3dplus.api.OC3DPlusResult result = sc.fiji.oc3dplus.api.OC3DPlus.count(
                        input, sc.fiji.oc3dplus.api.OC3DPlus.builder()
                                .threshold(threshold).minSize(minSize).build());
                labels = result.labelImage();
                int labelDepth = labels == null || labels.getStack() == null
                        ? -1 : labels.getStack().getSize();
                System.out.println("   label      depth=" + labelDepth
                        + " objects=" + result.objectCount());
                if (labelDepth >= 0 && labelDepth < stackSize) {
                    truncated++;
                    System.out.println("   TRUNCATED  " + labelDepth + " of " + stackSize
                            + " planes measured; " + (stackSize - labelDepth)
                            + " planes ("
                            + Math.round(100.0 * (stackSize - labelDepth) / stackSize)
                            + "%) never reached the engine");
                } else {
                    System.out.println("   full depth measured");
                }
            } catch (OutOfMemoryError memory) {
                System.out.println("   skipped: OutOfMemoryError opening or measuring");
            } finally {
                Stacks.discard(labels);
                Stacks.discard(input);
            }
            System.out.println();
        }
        System.out.println("=== " + truncated + " of " + examined
                + " real multichannel stacks lost planes ===");
    }

    private static void describeSurvivors(String prefix,
                                          Map<Integer, long[]> partition,
                                          int width, int height, int depth) {
        int pixels = width * height;
        for (Map.Entry<Integer, long[]> entry : partition.entrySet()) {
            boolean touchesZ = false;
            boolean touchesXY = false;
            for (long voxel : entry.getValue()) {
                int z = (int) (voxel / pixels);
                int rest = (int) (voxel % pixels);
                int y = rest / width;
                int x = rest % width;
                if (z == 0 || z == depth - 1) touchesZ = true;
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) touchesXY = true;
            }
            System.out.println(prefix + "   label " + entry.getKey()
                    + " size=" + entry.getValue().length
                    + " touchesZborder=" + touchesZ
                    + " touchesXYborder=" + touchesXY);
        }
    }

    private static List<Integer> missing(int planes, List<Integer> seen) {
        List<Integer> out = new ArrayList<Integer>();
        for (int p = 0; p < planes; p++) {
            if (!seen.contains(Integer.valueOf(p))) out.add(Integer.valueOf(p));
        }
        return out;
    }

    private static String summarise(int[] sizes) {
        if (sizes.length == 0) return "[]";
        long total = 0;
        for (int i = 0; i < sizes.length; i++) total += sizes[i];
        return "[min=" + sizes[0] + " max=" + sizes[sizes.length - 1]
                + " total=" + total + "]";
    }

    private static String pad(String text, int width) {
        StringBuilder out = new StringBuilder(text);
        while (out.length() < width) out.append(' ');
        return out.toString();
    }
}
