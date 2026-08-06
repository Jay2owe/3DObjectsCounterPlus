package sc.fiji.oc3dplus.ui;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import org.junit.Test;
import sc.fiji.oc3d.core.map.ObjectMapBuilder;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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

    @Test
    public void interactiveSnapshotRetainsFullZShapeThroughObjectsMap() {
        ImagePlus live = hyperstackWithZSpanningCube();
        ImagePlus snapshot = null;
        ImagePlus objects = null;
        try {
            snapshot = OC3DPlusDialog.snapshotForInteractiveProcessing(live);
            OC3DPlusResult result = OC3DPlus.count(snapshot, OC3DPlus.builder()
                    .threshold(100)
                    .minSize(1)
                    .channel(2)
                    .frame(2)
                    .build());

            assertNotNull(result);
            objects = ObjectMapBuilder.objectMapInPlace(result.labelImage(),
                    result.statistics(), live.getTitle());
            assertEquals(5, objects.getStackSize());
            assertEquals(3, positiveSliceCount(objects));
            assertEquals(27, positiveVoxelCount(objects));
        } finally {
            close(objects);
            close(snapshot);
            close(live);
        }
    }

    private static ImagePlus hyperstackWithZSpanningCube() {
        ImageStack stack = new ImageStack(5, 5);
        for (int t = 1; t <= 2; t++) {
            for (int z = 0; z < 5; z++) {
                for (int c = 1; c <= 2; c++) {
                    ByteProcessor processor = new ByteProcessor(5, 5);
                    if (c == 2 && t == 2 && z >= 1 && z <= 3) {
                        for (int y = 1; y <= 3; y++) {
                            for (int x = 1; x <= 3; x++) {
                                processor.set(x, y, 200);
                            }
                        }
                    }
                    stack.addSlice(processor);
                }
            }
        }
        ImagePlus image = new ImagePlus("interactive-z-span", stack);
        image.setDimensions(2, 5, 2);
        image.setOpenAsHyperStack(true);
        return image;
    }

    private static int positiveSliceCount(ImagePlus image) {
        int count = 0;
        for (int slice = 1; slice <= image.getStackSize(); slice++) {
            ImageProcessor processor = image.getStack().getProcessor(slice);
            for (int i = 0; i < processor.getPixelCount(); i++) {
                if (processor.getf(i) > 0) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private static int positiveVoxelCount(ImagePlus image) {
        int count = 0;
        for (int slice = 1; slice <= image.getStackSize(); slice++) {
            ImageProcessor processor = image.getStack().getProcessor(slice);
            for (int i = 0; i < processor.getPixelCount(); i++) {
                if (processor.getf(i) > 0) count++;
            }
        }
        return count;
    }

    private static void close(ImagePlus image) {
        if (image == null) return;
        image.changes = false;
        image.close();
        image.flush();
    }
}
