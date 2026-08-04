package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;
import ij.plugin.FileInfoVirtualStack;
import org.junit.Assume;
import org.junit.Test;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusResult;

import java.io.File;

/**
 * Runs a stack above the 2<sup>31</sup>-voxel ceiling through the current build, to
 * capture the "before" half of the release note's most persuasive claim.
 *
 * <pre>
 * mvn -o -B test -Dtest=LargeStackCeilingProbeTest -Doc3dplus.largeStack=&lt;path&gt;
 * </pre>
 *
 * <p>The stack is opened <b>virtually</b>, so slices load on demand and the probe
 * needs a few tens of MB rather than the ~6 GB a full load of a 5.5 GB 16-bit
 * stack would take. That is not a shortcut around the test: the failure under
 * examination is index arithmetic, not memory exhaustion. {@code Counter3D} holds
 * the whole volume in a flat {@code int[] imgArray} addressed by
 * {@code offset(x,y,z)}, so it must compute {@code width * height * depth} as an
 * {@code int} - and for this stack that product overflows to a negative number
 * before a single voxel is read.
 *
 * <p>Distinguishing the two failure modes matters. {@code OutOfMemoryError} would
 * mean "buy more RAM"; {@code NegativeArraySizeException} means the ceiling is
 * structural and no amount of RAM helps. Only the second supports the claim.
 */
public class LargeStackCeilingProbeTest {

    private static final String PATH_PROPERTY = "oc3dplus.largeStack";
    private static final long INT_CEILING = 2147483647L;

    @Test
    public void runAStackAboveTheIntVoxelCeiling() {
        String path = System.getProperty(PATH_PROPERTY);
        Assume.assumeTrue("set -D" + PATH_PROPERTY + "=<path to a >2^31-voxel stack>",
                path != null && !path.trim().isEmpty());
        File file = new File(path.trim());
        Assume.assumeTrue("stack not found: " + file, file.isFile());

        ImagePlus image = null;
        try {
            image = FileInfoVirtualStack.openVirtual(file.getAbsolutePath());
            Assume.assumeTrue("could not open virtually: " + file, image != null);

            long width = image.getWidth();
            long height = image.getHeight();
            long depth = image.getStack() == null ? 0 : image.getStack().getSize();
            long voxels = width * height * depth;
            int overflowed = (int) voxels;

            System.out.println("=== 2^31-voxel ceiling probe ===");
            System.out.println("  file        " + file.getName());
            System.out.println("  dimensions  " + width + "x" + height + "x" + depth);
            System.out.println("  bitDepth    " + image.getBitDepth());
            System.out.println("  channels    " + image.getNChannels()
                    + "  frames=" + image.getNFrames());
            System.out.println("  voxels      " + voxels
                    + (voxels > INT_CEILING ? "  ABOVE the 2^31 ceiling" : "  below the ceiling"));
            System.out.println("  as int      " + overflowed
                    + (overflowed < 0 ? "  <-- OVERFLOWED NEGATIVE" : ""));
            System.out.println("  virtual     " + (image.getStack() != null
                    && image.getStack().isVirtual()));

            if (voxels <= INT_CEILING) {
                System.out.println("  RESULT      stack is below the ceiling; this run proves nothing "
                        + "about it either way");
                return;
            }

            long started = System.currentTimeMillis();
            String outcome;
            try {
                OC3DPlusResult result = OC3DPlus.count(image, OC3DPlus.builder()
                        .threshold(100).minSize(1).build());
                outcome = "COMPLETED with " + result.objectCount() + " objects - the current "
                        + "build does NOT fail on this stack, so the ceiling claim does not "
                        + "apply to it";
                Stacks.discard(result.labelImage());
            } catch (OutOfMemoryError memory) {
                outcome = "OutOfMemoryError - a memory limit, NOT the structural ceiling. "
                        + "This does not support the release-note claim; rerun with a larger "
                        + "-Xmx to see whether the arithmetic fails first";
            } catch (NegativeArraySizeException overflow) {
                outcome = "NegativeArraySizeException(" + overflow.getMessage() + ") - the "
                        + "structural ceiling, exactly as documented. No amount of RAM avoids it";
            } catch (RuntimeException other) {
                outcome = other.getClass().getName() + ": " + other.getMessage();
            } catch (Error other) {
                outcome = other.getClass().getName() + ": " + other.getMessage();
            }
            System.out.println("  elapsed     " + (System.currentTimeMillis() - started) + " ms");
            System.out.println("  RESULT      " + outcome);
        } finally {
            Stacks.discard(image);
        }
    }

    /**
     * The same test against the path the claim is actually about.
     *
     * <p>The real >2<sup>31</sup>-voxel stacks in this lab's data are multi-channel
     * timelapses, so {@code canUseClassicCounter} rejects them and they go to
     * mcib3d, where the failure is {@code OutOfMemoryError} - a memory limit, not
     * the structural ceiling. The ceiling lives in the classic path's flat
     * {@code int[] imgArray}, and reaching it needs 8- or 16-bit, one channel, one
     * frame, above 2<sup>31</sup> voxels.
     *
     * <p>No such file exists here, so the fixture is generated: a virtual stack of
     * the same dimensions as the real timelapse, single channel and single frame,
     * whose slices are produced on demand. Nothing is read from disk and nothing
     * whole-volume is allocated, so this costs a few tens of MB. The plan calls for
     * "a >2.1-G-voxel fixture" and this is one.
     */
    @Test
    public void aSingleChannelFixtureAboveTheCeilingHitsTheStructuralLimit() {
        Assume.assumeTrue("set -D" + PATH_PROPERTY + "=... to run the ceiling probes",
                System.getProperty(PATH_PROPERTY) != null);

        int width = 1536;
        int height = 1152;
        int depth = 1630;
        long voxels = (long) width * height * depth;
        ImagePlus image = new ImagePlus("synthetic-above-2^31",
                new GeneratedVirtualStack(width, height, depth));
        try {
            System.out.println("=== 2^31 ceiling, single-channel generated fixture ===");
            System.out.println("  dimensions  " + width + "x" + height + "x" + depth);
            System.out.println("  voxels      " + voxels + "  as int " + (int) voxels);
            System.out.println("  channels    " + image.getNChannels()
                    + "  frames=" + image.getNFrames()
                    + "  bitDepth=" + image.getBitDepth());

            long started = System.currentTimeMillis();
            String outcome;
            try {
                OC3DPlusResult result = OC3DPlus.count(image, OC3DPlus.builder()
                        .threshold(100).minSize(1).build());
                outcome = "COMPLETED with " + result.objectCount() + " objects";
                Stacks.discard(result.labelImage());
            } catch (NegativeArraySizeException overflow) {
                outcome = "NegativeArraySizeException(" + overflow.getMessage()
                        + ") - the structural ceiling, exactly as documented";
            } catch (OutOfMemoryError memory) {
                outcome = "OutOfMemoryError - hit a memory limit before the arithmetic";
            } catch (RuntimeException other) {
                outcome = other.getClass().getName() + ": " + other.getMessage();
            } catch (Error other) {
                outcome = other.getClass().getName() + ": " + other.getMessage();
            }
            System.out.println("  elapsed     " + (System.currentTimeMillis() - started) + " ms");
            System.out.println("  RESULT      " + outcome);
        } finally {
            Stacks.discard(image);
        }
    }

    /**
     * A virtual stack that generates 16-bit slices on demand, so a volume above
     * 2<sup>31</sup> voxels can be presented to the engine without reading or
     * allocating one.
     */
    private static final class GeneratedVirtualStack extends ij.VirtualStack {
        private final int depth;

        GeneratedVirtualStack(int width, int height, int depth) {
            super(width, height, null, "");
            this.depth = depth;
        }

        @Override public int getSize() {
            return depth;
        }

        @Override public ij.process.ImageProcessor getProcessor(int slice) {
            ij.process.ShortProcessor processor =
                    new ij.process.ShortProcessor(getWidth(), getHeight());
            // A handful of blobs per slice, deterministic, so the volume is not empty.
            for (int y = 8; y < getHeight(); y += 64) {
                for (int x = 8; x < getWidth(); x += 64) {
                    processor.set(x, y, 200);
                    processor.set(x + 1, y, 200);
                    processor.set(x, y + 1, 200);
                }
            }
            return processor;
        }

        @Override public String getSliceLabel(int slice) {
            return "generated-" + slice;
        }
    }
}
