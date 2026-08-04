package sc.fiji.oc3dplus.batch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScoreFeatureCatalogTest {

    @Test
    public void includesPhysicalMorphologyWithStableUnits() {
        assertEquals("Volume",
                ScoreFeatureCatalog.canonicalFeature("Volume (nm^3)"));
        assertEquals("Surface",
                ScoreFeatureCatalog.canonicalFeature("Surface (mm^2)"));
        assertEquals(3, ScoreFeatureCatalog.physicalDimensionPower("Volume"));
        assertEquals(2, ScoreFeatureCatalog.physicalDimensionPower("Surface"));
        assertEquals(1,
                ScoreFeatureCatalog.physicalDimensionPower("Morph_Feret3D_um"));
        assertEquals("um^3", ScoreFeatureCatalog.scoringUnit("Volume"));
    }

    @Test
    public void treatsFractalR2AsQualityMetadataRatherThanScore() {
        assertFalse(ScoreFeatureCatalog.isScoreable("Morph_FractalDim_XY_R2"));
        assertTrue(ScoreFeatureCatalog.isScoreable("Morph_FractalDim_XY"));
    }
}
