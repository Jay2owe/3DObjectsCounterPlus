package sc.fiji.oc3dplus.engine;

import ij.measure.ResultsTable;
import org.junit.Test;
import sc.fiji.oc3dplus.api.OC3DPlusResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SummaryReporterTest {

    @Test
    public void formatsNativeStyleSummaryLine() {
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.incrementCounter();
        OC3DPlusResult result = new OC3DPlusResult(stats, null, null, null);

        assertEquals("stack.tif: 2 objects detected (Size filter set to 10-500 voxels, "
                        + "threshold set to: 128).",
                SummaryReporter.format("stack.tif", result, 10, 500, 128));
    }

    @Test
    public void formatsUnboundedMaxAsInfinity() {
        assertEquals("stack.tif: 0 objects detected (Size filter set to 0-Infinity voxels, "
                        + "threshold set to: 1).",
                SummaryReporter.format("stack.tif", null, 0, Integer.MAX_VALUE, 1));
    }

    @Test
    public void formatsSummaryLineWithRedirectContextOnlyInLogText() {
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        OC3DPlusResult result = new OC3DPlusResult(stats, null, null, null);

        assertEquals("segmented.tif redirect to raw.tif: 1 objects detected "
                        + "(Size filter set to 1-100 voxels, threshold set to: 42).",
                SummaryReporter.format("segmented.tif", "raw.tif", result, 1, 100, 42));
    }

    @Test
    public void blankRedirectKeepsSummarySubjectUnchanged() {
        assertEquals("stack.tif: 0 objects detected (Size filter set to 0-Infinity voxels, "
                        + "threshold set to: 1).",
                SummaryReporter.format("stack.tif", "", null, 0, Integer.MAX_VALUE, 1));
    }

    @Test
    public void includesMorphologyMeansWhenColumnsAreAvailable() {
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.setValue("Nb of obj. voxels", 0, 10);
        stats.setValue("Morph_Sphericity", 0, 0.5);
        stats.setValue("Morph_Compactness", 0, 0.4);
        stats.setValue("Morph_Elongation", 0, 1.25);
        stats.setValue("Morph_Feret3D_um", 0, 6.0);
        OC3DPlusResult result = new OC3DPlusResult(stats, null, null, null);

        String summary = SummaryReporter.format("stack.tif", result, 1, 100, 128);

        assertTrue(summary.contains("Morphology means:"));
        assertTrue(summary.contains("Size=10"));
        assertTrue(summary.contains("Sphericity=0.5"));
        assertTrue(summary.contains("Compactness=0.4"));
        assertTrue(summary.contains("Elongation=1.25"));
        assertTrue(summary.contains("Max Feret diameter=6"));
    }
}
