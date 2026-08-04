package sc.fiji.oc3dplus.ui;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

public class OC3DPlusDialogSnapshotTest {

    @Test
    public void interactiveProcessingSnapshotDoesNotShareLiveStackPixels() {
        ImageStack stack = new ImageStack(2, 1);
        ByteProcessor first = new ByteProcessor(2, 1);
        first.set(0, 10);
        ByteProcessor second = new ByteProcessor(2, 1);
        second.set(0, 20);
        stack.addSlice(first);
        stack.addSlice(second);
        ImagePlus live = new ImagePlus("live-window", stack);

        ImagePlus snapshot = OC3DPlusDialog.snapshotForInteractiveProcessing(live);

        assertNotSame(live, snapshot);
        assertEquals("live-window", snapshot.getTitle());
        live.getStack().getProcessor(1).set(0, 99);
        snapshot.getStack().getProcessor(2).set(0, 77);
        assertEquals(10, snapshot.getStack().getProcessor(1).get(0));
        assertEquals(20, live.getStack().getProcessor(2).get(0));
    }
}
