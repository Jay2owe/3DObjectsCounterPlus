package sc.fiji.oc3dplus.ui;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OC3DPlusDialogDefaultsTest {

    @Test
    public void centerSliceUsesMiddleZPlane() {
        assertEquals(1, OC3DPlusDialogDefaults.centerSlice(null));
        assertEquals(1, OC3DPlusDialogDefaults.centerSlice(stackOf(1)));
        assertEquals(2, OC3DPlusDialogDefaults.centerSlice(stackOf(4)));
        assertEquals(3, OC3DPlusDialogDefaults.centerSlice(stackOf(5)));
    }

    @Test
    public void moveToCenterSliceUpdatesImagePosition() {
        ImagePlus image = stackOf(5);

        OC3DPlusDialogDefaults.moveToCenterSlice(image);

        assertEquals(3, image.getZ());
        assertEquals(3, image.getCurrentSlice());
    }

    @Test
    public void isoDataThresholdComesFromCenterSlice() {
        ImageStack stack = new ImageStack(4, 1);
        stack.addSlice(row(5, 5, 5, 250));
        ByteProcessor center = row(0, 0, 200, 200);
        stack.addSlice(center);
        stack.addSlice(row(10, 10, 10, 10));
        ImagePlus image = new ImagePlus("threshold-source", stack);

        assertEquals(center.getAutoThreshold(),
                OC3DPlusDialogDefaults.isoDataThresholdAtCenterSlice(image, 128));
    }

    @Test
    public void sliderRangeUsesStackIntensityRange() {
        ImageStack stack = new ImageStack(2, 1);
        stack.addSlice(row(5, 10));
        stack.addSlice(row(20, 30));
        ImagePlus image = new ImagePlus("range-source", stack);

        assertEquals(5, OC3DPlusDialogDefaults.sliderMinimum(image));
        assertEquals(30, OC3DPlusDialogDefaults.sliderMaximum(image, 12));
    }

    private static ImagePlus stackOf(int slices) {
        ImageStack stack = new ImageStack(1, 1);
        for (int i = 0; i < slices; i++) {
            stack.addSlice(row(i + 1));
        }
        return new ImagePlus("stack", stack);
    }

    private static ByteProcessor row(int... values) {
        ByteProcessor processor = new ByteProcessor(values.length, 1);
        for (int i = 0; i < values.length; i++) {
            processor.set(i, 0, values[i]);
        }
        return processor;
    }
}
