package sc.fiji.oc3dplus.engine.extended.arbor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class InternalSkeletonizerTest {

    @Test
    public void preservesAlreadyThinLineIncludingEndpoints() {
        boolean[] line = new boolean[9];
        for (int i = 0; i < line.length; i++) {
            line[i] = true;
        }

        boolean[] skeleton = InternalSkeletonizer.thin(line, 9, 1, 1);

        assertEquals(9, BinaryMaskOps.countTrue(skeleton));
        assertTrue(skeleton[0]);
        assertTrue(skeleton[8]);
        assertEquals(1, BinaryMaskOps.componentCount26(skeleton, 9, 1, 1, 2));
    }

    @Test
    public void solidBlockThinsToConnectedNonEmptySubset() {
        int side = 5;
        boolean[] block = new boolean[side * side * side];
        for (int z = 1; z < 4; z++) {
            for (int y = 1; y < 4; y++) {
                for (int x = 1; x < 4; x++) {
                    block[BinaryMaskOps.index(x, y, z, side, side)] = true;
                }
            }
        }

        boolean[] skeleton = InternalSkeletonizer.thin(block, side, side, side);
        int skeletonCount = BinaryMaskOps.countTrue(skeleton);

        assertTrue(skeletonCount > 0);
        assertTrue(skeletonCount < 27);
        assertTrue(BinaryMaskOps.isSubset(skeleton, block));
        assertEquals(1, BinaryMaskOps.componentCount26(skeleton, side, side, side, 2));
    }
}
