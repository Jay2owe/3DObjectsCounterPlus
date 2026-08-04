package sc.fiji.oc3dplus.engine.extended;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ObjectMask3DTest {

    @Test
    public void copiesInputAndReportsTightBounds() {
        byte[] voxels = new byte[5 * 4 * 3];
        set(voxels, 5, 4, 1, 1, 0);
        set(voxels, 5, 4, 3, 2, 2);

        ObjectMask3D mask = new ObjectMask3D(voxels, 5, 4, 3);
        voxels[index(5, 4, 1, 1, 0)] = 0;

        assertEquals(2, mask.voxelCount());
        assertTrue(mask.contains(1, 1, 0));
        assertFalse(mask.contains(2, 1, 0));
        assertEquals(1, mask.minX());
        assertEquals(3, mask.maxX());
        assertEquals(1, mask.minY());
        assertEquals(2, mask.maxY());
        assertEquals(0, mask.minZ());
        assertEquals(2, mask.maxZ());
        assertEquals(3, mask.boundsWidth());
        assertEquals(2, mask.boundsHeight());
        assertEquals(3, mask.boundsDepth());
    }

    @Test
    public void emptyMaskHasZeroSizedBounds() {
        ObjectMask3D mask = new ObjectMask3D(new byte[24], 4, 3, 2);

        assertTrue(mask.isEmpty());
        assertEquals(0, mask.boundsWidth());
        assertEquals(0, mask.boundsHeight());
        assertEquals(0, mask.boundsDepth());
        assertEquals(-1, mask.minX());
        assertEquals(-1, mask.maxX());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMismatchedArrayLength() {
        new ObjectMask3D(new byte[7], 2, 2, 2);
    }

    private static void set(byte[] voxels,
                            int width,
                            int height,
                            int x,
                            int y,
                            int z) {
        voxels[index(width, height, x, y, z)] = 1;
    }

    private static int index(int width,
                             int height,
                             int x,
                             int y,
                             int z) {
        return z * width * height + y * width + x;
    }
}
