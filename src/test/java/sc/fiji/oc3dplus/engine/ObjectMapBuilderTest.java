package sc.fiji.oc3dplus.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;
import sc.fiji.oc3d.core.map.ObjectMapBuilder;

import java.awt.Color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ObjectMapBuilderTest {

    @Test
    public void surfaceMapKeepsBoundaryVoxelsOnly() {
        ImagePlus labels = cubeLabelStack(5, 7);

        ImagePlus surface = ObjectMapBuilder.surfaceMap(labels, "source");

        assertNotNull(surface);
        assertEquals(0.0, surface.getStack().getProcessor(3).getf(2, 2), 0.0);
        assertEquals(7.0, surface.getStack().getProcessor(2).getf(2, 2), 0.0);
        assertEquals(26, positiveVoxelCount(surface));
        assertEquals(3, positiveSliceCount(surface));
    }

    @Test
    public void objectMapAddsNumberOverlayWithoutChangingSourceLabelImage() {
        ImagePlus labels = cubeLabelStack(5, 7);
        ResultsTable stats = singleObjectStats(7, 2, 3, 1, 1, 2, 3);

        ImagePlus objects = ObjectMapBuilder.objectMap(labels, stats, "source");

        assertNotNull(objects);
        assertNotSame(labels, objects);
        assertNull(labels.getOverlay());
        assertNotNull(objects.getOverlay());
        assertEquals(1, objects.getOverlay().size());
        assertEquals(Color.RED, objects.getOverlay().get(0).getStrokeColor());
        assertEquals(7.0, labels.getStack().getProcessor(2).getf(2, 3), 0.0);
        assertEquals(7.0, objects.getStack().getProcessor(2).getf(2, 3), 0.0);
        assertEquals(3, positiveSliceCount(objects));
    }

    @Test
    public void objectMapCanDecorateLabelImageInPlaceToAvoidExtraStackCopies() {
        ImagePlus labels = cubeLabelStack(5, 7);
        ResultsTable stats = singleObjectStats(7, 2, 3, 1, 1, 2, 3);

        ImagePlus objects = ObjectMapBuilder.objectMapInPlace(labels, stats, "source");

        assertSame(labels, objects);
        assertNotNull(objects.getOverlay());
        assertEquals("Objects map of source", objects.getTitle());
    }

    @Test
    public void objectMapRendersEveryPositiveLabelAsAVisibleShape() {
        ImagePlus labels = lowAndHighLabelStack();

        ImagePlus objects = ObjectMapBuilder.objectMapInPlace(labels, null, "source");

        for (int slice = 1; slice <= 3; slice++) {
            objects.setSlice(slice);
            int renderedRgb = objects.getBufferedImage().getRGB(2, 2) & 0x00ffffff;
            assertNotEquals("label 1 must not render as black on occupied slice " + slice,
                    0, renderedRgb);
            assertEquals("display changes must preserve the numeric label image",
                    1.0, objects.getProcessor().getf(2, 2), 0.0);
        }
        assertEquals(1000.0, objects.getStack().getProcessor(1).getf(0, 0), 0.0);
    }

    @Test
    public void objectMapAlwaysAddsNumberOverlayAboveConfiguredDisplayLimit() {
        String previousLimit = System.getProperty(ObjectMapBuilder.MAX_OVERLAY_LABELS_PROPERTY);
        System.setProperty(ObjectMapBuilder.MAX_OVERLAY_LABELS_PROPERTY, "2");
        try {
            ImagePlus labels = pointLabelStack(5, 3);
            ResultsTable stats = threeObjectStats();

            ImagePlus objects = ObjectMapBuilder.objectMapInPlace(labels, stats, "source");

            assertNotNull(objects.getOverlay());
            assertEquals(3, objects.getOverlay().size());
            assertNull(objects.getProperty(ObjectMapBuilder.OVERLAY_SKIPPED_PROPERTY));
            assertNull(ObjectMapBuilder.overlaySkippedReason(objects));
        } finally {
            if (previousLimit == null) {
                System.clearProperty(ObjectMapBuilder.MAX_OVERLAY_LABELS_PROPERTY);
            } else {
                System.setProperty(ObjectMapBuilder.MAX_OVERLAY_LABELS_PROPERTY, previousLimit);
            }
        }
    }

    @Test
    public void sparseMapsUseCompactProcessorsWhenLabelsAllowIt() {
        ImagePlus smallLabels = cubeLabelStack(5, 7);
        ImagePlus shortLabels = cubeLabelStackShort(5, 300);

        ImagePlus smallSurface = ObjectMapBuilder.surfaceMap(smallLabels, "small");
        ImagePlus shortSurface = ObjectMapBuilder.surfaceMap(shortLabels, "short");

        assertEquals(8, smallSurface.getBitDepth());
        assertEquals(16, shortSurface.getBitDepth());
    }

    @Test
    public void optionalStackMapsThrowMemoryGuardBeforeAllocationWhenReserveIsUnsafe() {
        String previousReserve = System.getProperty(
                ObjectMapBuilder.OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY);
        System.setProperty(ObjectMapBuilder.OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY,
                Long.toString(Long.MAX_VALUE / 2L));
        try {
            try {
                ObjectMapBuilder.surfaceMap(cubeLabelStack(5, 7), "source");
                fail("expected optional map memory guard");
            } catch (ObjectMapBuilder.OptionalMapMemoryException guarded) {
                assertEquals("Surfaces", guarded.mapName());
                assertTrue(guarded.estimatedBytes() > 0L);
                assertTrue(guarded.reserveBytes() > guarded.estimatedBytes());
            }
        } finally {
            if (previousReserve == null) {
                System.clearProperty(ObjectMapBuilder.OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY);
            } else {
                System.setProperty(ObjectMapBuilder.OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY,
                        previousReserve);
            }
        }
    }

    @Test
    public void pointMapsUseStatisticsCoordinatesAndLabels() {
        ImagePlus labels = cubeLabelStack(5, 7);
        ResultsTable stats = singleObjectStats(7, 2, 3, 1, 1, 2, 3);

        ImagePlus centroids = ObjectMapBuilder.centroidMap(labels, stats, "source");
        ImagePlus centers = ObjectMapBuilder.centerOfMassMap(labels, stats, "source");

        assertNotNull(centroids);
        assertNotNull(centers);
        assertEquals(7.0, centroids.getStack().getProcessor(2).getf(2, 3), 0.0);
        assertEquals(7.0, centers.getStack().getProcessor(4).getf(1, 2), 0.0);
        assertEquals(1, positiveVoxelCount(centroids));
        assertEquals(1, positiveVoxelCount(centers));
        assertEquals(1, positiveSliceCount(centroids));
        assertEquals(1, positiveSliceCount(centers));
        assertNotNull(centroids.getOverlay());
        assertNotNull(centers.getOverlay());
        assertEquals(1, centroids.getOverlay().size());
        assertEquals(1, centers.getOverlay().size());
    }

    private static ResultsTable singleObjectStats(int label,
                                                  double x,
                                                  double y,
                                                  double z,
                                                  double xm,
                                                  double ym,
                                                  double zm) {
        ResultsTable stats = new ResultsTable();
        stats.incrementCounter();
        stats.setValue("Label", 0, label);
        stats.setValue("X", 0, x);
        stats.setValue("Y", 0, y);
        stats.setValue("Z", 0, z);
        stats.setValue("XM", 0, xm);
        stats.setValue("YM", 0, ym);
        stats.setValue("ZM", 0, zm);
        return stats;
    }

    private static ResultsTable threeObjectStats() {
        ResultsTable stats = new ResultsTable();
        for (int i = 0; i < 3; i++) {
            stats.incrementCounter();
            stats.setValue("Label", i, i + 1);
            stats.setValue("X", i, i + 1);
            stats.setValue("Y", i, i + 1);
            stats.setValue("Z", i, 1);
            stats.setValue("XM", i, i + 1);
            stats.setValue("YM", i, i + 1);
            stats.setValue("ZM", i, 1);
        }
        return stats;
    }

    private static ImagePlus cubeLabelStack(int size, int label) {
        ImageStack stack = new ImageStack(size, size);
        for (int z = 0; z < size; z++) {
            ByteProcessor bp = new ByteProcessor(size, size);
            if (z >= 1 && z <= 3) {
                for (int y = 1; y <= 3; y++) {
                    for (int x = 1; x <= 3; x++) {
                        bp.set(x, y, label);
                    }
                }
            }
            stack.addSlice(bp);
        }
        return new ImagePlus("labels", stack);
    }

    private static ImagePlus cubeLabelStackShort(int size, int label) {
        ImageStack stack = new ImageStack(size, size);
        for (int z = 0; z < size; z++) {
            ShortProcessor sp = new ShortProcessor(size, size);
            if (z >= 1 && z <= 3) {
                for (int y = 1; y <= 3; y++) {
                    for (int x = 1; x <= 3; x++) {
                        sp.set(x, y, label);
                    }
                }
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("labels", stack);
    }

    private static ImagePlus lowAndHighLabelStack() {
        ImageStack stack = new ImageStack(5, 5);
        for (int z = 0; z < 3; z++) {
            ShortProcessor sp = new ShortProcessor(5, 5);
            sp.set(2, 2, 1);
            if (z == 0) sp.set(0, 0, 1000);
            stack.addSlice(sp);
        }
        return new ImagePlus("labels", stack);
    }

    private static ImagePlus pointLabelStack(int size, int labels) {
        ImageStack stack = new ImageStack(size, size);
        for (int z = 0; z < size; z++) {
            ShortProcessor sp = new ShortProcessor(size, size);
            if (z == 1) {
                for (int i = 0; i < labels; i++) {
                    sp.set(i + 1, i + 1, i + 1);
                }
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("labels", stack);
    }

    private static int positiveVoxelCount(ImagePlus image) {
        int count = 0;
        ImageStack stack = image.getStack();
        for (int slice = 1; slice <= stack.size(); slice++) {
            for (int i = 0; i < stack.getProcessor(slice).getPixelCount(); i++) {
                if (stack.getProcessor(slice).getf(i) > 0) count++;
            }
        }
        return count;
    }

    private static int positiveSliceCount(ImagePlus image) {
        int count = 0;
        ImageStack stack = image.getStack();
        for (int slice = 1; slice <= stack.size(); slice++) {
            ImageProcessor processor = stack.getProcessor(slice);
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
}
