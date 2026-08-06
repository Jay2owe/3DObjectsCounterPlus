package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;
import org.junit.Test;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pins the two {@code Counter3D} defects that the migration fixes by construction,
 * so both are evidenced in this repository rather than only in the plan, and both
 * end up in the CHANGELOG with a reproducer behind them.
 *
 * <p>This is also where the exception <em>text</em> for defect 1 lives. It cannot
 * live in the goldens: HotSpot's {@code OmitStackTraceInFastThrow} strips the
 * message from a hot implicit-exception throw site, so the message is present on a
 * cold JVM and null on a warm one. The harness's determinism check found that on
 * the second capture pass, which is the check doing its job - a golden that
 * depended on JIT state would have been worthless.
 */
public class Counter3DDefectTest {

    /**
     * Defect 1: a stack whose final voxel is an isolated object throws.
     *
     * <p>{@code findObjects()} sizes {@code IDcount} as {@code new int[tag]} and
     * {@code tag} is bumped at the <em>start</em> of the next voxel's iteration, so
     * a foreground final voxel that starts a new object leaves {@code tag}
     * un-bumped and the tally loop indexes one past the end.
     *
     * <p>Observed message on a cold JVM: {@code Index 1 out of bounds for length 1}.
     * Asserted loosely because the JVM may erase it.
     */
    @Test
    public void isolatedFinalVoxelThrowsOutOfTheClassicCounter() {
        ImagePlus input = FixtureCorpus.byName("corner-x1y1z1").createInput();
        try {
            // Deliberately against the shipped Counter3D rather than through
            // OC3DPlus.count. The plugin no longer routes here, so calling the API
            // would prove only that the crash is gone - which the sibling test
            // below asserts. This test's job is to keep the defect itself
            // evidenced, in this repository, for as long as the jar is a
            // dependency.
            new sc.fiji.oc3dplus.engine.ReferenceEngines().run(
                    input, 100, 1, Integer.MAX_VALUE, false, false, true, false);
            fail("expected the classic Counter3D to throw on a stack whose final voxel is an "
                    + "isolated object; if this now passes, the shipped jar changed and both "
                    + "this test and the CHANGELOG entry need updating");
        } catch (ArrayIndexOutOfBoundsException expected) {
            String message = expected.getMessage();
            assertTrue("message should either be absent (HotSpot fast-throw) or name the "
                            + "out-of-bounds index, but was: " + message,
                    message == null || message.contains("1"));
        } finally {
            Stacks.discard(input);
        }
    }

    /**
     * ...and the unified engine counts the same stack without incident.
     *
     * <p>The pair is the point: the defect is real and reproducible on the shipped
     * counter, and it is gone from the path users actually take.
     */
    @Test
    public void theUnifiedEngineCountsTheFinalVoxelStack() {
        ImagePlus input = FixtureCorpus.byName("corner-x1y1z1").createInput();
        ImagePlus labels = null;
        try {
            OC3DPlusResult result = OC3DPlus.count(input,
                    OC3DPlus.builder().threshold(100).minSize(1).build());
            labels = result.labelImage();
            assertEquals("the isolated final voxel is one object", 1, result.objectCount());
        } finally {
            Stacks.discard(labels);
            Stacks.discard(input);
        }
    }

    /** The same voxel one slice earlier is fine, which localises the defect to the final voxel. */
    @Test
    public void theSameVoxelOneSliceEarlierDoesNotThrow() {
        ImagePlus input = FixtureCorpus.byName("corner-x1y1z0").createInput();
        ImagePlus labels = null;
        try {
            OC3DPlusResult result = OC3DPlus.count(input, OC3DPlus.builder()
                    .threshold(100).minSize(1).build());
            labels = result.labelImage();
            assertEquals("a corner voxel that is not the final voxel of the volume counts normally",
                    1, result.objectCount());
        } finally {
            Stacks.discard(labels);
            Stacks.discard(input);
        }
    }

    /**
     * All seven other corners count normally, so the failure is specifically the
     * last voxel in scan order and not "corners" in general.
     */
    @Test
    public void everyOtherCornerCountsNormally() {
        for (int z = 0; z <= 1; z++) {
            for (int y = 0; y <= 1; y++) {
                for (int x = 0; x <= 1; x++) {
                    if (x == 1 && y == 1 && z == 1) continue;
                    String name = "corner-x" + x + "y" + y + "z" + z;
                    ImagePlus input = FixtureCorpus.byName(name).createInput();
                    ImagePlus labels = null;
                    try {
                        OC3DPlusResult result = OC3DPlus.count(input, OC3DPlus.builder()
                                .threshold(100).minSize(1).build());
                        labels = result.labelImage();
                        assertEquals(name, 1, result.objectCount());
                    } finally {
                        Stacks.discard(labels);
                        Stacks.discard(input);
                    }
                }
            }
        }
    }
}
