package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusParameters;
import sc.fiji.oc3dplus.api.OC3DPlusResult;
import sc.fiji.oc3dplus.engine.ObjectsCounter3DWrapper;
import sc.fiji.oc3dplus.engine.ReferenceEngines;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The two defects the unified engine fixes, each shown failing on the old path and
 * passing on the new one.
 *
 * <p>Both were found by the equivalence harness rather than reasoned about in
 * advance, and neither is mentioned in the migration plan.
 */
public class UnifiedEngineFixesTest {

    // ── Defect: objects above the 65 535-label ceiling ───────────────────────

    /**
     * The shipped counter reports more objects than its label image can hold.
     *
     * <p>On a volume containing 65 536 separated objects the classic path returns
     * {@code objectCount = 65536} and 65 536 statistics rows, while the 16-bit
     * label image it hands back contains only 65 535 distinct labels: the
     * 65 536th wraps to zero and that object becomes background in the very map
     * it is counted in. The table and the picture disagree, silently.
     *
     * <p>65 534 and 65 535 objects are unaffected, which places the ceiling
     * exactly rather than merely nearby.
     */
    @Test
    public void classicCounterLosesTheObjectAboveTheSixteenBitCeiling() {
        ImagePlus input = manyIsolatedObjects(65536);
        ImagePlus labels = null;
        try {
            ObjectsCounter3DWrapper.Result result = new ReferenceEngines().run(
                    input, 100, 1, Integer.MAX_VALUE, false, false, true, false);
            labels = result.getObjectsMap();
            ResultsTable stats = result.getStatistics();

            int distinct = distinctLabels(labels).size();
            System.out.println("=== 16-bit label ceiling, classic counter ===");
            System.out.println("  objects in the fixture   65536");
            System.out.println("  rows in the table        " + stats.size());
            System.out.println("  distinct labels in map   " + distinct);
            System.out.println("  label image bit depth    " + labels.getBitDepth());

            assertEquals("the table counts every object", 65536, stats.size());
            assertNotEquals("if the map now holds 65536 labels the shipped jar changed",
                    65536, distinct);
            assertEquals("the 65536th label wraps to zero, so one object is lost",
                    65535, distinct);
        } finally {
            Stacks.discard(labels);
            Stacks.discard(input);
        }
    }

    /** The unified engine keeps all 65 536, and the table and map agree. */
    @Test
    public void unifiedEngineKeepsEveryObjectAboveTheCeiling() {
        ImagePlus input = manyIsolatedObjects(65536);
        ImagePlus labels = null;
        try {
            OC3DPlusResult result = OC3DPlus.count(input,
                    OC3DPlus.builder().threshold(100).minSize(1).build());
            labels = result.labelImage();

            Set<Integer> distinct = distinctLabels(labels);
            System.out.println("=== 16-bit label ceiling, unified engine ===");
            System.out.println("  objectCount              " + result.objectCount());
            System.out.println("  distinct labels in map   " + distinct.size());
            System.out.println("  label image bit depth    " + labels.getBitDepth());

            assertEquals("every object is counted", 65536, result.objectCount());
            assertEquals("every object is present in the label image",
                    65536, distinct.size());
            assertEquals("the table and the map agree",
                    result.objectCount(), result.statistics().size());
            assertTrue("labels above 65535 need more than 16 bits to survive",
                    labels.getBitDepth() > 16);
        } finally {
            Stacks.discard(labels);
            Stacks.discard(input);
        }
    }

    /** Just below the ceiling both agree, which locates it exactly. */
    @Test
    public void bothPathsAgreeJustBelowTheCeiling() {
        for (int count = 65534; count <= 65535; count++) {
            ImagePlus input = manyIsolatedObjects(count);
            ImagePlus classicLabels = null;
            ImagePlus unifiedLabels = null;
            try {
                ObjectsCounter3DWrapper.Result classic = new ReferenceEngines().run(
                        input, 100, 1, Integer.MAX_VALUE, false, false, true, false);
                classicLabels = classic.getObjectsMap();
                OC3DPlusResult unified = OC3DPlus.count(input,
                        OC3DPlus.builder().threshold(100).minSize(1).build());
                unifiedLabels = unified.labelImage();

                assertEquals(count + " objects: classic map", count,
                        distinctLabels(classicLabels).size());
                assertEquals(count + " objects: unified map", count,
                        distinctLabels(unifiedLabels).size());
            } finally {
                Stacks.discard(classicLabels);
                Stacks.discard(unifiedLabels);
                Stacks.discard(input);
            }
        }
    }

    // ── Channel and frame selection, on an image that can tell ───────────────

    /**
     * A synthetic hyperstack whose channels differ, so the selection is testable.
     *
     * <p>The corpus fixtures {@code multichannel-2c} and {@code hyperstack-2c2t}
     * cannot do this job: their content is identical in every channel and slice,
     * so measuring the wrong channel returns the right answer by accident. They
     * are left alone - their goldens are immutable - and this test carries the
     * discrimination instead.
     *
     * <p>Channel 1 holds three objects, channel 2 holds one, and they occupy
     * different positions, so the object count alone says which was measured.
     */
    @Test
    public void measuresTheSelectedChannelNotTheWholeStack() {
        ImagePlus input = twoChannelsWithDifferentContent();
        ImagePlus first = null;
        ImagePlus second = null;
        try {
            OC3DPlusResult channelOne = OC3DPlus.count(input, OC3DPlus.builder()
                    .threshold(100).minSize(1).channel(1).frame(1).build());
            first = channelOne.labelImage();
            OC3DPlusResult channelTwo = OC3DPlus.count(input, OC3DPlus.builder()
                    .threshold(100).minSize(1).channel(2).frame(1).build());
            second = channelTwo.labelImage();

            System.out.println("=== channel selection ===");
            System.out.println("  channel 1 objects  " + channelOne.objectCount());
            System.out.println("  channel 2 objects  " + channelTwo.objectCount());

            assertEquals("channel 1 holds three objects", 3, channelOne.objectCount());
            assertEquals("channel 2 holds one object", 1, channelTwo.objectCount());
            assertEquals("a channel is a z-series in its own right, so the label image "
                            + "has one plane per slice and not one per stack plane",
                    4, first.getStack().getSize());
            assertEquals(4, second.getStack().getSize());
        } finally {
            Stacks.discard(first);
            Stacks.discard(second);
            Stacks.discard(input);
        }
    }

    /**
     * Objects are never joined across a channel boundary.
     *
     * <p>The old path read consecutive stack planes, and ImageJ interleaves
     * channels, so channel 1 and channel 2 of the same slice were labelled as
     * though they were adjacent z slices. Here each channel holds a single voxel
     * at the same (x, y): treating the stack as a z-series would fuse them into
     * one object spanning two "slices".
     */
    @Test
    public void objectsAreNotJoinedAcrossChannels() {
        int width = 8;
        int height = 8;
        ImageStack stack = new ImageStack(width, height);
        for (int plane = 0; plane < 4; plane++) {
            stack.addSlice(new ShortProcessor(width, height));
        }
        ImagePlus input = new ImagePlus("touching-across-channels", stack);
        input.setDimensions(2, 2, 1);
        // Same (x, y) in both channels of both slices: adjacent only if the
        // channel axis is mistaken for depth.
        for (int plane = 1; plane <= 4; plane++) {
            stack.getProcessor(plane).set(4, 4, 200);
        }

        ImagePlus labels = null;
        try {
            OC3DPlusResult result = OC3DPlus.count(input, OC3DPlus.builder()
                    .threshold(100).minSize(1).channel(1).frame(1).build());
            labels = result.labelImage();
            assertEquals("channel 1 contributes one voxel per slice, so one object "
                            + "of two voxels - and nothing from channel 2",
                    1, result.objectCount());
            assertEquals(2.0, result.statistics().getValue("Nb of obj. voxels", 0), 0.0);
        } finally {
            Stacks.discard(labels);
            Stacks.discard(input);
        }
    }

    /** With no channel given, the image's own current position is used. */
    @Test
    public void defaultsToTheImagesCurrentPosition() {
        ImagePlus input = twoChannelsWithDifferentContent();
        ImagePlus labels = null;
        try {
            input.setPosition(2, 1, 1);
            OC3DPlusResult result = OC3DPlus.count(input, OC3DPlus.builder()
                    .threshold(100).minSize(1).build());
            labels = result.labelImage();
            assertEquals("channel 2 was showing, so channel 2 is what gets measured",
                    1, result.objectCount());
        } finally {
            Stacks.discard(labels);
            Stacks.discard(input);
        }
    }

    /** Selecting a channel that does not exist clamps rather than throwing. */
    @Test
    public void outOfRangeChannelClampsToTheLastOne() {
        ImagePlus input = twoChannelsWithDifferentContent();
        ImagePlus labels = null;
        try {
            OC3DPlusResult result = OC3DPlus.count(input, OC3DPlus.builder()
                    .threshold(100).minSize(1).channel(99).frame(99).build());
            labels = result.labelImage();
            assertEquals(1, result.objectCount());
        } finally {
            Stacks.discard(labels);
            Stacks.discard(input);
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /**
     * A 2-channel, 4-slice hyperstack. Channel 1 carries three separated objects,
     * channel 2 exactly one, at a position channel 1 leaves empty.
     */
    private static ImagePlus twoChannelsWithDifferentContent() {
        int width = 24;
        int height = 24;
        int channels = 2;
        int slices = 4;
        ImageStack stack = new ImageStack(width, height);
        for (int plane = 0; plane < channels * slices; plane++) {
            stack.addSlice(new ShortProcessor(width, height));
        }
        ImagePlus image = new ImagePlus("two-channels", stack);
        image.setDimensions(channels, slices, 1);

        for (int z = 1; z <= slices; z++) {
            ImageProcessor channelOne = stack.getProcessor(image.getStackIndex(1, z, 1));
            channelOne.set(2, 2, 200);
            channelOne.set(2, 12, 200);
            channelOne.set(12, 2, 200);

            ImageProcessor channelTwo = stack.getProcessor(image.getStackIndex(2, z, 1));
            channelTwo.set(18, 18, 200);
        }
        return image;
    }

    /**
     * {@code count} isolated single-voxel objects on a 26-connectivity-safe
     * lattice: every third position in x and y, so no two ever touch.
     */
    private static ImagePlus manyIsolatedObjects(int count) {
        int perRow = 256;
        int perSlice = perRow * perRow;
        int slices = (count + perSlice - 1) / perSlice;
        int width = perRow * 2;
        int height = perRow * 2;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < slices; z++) {
            stack.addSlice(new ShortProcessor(width, height));
        }
        int written = 0;
        for (int z = 0; z < slices && written < count; z++) {
            ImageProcessor slice = stack.getProcessor(z + 1);
            for (int y = 0; y < perRow && written < count; y++) {
                for (int x = 0; x < perRow && written < count; x++) {
                    slice.set(x * 2, y * 2, 200);
                    written++;
                }
            }
        }
        return new ImagePlus("isolated-" + count, stack);
    }

    private static Set<Integer> distinctLabels(ImagePlus labels) {
        Set<Integer> out = new HashSet<Integer>();
        if (labels == null || labels.getStack() == null) return out;
        ImageStack stack = labels.getStack();
        int pixels = labels.getWidth() * labels.getHeight();
        for (int z = 1; z <= stack.getSize(); z++) {
            ImageProcessor processor = stack.getProcessor(z);
            for (int i = 0; i < pixels; i++) {
                int value = (int) processor.getf(i);
                if (value > 0) out.add(Integer.valueOf(value));
            }
        }
        return out;
    }
}
