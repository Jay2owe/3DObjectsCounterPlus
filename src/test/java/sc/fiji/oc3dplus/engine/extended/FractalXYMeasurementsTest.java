package sc.fiji.oc3dplus.engine.extended;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FractalXYMeasurementsTest {

    @Test
    public void filledProjectedSquareHasDimensionNearTwo() {
        byte[] voxels = new byte[32 * 32 * 2];
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                int z = x < 16 ? 0 : 1;
                voxels[index(32, 32, x, y, z)] = 1;
            }
        }

        FractalXYMeasurements.Result result =
                FractalXYMeasurements.compute(
                        new ObjectMask3D(voxels, 32, 32, 2));

        assertTrue(result.isValid());
        assertTrue(result.isReliable());
        assertEquals(2.0, result.fractalDimension(), 0.05);
        assertTrue(result.rSquared() > 0.99);
        assertTrue(Double.isFinite(result.lacunarityMean()));
        assertTrue(Double.isFinite(result.lacunaritySpread()));
        assertEquals(6, result.regressionScaleCount());
    }

    @Test
    public void diagonalProjectionHasDimensionNearOne() {
        byte[] voxels = new byte[32 * 32 * 3];
        for (int i = 0; i < 32; i++) {
            voxels[index(32, 32, i, i, i % 3)] = 1;
        }

        FractalXYMeasurements.Result result =
                FractalXYMeasurements.compute(
                        new ObjectMask3D(voxels, 32, 32, 3));

        assertTrue(result.isValid());
        assertEquals(1.0, result.fractalDimension(), 0.15);
    }

    @Test
    public void differentDepthAssignmentsWithSameProjectionGiveSameResult() {
        byte[] oneSlice = diagonalMask(32, 32, 2, false);
        byte[] alternatingSlices = diagonalMask(32, 32, 2, true);

        FractalXYMeasurements.Result first =
                FractalXYMeasurements.compute(
                        new ObjectMask3D(oneSlice, 32, 32, 2));
        FractalXYMeasurements.Result second =
                FractalXYMeasurements.compute(
                        new ObjectMask3D(alternatingSlices, 32, 32, 2));

        assertEquals(first.fractalDimension(), second.fractalDimension(), 0.0);
        assertEquals(first.rSquared(), second.rSquared(), 0.0);
        assertEquals(first.lacunarityMean(), second.lacunarityMean(), 0.0);
        assertEquals(first.lacunaritySpread(), second.lacunaritySpread(), 0.0);
    }

    @Test
    public void projectedBoundsSmallerThanEightAreInvalid() {
        byte[] voxels = new byte[8 * 8];
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 8; x++) {
                voxels[y * 8 + x] = 1;
            }
        }

        FractalXYMeasurements.Result result =
                FractalXYMeasurements.compute(
                        new ObjectMask3D(voxels, 8, 8, 1));

        assertFalse(result.isValid());
        assertFalse(result.isReliable());
        assertTrue(Double.isNaN(result.fractalDimension()));
    }

    @Test
    public void fewerThanThirtyTwoProjectedPixelsAreInvalid() {
        byte[] voxels = new byte[32 * 32];
        for (int i = 0; i < 31; i++) {
            voxels[i * 32 + i] = 1;
        }

        FractalXYMeasurements.Result result =
                FractalXYMeasurements.compute(
                        new ObjectMask3D(voxels, 32, 32, 1));

        assertFalse(result.isValid());
        assertTrue(Double.isNaN(result.rSquared()));
    }

    @Test
    public void poorRegressionKeepsR2ButSuppressesReportedMeasurements() {
        byte[] voxels = new byte[32 * 32];
        Random random = new Random(3L);
        for (int i = 0; i < 64; i++) {
            voxels[random.nextInt(voxels.length)] = 1;
        }
        voxels[0] = 1;
        voxels[voxels.length - 1] = 1;

        FractalXYMeasurements.Result result =
                FractalXYMeasurements.compute(
                        new ObjectMask3D(voxels, 32, 32, 1));

        assertFalse(result.isValid());
        assertFalse(result.isReliable());
        assertTrue(Double.isFinite(result.rSquared()));
        assertTrue(result.rSquared() < 0.9);
        assertTrue(Double.isNaN(result.fractalDimension()));
        assertTrue(Double.isNaN(result.lacunarityMean()));
        assertTrue(Double.isNaN(result.lacunaritySpread()));
        assertEquals(6, result.regressionScaleCount());
    }

    private static byte[] diagonalMask(int width,
                                       int height,
                                       int depth,
                                       boolean alternateDepth) {
        byte[] voxels = new byte[width * height * depth];
        for (int i = 0; i < Math.min(width, height); i++) {
            int z = alternateDepth ? i % depth : 0;
            voxels[index(width, height, i, i, z)] = 1;
        }
        return voxels;
    }

    private static int index(int width,
                             int height,
                             int x,
                             int y,
                             int z) {
        return z * width * height + y * width + x;
    }
}
