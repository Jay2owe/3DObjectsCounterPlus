package sc.fiji.oc3dplus.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class ImageOpsTest {

    @Test
    public void thresholdRetainedIntensityCopyZerosOnlyRejectedVoxels() {
        FloatProcessor processor = new FloatProcessor(4, 1);
        processor.setf(0, 99f);
        processor.setf(1, 100f);
        processor.setf(2, 150f);
        processor.setf(3, Float.NaN);
        ImageStack stack = new ImageStack(4, 1);
        stack.addSlice(processor);
        ImagePlus source = new ImagePlus("source", stack);

        ImagePlus thresholded = ImageOps.thresholdRetainedIntensityCopy(source, 100);

        assertEquals(0f, thresholded.getStack().getProcessor(1).getf(0), 0f);
        assertEquals(100f, thresholded.getStack().getProcessor(1).getf(1), 0f);
        assertEquals(150f, thresholded.getStack().getProcessor(1).getf(2), 0f);
        assertEquals(0f, thresholded.getStack().getProcessor(1).getf(3), 0f);
        assertEquals(99f, source.getStack().getProcessor(1).getf(0), 0f);
    }

    @Test
    public void thresholdBinaryMaskCopyUsesByteStackAndPreservesSource() {
        ImageStack stack = new ImageStack(4, 1);
        FloatProcessor first = new FloatProcessor(4, 1);
        first.setf(0, 99f);
        first.setf(1, 100f);
        first.setf(2, 150f);
        first.setf(3, Float.NaN);
        FloatProcessor second = new FloatProcessor(4, 1);
        second.setf(0, 200f);
        second.setf(1, 1f);
        second.setf(2, 100f);
        second.setf(3, Float.POSITIVE_INFINITY);
        stack.addSlice(first);
        stack.addSlice(second);
        ImagePlus source = new ImagePlus("source", stack);

        ImagePlus mask = ImageOps.thresholdBinaryMaskCopy(source, 100);

        assertEquals(8, mask.getBitDepth());
        assertEquals(2, mask.getStackSize());
        assertEquals(0f, mask.getStack().getProcessor(1).getf(0), 0f);
        assertEquals(255f, mask.getStack().getProcessor(1).getf(1), 0f);
        assertEquals(255f, mask.getStack().getProcessor(1).getf(2), 0f);
        assertEquals(0f, mask.getStack().getProcessor(1).getf(3), 0f);
        assertEquals(255f, mask.getStack().getProcessor(2).getf(0), 0f);
        assertEquals(0f, mask.getStack().getProcessor(2).getf(1), 0f);
        assertEquals(255f, mask.getStack().getProcessor(2).getf(2), 0f);
        assertEquals(0f, mask.getStack().getProcessor(2).getf(3), 0f);
        assertEquals(99f, source.getStack().getProcessor(1).getf(0), 0f);
    }

    @Test
    public void thresholdRetainedCurrentPlaneCopyUsesDisplayedSliceOnly() {
        ImageStack stack = new ImageStack(2, 1);
        FloatProcessor first = new FloatProcessor(2, 1);
        first.setf(0, 50f);
        first.setf(1, 150f);
        FloatProcessor second = new FloatProcessor(2, 1);
        second.setf(0, 200f);
        second.setf(1, Float.NaN);
        stack.addSlice(first);
        stack.addSlice(second);
        ImagePlus source = new ImagePlus("source", stack);
        source.setSlice(2);

        ImagePlus thresholded = ImageOps.thresholdRetainedCurrentPlaneCopy(source, 100);

        assertEquals(1, thresholded.getStackSize());
        assertEquals(200f, thresholded.getProcessor().getf(0), 0f);
        assertEquals(0f, thresholded.getProcessor().getf(1), 0f);
        assertTrue(Float.isNaN(source.getStack().getProcessor(2).getf(1)));
    }

    @Test
    public void processingSnapshotCopiesFullStackMetadataAndPixelsIndependently() {
        ImageStack stack = new ImageStack(2, 2);
        for (int i = 1; i <= 12; i++) {
            FloatProcessor processor = new FloatProcessor(2, 2);
            processor.setf(0, i);
            processor.setf(1, i + 100);
            stack.addSlice("slice-" + i, processor);
        }
        ImagePlus source = new ImagePlus("live-title", stack);
        source.setDimensions(2, 3, 2);
        source.setOpenAsHyperStack(true);
        Calibration calibration = new Calibration();
        calibration.setUnit("unit");
        calibration.pixelWidth = 0.5;
        calibration.pixelHeight = 0.25;
        calibration.pixelDepth = 2.0;
        source.setCalibration(calibration);

        ImagePlus snapshot = ImageOps.processingSnapshot(source);

        assertNotSame(source, snapshot);
        assertEquals("live-title", snapshot.getTitle());
        assertEquals(2, snapshot.getNChannels());
        assertEquals(3, snapshot.getNSlices());
        assertEquals(2, snapshot.getNFrames());
        assertTrue(snapshot.isHyperStack());
        assertNotSame(source.getCalibration(), snapshot.getCalibration());
        assertEquals("unit", snapshot.getCalibration().getUnit());
        assertEquals(0.5, snapshot.getCalibration().pixelWidth, 0.0);
        assertEquals(0.25, snapshot.getCalibration().pixelHeight, 0.0);
        assertEquals(2.0, snapshot.getCalibration().pixelDepth, 0.0);

        source.getStack().getProcessor(1).setf(0, 999f);
        snapshot.getStack().getProcessor(2).setf(1, 777f);

        assertEquals(1f, snapshot.getStack().getProcessor(1).getf(0), 0f);
        assertEquals(102f, source.getStack().getProcessor(2).getf(1), 0f);
    }
}
