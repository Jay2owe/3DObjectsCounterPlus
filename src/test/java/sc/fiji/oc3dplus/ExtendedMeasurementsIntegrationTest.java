package sc.fiji.oc3dplus;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import org.junit.Test;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExtendedMeasurementsIntegrationTest {

    @Test
    public void extendedColumnsAreAbsentByDefaultAndConditionalWhenSelected() {
        OC3DPlusResult legacy = OC3DPlus.count(cube(), OC3DPlus.builder()
                .threshold(100).minSize(1).build());
        assertEquals(ResultsTable.COLUMN_NOT_FOUND,
                legacy.statistics().getColumnIndex("Morph_FractalDim_XY"));
        assertEquals(ResultsTable.COLUMN_NOT_FOUND,
                legacy.statistics().getColumnIndex("Morph_RI"));

        OC3DPlusResult extended = OC3DPlus.count(cube(), OC3DPlus.builder()
                .threshold(100).minSize(1)
                .measureFractalXY(true)
                .measureCompositeIndices(true)
                .build());
        assertTrue(extended.statistics().getColumnIndex("Morph_FractalDim_XY") >= 0);
        assertTrue(extended.statistics().getColumnIndex("Morph_LacunarityMean_XY") >= 0);
        assertTrue(extended.statistics().getColumnIndex("Morph_RI") >= 0);
        assertTrue(extended.statistics().getColumnIndex("Morph_VSD") >= 0);
        assertTrue(Double.isFinite(extended.statistics().getValue("Morph_FractalDim_XY", 0)));
        assertTrue(Double.isFinite(extended.statistics().getValue("Morph_RI", 0)));
    }

    @Test
    public void extendedPredicateAutomaticallyEnablesItsMeasurementFamily() {
        OC3DPlusResult result = OC3DPlus.count(cube(), OC3DPlus.builder()
                .threshold(100).minSize(1)
                .addFilter("ri", ">=", 1.0)
                .build());

        assertEquals(1, result.objectCount());
        assertTrue(result.statistics().getColumnIndex("Morph_RI") >= 0);
        assertTrue(Double.isFinite(result.statistics().getValue("Morph_RI", 0)));
    }

    @Test
    public void arborizationIsExplicitlyUnavailableWithoutParityCertifiedBackend() {
        OC3DPlusResult result = OC3DPlus.count(cube(), OC3DPlus.builder()
                .threshold(100).minSize(1)
                .measureArborization(true)
                .build());

        assertTrue(result.statistics().getColumnIndex("Morph_SkeletonBranches") >= 0);
        assertTrue(result.statistics().getColumnIndex("Morph_ArborizationBackend") >= 0);
        String backend = result.statistics().getStringValue("Morph_ArborizationBackend", 0);
        assertTrue("Unexpected backend: " + backend,
                "Unavailable".equals(backend) || backend.contains("Fiji Skeletonize3D"));
    }

    private static ImagePlus cube() {
        int width = 16;
        int height = 16;
        int depth = 8;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            for (int y = 2; y < 14; y++) {
                for (int x = 2; x < 14; x++) {
                    processor.set(x, y, 200);
                }
            }
            stack.addSlice(processor);
        }
        return new ImagePlus("cube", stack);
    }
}
