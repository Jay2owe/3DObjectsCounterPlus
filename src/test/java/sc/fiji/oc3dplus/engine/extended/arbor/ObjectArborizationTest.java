package sc.fiji.oc3dplus.engine.extended.arbor;

import ij.measure.Calibration;
import org.junit.Test;
import sc.fiji.oc3dplus.engine.extended.ObjectMask3D;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ObjectArborizationTest {

    @Test
    public void recognisesOnlyCalibrationConvertibleToMicrometres() {
        Calibration micrometres = new Calibration();
        micrometres.setUnit("um");
        assertTrue(ObjectArborization.hasPhysicalShollCalibration(micrometres));

        Calibration pixels = new Calibration();
        pixels.setUnit("pixel");
        assertFalse(ObjectArborization.hasPhysicalShollCalibration(pixels));
        assertFalse(ObjectArborization.hasPhysicalShollCalibration(null));
    }

    @Test
    public void straightLineHasOneBranchTwoEndpointsAndCalibratedShollProfile() {
        boolean[] line = lineMask(21);

        ObjectArborization.Result result = ObjectArborization.compute(
                line, 21, 1, 1, calibration("um", 1.0, 1.0, 1.0));

        if (!fijiAvailableOrFailClosed(result)) return;
        assertEquals(1, result.skeletonBranches);
        assertEquals(0, result.skeletonJunctions);
        assertEquals(2, result.skeletonEndpoints);
        assertEquals(21, result.skeletonVoxels);
        assertEquals(5.0, result.shollCriticalRadiusUm, 0.0);
        assertEquals(2.0, result.shollCriticalIntersections, 0.0);
        assertEquals(2.0, result.shollPrimaryBranches, 0.0);
        assertEquals(1.0, result.shollSchoenenIndex, 0.0);
        assertEquals(2, result.shollProfile.size());
        assertTrue(result.hasShollMeasurements());
        assertFalse(result.skeletonBackend.isEmpty());
    }

    @Test
    public void nanometreCalibrationIsConvertedToMicrometres() {
        ObjectArborization.Result result = ObjectArborization.compute(
                lineMask(21), 21, 1, 1,
                calibration("nm", 1000.0, 1000.0, 1000.0));

        if (!fijiAvailableOrFailClosed(result)) return;
        assertEquals(5.0, result.shollCriticalRadiusUm, 0.0);
        assertEquals(2.0, result.shollCriticalIntersections, 0.0);
    }

    @Test
    public void acceptsSharedObjectMaskRepresentation() {
        byte[] line = new byte[21];
        for (int i = 0; i < line.length; i++) {
            line[i] = 1;
        }

        ObjectArborization.Result result = ObjectArborization.compute(
                new ObjectMask3D(line, 21, 1, 1),
                calibration("um", 1.0, 1.0, 1.0));

        if (!fijiAvailableOrFailClosed(result)) return;
        assertEquals(1, result.skeletonBranches);
        assertEquals(2, result.skeletonEndpoints);
        assertEquals(5.0, result.shollCriticalRadiusUm, 0.0);
    }

    @Test
    public void unknownCalibrationLeavesGraphCountsValidAndShollUnavailable() {
        ObjectArborization.Result result = ObjectArborization.compute(
                lineMask(21), 21, 1, 1,
                calibration("pixel", 1.0, 1.0, 1.0));

        if (!fijiAvailableOrFailClosed(result)) return;
        assertEquals(1, result.skeletonBranches);
        assertEquals(0, result.skeletonJunctions);
        assertEquals(2, result.skeletonEndpoints);
        assertEquals(21, result.skeletonVoxels);
        assertTrue(Double.isNaN(result.shollCriticalRadiusUm));
        assertTrue(Double.isNaN(result.shollCriticalIntersections));
        assertTrue(Double.isNaN(result.shollSchoenenIndex));
        assertTrue(Double.isNaN(result.shollPrimaryBranches));
        assertTrue(result.shollProfile.isEmpty());
        assertFalse(result.hasShollMeasurements());
    }

    @Test
    public void emptyAndDisconnectedMasksFailClosed() {
        ObjectArborization.Result empty = ObjectArborization.compute(
                new boolean[5], 5, 1, 1, null);
        assertFalse(empty.valid);
        assertEquals(-1, empty.skeletonBranches);
        assertTrue(empty.unavailableReason.contains("no foreground"));

        boolean[] disconnected = new boolean[5];
        disconnected[0] = true;
        disconnected[4] = true;
        ObjectArborization.Result separate = ObjectArborization.compute(
                disconnected, 5, 1, 1, null);
        assertFalse(separate.valid);
        assertEquals(-1, separate.skeletonVoxels);
        assertTrue(separate.unavailableReason.contains("exactly one"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMaskLengthMismatch() {
        ObjectArborization.compute(new boolean[3], 2, 2, 1, null);
    }

    @Test
    public void installedFijiSkeletonize3DIsThePreferredBackend() {
        try {
            Class.forName("sc.fiji.skeletonize3D.Skeletonize3D_");
        } catch (ClassNotFoundException notOnTestClasspath) {
            return;
        }
        ObjectArborization.Result result = ObjectArborization.compute(
                lineMask(21), 21, 1, 1,
                calibration("um", 1.0, 1.0, 1.0));
        assertTrue(result.unavailableReason, result.valid);
        assertEquals("Fiji Skeletonize3D", result.skeletonBackend);
        assertEquals(1, result.skeletonBranches);
        assertEquals(2, result.skeletonEndpoints);
    }

    private static boolean[] lineMask(int length) {
        boolean[] line = new boolean[length];
        for (int i = 0; i < line.length; i++) {
            line[i] = true;
        }
        return line;
    }

    private static boolean fijiAvailableOrFailClosed(ObjectArborization.Result result) {
        if (result.valid) return true;
        assertEquals("Unavailable", result.skeletonBackend);
        assertTrue(result.unavailableReason.contains("parity"));
        assertEquals(-1, result.skeletonBranches);
        return false;
    }

    private static Calibration calibration(String unit,
                                           double pixelWidth,
                                           double pixelHeight,
                                           double pixelDepth) {
        Calibration calibration = new Calibration();
        calibration.setUnit(unit);
        calibration.pixelWidth = pixelWidth;
        calibration.pixelHeight = pixelHeight;
        calibration.pixelDepth = pixelDepth;
        return calibration;
    }
}
