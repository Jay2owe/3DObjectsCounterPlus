package sc.fiji.oc3dplus;

import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Macro;
import ij.WindowManager;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import org.junit.Test;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assume.assumeFalse;

/** Exercises the actual macro entry point and the Objects-map window it displays. */
public class ObjectsMapEntryPointTest {

    @Test
    public void displayedObjectsMapRetainsTheFullZShape() throws Exception {
        assumeFalse("A display is required to exercise ImagePlus.show()",
                GraphicsEnvironment.isHeadless());

        ImageJ imageJ = null;
        ImagePlus input = zSpanningCube();
        ImagePlus objects = null;
        try {
            if (ij.IJ.getInstance() == null) {
                imageJ = new ImageJ(ImageJ.NO_SHOW);
            }
            final AtomicReference<ImagePlus> output = new AtomicReference<ImagePlus>();
            final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            Thread macroThread = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        WindowManager.setTempCurrentImage(input);
                        Macro.setOptions("threshold=100 min=1 max=Infinity channel=2 frame=2 hide_surfaces "
                                + "hide_centroids hide_centers_of_mass hide_stats hide_summary");
                        assertSame("the plugin must see the synthetic stack as current",
                                input, WindowManager.getCurrentImage());
                        assertNotNull("the plugin must see macro options", Macro.getOptions());

                        new ObjectsCounter3DPlus().run("");

                        ImagePlus displayed = WindowManager.getImage(
                                "Objects map of entry-point-z-span");
                        if (displayed == null) {
                            // ImageJ keeps images produced by a macro in its per-thread
                            // batch/current slot until the surrounding macro completes.
                            displayed = WindowManager.getTempCurrentImage();
                        }
                        output.set(displayed);
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    } finally {
                        Macro.setOptions(null);
                        WindowManager.setTempCurrentImage(null);
                    }
                }
            }, "Run$_objects-map-entry-point-test");
            macroThread.start();
            macroThread.join(30000L);
            if (macroThread.isAlive()) {
                throw new AssertionError("plugin entry-point run did not finish within 30 seconds");
            }
            if (failure.get() != null) {
                throw new AssertionError("plugin entry-point run failed", failure.get());
            }
            objects = output.get();
            assertNotNull("the plugin should display its Objects map; open windows: "
                    + openImageTitles() + "; ImageJ log: " + ij.IJ.getLog(), objects);
            assertEquals(5, objects.getStackSize());
            assertEquals("the cube must remain visible on all three occupied Z slices",
                    3, positiveSliceCount(objects));
            assertEquals(27, positiveVoxelCount(objects));
        } finally {
            Macro.setOptions(null);
            WindowManager.setTempCurrentImage(null);
            close(objects);
            close(input);
            if (imageJ != null) imageJ.dispose();
        }
    }

    private static ImagePlus zSpanningCube() {
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
        ImagePlus hyperstack = new ImagePlus("entry-point-z-span", stack);
        hyperstack.setDimensions(2, 5, 2);
        hyperstack.setOpenAsHyperStack(true);
        return hyperstack;
    }

    private static int positiveSliceCount(ImagePlus image) {
        int count = 0;
        for (int slice = 1; slice <= image.getStackSize(); slice++) {
            ImageProcessor processor = image.getStack().getProcessor(slice);
            boolean found = false;
            for (int i = 0; i < processor.getPixelCount(); i++) {
                if (processor.getf(i) > 0) {
                    found = true;
                    break;
                }
            }
            if (found) count++;
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

    private static String openImageTitles() {
        int[] ids = WindowManager.getIDList();
        if (ids == null || ids.length == 0) return "<none>";
        StringBuilder titles = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            ImagePlus image = WindowManager.getImage(ids[i]);
            if (i > 0) titles.append(", ");
            titles.append(image == null ? "<null>" : image.getTitle());
        }
        return titles.toString();
    }
}
