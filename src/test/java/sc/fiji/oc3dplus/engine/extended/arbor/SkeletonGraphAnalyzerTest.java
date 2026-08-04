package sc.fiji.oc3dplus.engine.extended.arbor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SkeletonGraphAnalyzerTest {

    @Test
    public void isolatedSkeletonVoxelMatchesAnalyzeSkeletonEndpointSemantics() {
        boolean[] point = {true};

        SkeletonGraphAnalyzer.Summary summary =
                SkeletonGraphAnalyzer.analyze(point, 1, 1, 1);

        assertTrue(summary.unavailableReason, summary.available);
        assertEquals(0, summary.branches);
        assertEquals(0, summary.junctions);
        assertEquals(1, summary.endpoints);
        assertEquals(1, summary.skeletonVoxels);
    }

    @Test
    public void straightSkeletonHasExpectedGraphCounts() {
        boolean[] line = new boolean[7];
        for (int x = 0; x < line.length; x++) {
            line[x] = true;
        }

        SkeletonGraphAnalyzer.Summary summary =
                SkeletonGraphAnalyzer.analyze(line, 7, 1, 1);

        assertTrue(summary.unavailableReason, summary.available);
        assertEquals(1, summary.branches);
        assertEquals(0, summary.junctions);
        assertEquals(2, summary.endpoints);
        assertEquals(7, summary.skeletonVoxels);
    }

    @Test
    public void threeDimensionalThreeArmSkeletonCollapsesJunctionCluster() {
        int side = 9;
        boolean[] mask = new boolean[side * side * side];
        int center = 4;
        set(mask, side, center, center, center);
        for (int offset = 1; offset <= 4; offset++) {
            set(mask, side, center + offset, center, center);
            set(mask, side, center, center + offset, center);
            set(mask, side, center, center, center + offset);
        }

        SkeletonGraphAnalyzer.Summary summary =
                SkeletonGraphAnalyzer.analyze(mask, side, side, side);

        assertTrue(summary.unavailableReason, summary.available);
        assertEquals(3, summary.branches);
        assertEquals(1, summary.junctions);
        assertEquals(3, summary.endpoints);
        assertEquals(13, summary.skeletonVoxels);
    }

    private static void set(boolean[] mask, int side, int x, int y, int z) {
        mask[BinaryMaskOps.index(x, y, z, side, side)] = true;
    }
}
