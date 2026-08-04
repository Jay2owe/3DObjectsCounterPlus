package sc.fiji.oc3dplus.engine.extended;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CompositeShapeMeasurementsTest {

    @Test
    public void computesAllFiveCompositeFormulas() {
        CompositeShapeMeasurements.Result result =
                CompositeShapeMeasurements.compute(
                        0.5,
                        4.0,
                        1.0,
                        0.2,
                        2.0,
                        4.0,
                        10.0,
                        100.0);

        assertEquals(2.0, result.ramificationIndex(), 0.0);
        assertEquals(0.25, result.surfaceRoughnessIndex(), 0.0);
        assertEquals(0.8, result.processBurden(), 1.0e-12);
        assertEquals(0.25, result.morphologicalPolarity(), 0.0);
        assertEquals(1.0, result.volumeSpanDiscrepancy(), 0.0);
    }

    @Test
    public void exactZeroPolarityDenominatorIsUndefined() {
        CompositeShapeMeasurements.Result result =
                CompositeShapeMeasurements.compute(
                        1.0,
                        1.0,
                        0.0,
                        1.0,
                        1.0,
                        1.0,
                        1.0,
                        1.0);

        assertTrue(Double.isNaN(result.morphologicalPolarity()));
    }

    @Test
    public void nearSpherePolarityDenominatorIsUndefined() {
        CompositeShapeMeasurements.Result result =
                CompositeShapeMeasurements.compute(
                        1.0,
                        1.0,
                        0.0,
                        1.0,
                        1.0 + 1.0e-12,
                        1.0 + 2.0e-12,
                        1.0,
                        1.0);

        assertTrue(Double.isNaN(result.morphologicalPolarity()));
    }

    @Test
    public void undefinedDivisionsAndLogarithmReturnNaN() {
        CompositeShapeMeasurements.Result result =
                CompositeShapeMeasurements.compute(
                        0.0,
                        0.0,
                        1.0,
                        Double.NaN,
                        Double.NaN,
                        2.0,
                        0.0,
                        -1.0);

        assertTrue(Double.isNaN(result.ramificationIndex()));
        assertTrue(Double.isNaN(result.surfaceRoughnessIndex()));
        assertTrue(Double.isNaN(result.processBurden()));
        assertTrue(Double.isNaN(result.morphologicalPolarity()));
        assertTrue(Double.isNaN(result.volumeSpanDiscrepancy()));
    }

    @Test
    public void nonPositivePhysicalDenominatorsAreUndefined() {
        CompositeShapeMeasurements.Result result =
                CompositeShapeMeasurements.compute(
                        -0.5,
                        -2.0,
                        1.0,
                        0.5,
                        2.0,
                        2.0,
                        1.0,
                        1.0);

        assertTrue(Double.isNaN(result.ramificationIndex()));
        assertTrue(Double.isNaN(result.surfaceRoughnessIndex()));
    }

    @Test
    public void outOfDomainCompositePrerequisitesAreUndefined() {
        CompositeShapeMeasurements.Result result =
                CompositeShapeMeasurements.compute(
                        0.5,
                        2.0,
                        -1.0,
                        1.1,
                        0.5,
                        0.75,
                        2.0,
                        2.0);

        assertTrue(Double.isNaN(result.surfaceRoughnessIndex()));
        assertTrue(Double.isNaN(result.processBurden()));
        assertTrue(Double.isNaN(result.morphologicalPolarity()));
    }

    @Test
    public void objectsBelowFixedVoxelReliabilityFloorAreUnavailable() {
        CompositeShapeMeasurements.Result result =
                CompositeShapeMeasurements.compute(
                        0.5,
                        2.0,
                        1.0,
                        0.5,
                        2.0,
                        2.0,
                        2.0,
                        2.0,
                        CompositeShapeMeasurements.MIN_RELIABLE_OBJECT_VOXELS - 1);

        assertTrue(Double.isNaN(result.ramificationIndex()));
        assertTrue(Double.isNaN(result.surfaceRoughnessIndex()));
        assertTrue(Double.isNaN(result.processBurden()));
        assertTrue(Double.isNaN(result.morphologicalPolarity()));
        assertTrue(Double.isNaN(result.volumeSpanDiscrepancy()));
    }
}
