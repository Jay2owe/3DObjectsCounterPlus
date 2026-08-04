package sc.fiji.oc3dplus.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LabelFeatureAccumulatorTest {

    @Test
    public void byteLabelsWithoutIntensityComputeGeometryAndSurfaceValues() {
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 2.0;
        calibration.pixelHeight = 3.0;
        calibration.pixelDepth = 4.0;
        calibration.setUnit("um");

        LabelFeatureAccumulator.Result result = LabelFeatureAccumulator.scan(
                solidByteCube(5, 7), null, calibration);

        LabelFeatureAccumulator.FeatureValues values = result.valuesForLabel(7);
        assertNotNull(values);
        assertEquals(Arrays.asList(Integer.valueOf(7)), result.labelsSorted());
        assertEquals(27, values.voxelCount);
        assertEquals(27.0 * 2.0 * 3.0 * 4.0, values.calibratedVolume, 0.0);
        assertEquals(26, values.surfaceVoxelCount);
        assertEquals(468.0, values.surfaceArea, 0.0);
        assertEquals(2.0, values.centroidX(), 0.0);
        assertEquals(2.0, values.centroidY(), 0.0);
        assertEquals(2.0, values.centroidZ(), 0.0);
        assertEquals(2.0, values.centerOfMassX(), 0.0);
        assertEquals(2.0, values.centerOfMassY(), 0.0);
        assertEquals(2.0, values.centerOfMassZ(), 0.0);
        assertEquals(1, values.minX);
        assertEquals(1, values.minY);
        assertEquals(1, values.minZ);
        assertEquals(3, values.maxX);
        assertEquals(3, values.maxY);
        assertEquals(3, values.maxZ);
        assertEquals(3, values.boundingWidth());
        assertEquals(3, values.boundingHeight());
        assertEquals(3, values.boundingDepth());
        assertFalse(values.hasIntensityValues());
        assertTrue(Double.isNaN(values.intensityMean()));

        ResultsTable table = result.toStatisticsTable(null);
        String volumeColumn = firstHeadingStartingWith(table, "Volume (");
        String surfaceColumn = firstHeadingStartingWith(table, "Surface (");
        assertEquals(1, table.size());
        assertEquals(648.0, table.getValue(volumeColumn, 0), 0.0);
        assertEquals(468.0, table.getValue(surfaceColumn, 0), 0.0);
        assertEquals(7.0, table.getValue("Label", 0), 0.0);
        assertTrue(Double.isNaN(table.getValue("Mean", 0)));
    }

    @Test
    public void shortLabelsWithMatchingIntensityComputeIntensityCentroidAndCenterOfMass() {
        ImagePlus labels = knownShortLabelStack(300);
        ImagePlus intensities = knownIntensityStack();

        LabelFeatureAccumulator.Result result = LabelFeatureAccumulator.scan(labels, intensities, null);

        LabelFeatureAccumulator.FeatureValues values = result.valuesForLabel(300);
        assertNotNull(values);
        assertEquals(3, values.voxelCount);
        assertEquals(2.0 / 3.0, values.centroidX(), 1.0e-12);
        assertEquals(2.0 / 3.0, values.centroidY(), 1.0e-12);
        assertEquals(1.0 / 3.0, values.centroidZ(), 1.0e-12);
        assertEquals(12.0, values.intensitySum, 0.0);
        assertEquals(56.0, values.intensitySumSquares, 0.0);
        assertEquals(4.0, values.intensityMean(), 0.0);
        assertEquals(2.0, values.intensityMin(), 0.0);
        assertEquals(6.0, values.intensityMax(), 0.0);
        assertEquals(Math.sqrt(8.0 / 3.0), values.intensityStdDev(), 1.0e-12);
        assertEquals(2.0 / 3.0, values.centerOfMassX(), 1.0e-12);
        assertEquals(1.0, values.centerOfMassY(), 1.0e-12);
        assertEquals(0.5, values.centerOfMassZ(), 1.0e-12);
        assertEquals(4.0, values.xxSum, 0.0);
        assertEquals(4.0, values.yySum, 0.0);
        assertEquals(1.0, values.zzSum, 0.0);
        assertEquals(0.0, values.xySum, 0.0);
        assertEquals(0.0, values.xzSum, 0.0);
        assertEquals(2.0, values.yzSum, 0.0);

        ResultsTable table = result.toStatisticsTable(null);
        assertEquals(12.0, table.getValue("IntDen", 0), 0.0);
        assertEquals(4.0, table.getValue("Mean", 0), 0.0);
        assertEquals(0.5, table.getValue("ZM", 0), 1.0e-12);
    }

    @Test
    public void longRodHasLargerElongationAndFeretThanCompactCube() {
        LabelFeatureAccumulator.Result result = LabelFeatureAccumulator.scan(
                compactAndRodLabelMap(), null, null);

        LabelFeatureAccumulator.FeatureValues compact = result.valuesForLabel(1);
        LabelFeatureAccumulator.FeatureValues rod = result.valuesForLabel(2);

        assertNotNull(compact);
        assertNotNull(rod);
        assertTrue(Double.isFinite(compact.elongation()));
        assertTrue(Double.isFinite(rod.elongation()));
        assertTrue("rod elongation should exceed compact object elongation",
                rod.elongation() > compact.elongation());
        assertTrue("rod Feret estimate should exceed compact object Feret estimate",
                rod.feretDiameterMax() > compact.feretDiameterMax());

        ResultsTable table = result.toStatisticsTable(null);
        assertTrue(Double.isFinite(table.getValue("Morph_Elongation", 0)));
        assertTrue(Double.isFinite(table.getValue("Morph_Feret3D_um", 0)));
    }

    @Test
    public void feretUsesAnisotropicCalibration() {
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 2.0;
        calibration.pixelHeight = 1.0;
        calibration.pixelDepth = 1.0;

        LabelFeatureAccumulator.Result result = LabelFeatureAccumulator.scan(
                twoPointXLabelMap(), null, calibration);

        LabelFeatureAccumulator.FeatureValues values = result.valuesForLabel(1);
        assertNotNull(values);
        assertEquals(4.0, values.feretDiameterMax(), 1.0e-12);
    }

    @Test
    public void elongationUsesAnisotropicCalibration() {
        Calibration calibration = new Calibration();
        calibration.pixelWidth = 1.0;
        calibration.pixelHeight = 1.0;
        calibration.pixelDepth = 3.0;

        LabelFeatureAccumulator.Result result = LabelFeatureAccumulator.scan(
                twoByTwoByTwoLabelMap(), null, calibration);

        LabelFeatureAccumulator.FeatureValues values = result.valuesForLabel(1);
        assertNotNull(values);
        assertEquals(3.0, values.elongation(), 1.0e-12);
    }

    @Test
    public void oneVoxelHasDegenerateElongationAndZeroFeret() {
        LabelFeatureAccumulator.Result result = LabelFeatureAccumulator.scan(
                singleVoxelLabelMap(), null, null);

        LabelFeatureAccumulator.FeatureValues values = result.valuesForLabel(1);
        assertNotNull(values);
        assertTrue(Double.isNaN(values.elongation()));
        assertEquals(0.0, values.feretDiameterMax(), 0.0);
    }

    @Test
    public void sparseFallbackKeepsLargeShortLabelsWithoutDenseAllocation() {
        String previous = System.getProperty(LabelFeatureAccumulator.MAX_DENSE_LABEL_PROPERTY);
        System.setProperty(LabelFeatureAccumulator.MAX_DENSE_LABEL_PROPERTY, "10");
        try {
            ImageStack stack = new ImageStack(3, 3);
            ShortProcessor processor = new ShortProcessor(3, 3);
            processor.set(1, 1, 60000);
            stack.addSlice(processor);

            LabelFeatureAccumulator.Result result = LabelFeatureAccumulator.scan(
                    new ImagePlus("labels", stack), null, null);

            assertTrue(result.usesSparseStorage());
            assertEquals(Arrays.asList(Integer.valueOf(60000)), result.labelsSorted());
            assertNotNull(result.valuesForLabel(60000));
            assertEquals(1, result.valuesForLabel(60000).voxelCount);
        } finally {
            if (previous == null) {
                System.clearProperty(LabelFeatureAccumulator.MAX_DENSE_LABEL_PROPERTY);
            } else {
                System.setProperty(LabelFeatureAccumulator.MAX_DENSE_LABEL_PROPERTY, previous);
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIntensityImagesWithMismatchedDimensions() {
        LabelFeatureAccumulator.scan(solidByteCube(5, 1),
                new ImagePlus("mismatched", new ByteProcessor(4, 5)), null);
    }

    @Test
    public void accumulatorSourceDoesNotImportMcib3dClasses() throws IOException {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/sc/fiji/oc3dplus/engine/LabelFeatureAccumulator.java")),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("import mcib3d"));
    }

    private static ImagePlus solidByteCube(int size, int label) {
        ImageStack stack = new ImageStack(size, size);
        for (int z = 0; z < size; z++) {
            ByteProcessor processor = new ByteProcessor(size, size);
            if (z >= 1 && z <= 3) {
                for (int y = 1; y <= 3; y++) {
                    for (int x = 1; x <= 3; x++) {
                        processor.set(x, y, label);
                    }
                }
            }
            stack.addSlice(processor);
        }
        return new ImagePlus("labels", stack);
    }

    private static ImagePlus compactAndRodLabelMap() {
        ImageStack stack = new ImageStack(20, 10);
        for (int z = 0; z < 6; z++) {
            ShortProcessor processor = new ShortProcessor(20, 10);
            if (z >= 1 && z < 4) {
                drawCubeSlice(processor, 1, 4, 1, 4, 1);
            }
            if (z >= 1 && z < 3) {
                drawCubeSlice(processor, 8, 18, 6, 8, 2);
            }
            stack.addSlice(processor);
        }
        return new ImagePlus("compact-and-rod", stack);
    }

    private static ImagePlus twoPointXLabelMap() {
        ImageStack stack = new ImageStack(3, 1);
        ShortProcessor processor = new ShortProcessor(3, 1);
        processor.set(0, 0, 1);
        processor.set(2, 0, 1);
        stack.addSlice(processor);
        return new ImagePlus("two-point-x", stack);
    }

    private static ImagePlus twoByTwoByTwoLabelMap() {
        ImageStack stack = new ImageStack(2, 2);
        for (int z = 0; z < 2; z++) {
            ShortProcessor processor = new ShortProcessor(2, 2);
            drawCubeSlice(processor, 0, 2, 0, 2, 1);
            stack.addSlice(processor);
        }
        return new ImagePlus("two-by-two-by-two", stack);
    }

    private static ImagePlus singleVoxelLabelMap() {
        ImageStack stack = new ImageStack(3, 3);
        ShortProcessor processor = new ShortProcessor(3, 3);
        processor.set(1, 1, 1);
        stack.addSlice(processor);
        return new ImagePlus("single-voxel", stack);
    }

    private static String firstHeadingStartingWith(ResultsTable table, String prefix) {
        String[] headings = table.getHeadings();
        for (int i = 0; i < headings.length; i++) {
            if (headings[i] != null && headings[i].startsWith(prefix)) {
                return headings[i];
            }
        }
        throw new AssertionError("No heading starts with " + prefix);
    }

    private static ImagePlus knownShortLabelStack(int label) {
        ImageStack stack = new ImageStack(3, 3);
        ShortProcessor first = new ShortProcessor(3, 3);
        first.set(0, 0, label);
        first.set(2, 0, label);
        stack.addSlice(first);

        ShortProcessor second = new ShortProcessor(3, 3);
        second.set(0, 2, label);
        stack.addSlice(second);
        return new ImagePlus("labels", stack);
    }

    private static ImagePlus knownIntensityStack() {
        ImageStack stack = new ImageStack(3, 3);
        FloatProcessor first = new FloatProcessor(3, 3);
        first.setf(0, 0, 2.0f);
        first.setf(2, 0, 4.0f);
        stack.addSlice(first);

        FloatProcessor second = new FloatProcessor(3, 3);
        second.setf(0, 2, 6.0f);
        stack.addSlice(second);
        return new ImagePlus("intensity", stack);
    }

    private static void drawCubeSlice(ShortProcessor processor,
                                      int x0,
                                      int x1,
                                      int y0,
                                      int y1,
                                      int value) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                processor.set(x, y, value);
            }
        }
    }
}
