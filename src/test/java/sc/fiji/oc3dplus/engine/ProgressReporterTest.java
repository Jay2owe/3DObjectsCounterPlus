package sc.fiji.oc3dplus.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ProgressReporterTest {

    @Test
    public void formatsNativeStyleStepLabels() {
        assertEquals("Step 1/3: Finding structures",
                ProgressReporter.formatStepForTests(1, 3, "Finding structures"));
        assertEquals("Step 3/3: Complete",
                ProgressReporter.formatStepForTests(99, 3, "Complete"));
        assertEquals("Step 1/1: Working",
                ProgressReporter.formatStepForTests(0, 0, " "));
    }

    @Test
    public void progressUsesStepBoundaries() {
        assertEquals(0.0, ProgressReporter.progressAtStepStartForTests(1, 4), 0.0);
        assertEquals(0.25, ProgressReporter.progressAtStepEndForTests(1, 4), 0.0);
        assertEquals(0.75, ProgressReporter.progressAtStepStartForTests(4, 4), 0.0);
        assertEquals(1.0, ProgressReporter.progressAtStepEndForTests(4, 4), 0.0);
    }
}
