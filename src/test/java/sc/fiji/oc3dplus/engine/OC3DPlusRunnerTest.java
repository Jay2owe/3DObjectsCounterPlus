package sc.fiji.oc3dplus.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusParameters;
import sc.fiji.oc3dplus.api.OC3DPlusResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OC3DPlusRunnerTest {

    @Test
    public void detectRemovesEdgeObjectsFromReturnedLabelMap() {
        OC3DPlusResult result = OC3DPlus.detect(edgeAndInteriorStack(),
                100, 1, Integer.MAX_VALUE, true, null);

        assertEquals(1, result.objectCount());
        assertNotNull(result.labelImage());
        assertEquals(1, positiveLabels(result.labelImage()).size());
    }

    @Test
    public void excludeOnEdgesRunsBeforeMorphFiltering() {
        OC3DPlusParameters params = OC3DPlus.builder()
                .threshold(100)
                .minSize(1)
                .excludeOnEdges(true)
                .addFilter("volume", ">=", 1.0)
                .build();

        OC3DPlusResult result = OC3DPlus.count(edgeAndInteriorStack(), params);

        assertEquals(1, result.objectCount());
        assertEquals(1, result.survivingPerFilter().length);
        assertEquals(1, result.survivingPerFilter()[0]);
        assertNotNull(result.labelImage());
        assertEquals(1, positiveLabels(result.labelImage()).size());
    }

    /**
     * Detection, then filtering, then renumbering, on one engine.
     *
     * <p>This used to inject a fake detection backend so it could hand the runner
     * a ready-made label map. There is one engine now and no such seam, so the
     * test drives the real thing: three separated objects of 1, 5 and 8 voxels, a
     * volume filter that keeps only the largest, and the survivor renumbered to 1.
     */
    @Test
    public void filteringRenumbersSurvivorsFromOne() {
        OC3DPlusRunner runner = new OC3DPlusRunner();

        OC3DPlusRunner.Result result = runner.runResult(basicFilterIntensityStack(),
                OC3DPlus.builder()
                        .threshold(10)
                        .minSize(1)
                        .addFilter("volume", ">=", 6.0)
                        .build());

        assertNotNull(result.getLabelImage());
        assertEquals(labels(Integer.valueOf(1)), positiveLabels(result.getLabelImage()));
        assertEquals(1, result.getPredicateCounts().length);
        assertEquals(1, result.getPredicateCounts()[0]);
        assertEquals(1, result.getStatistics().size());
        assertEquals(1.0, result.getStatistics().getValue("Label", 0), 0.0);
        assertEquals("the 8-voxel box is the only survivor, and it is measured from "
                        + "the source stack where it has intensity 80",
                80.0, result.getStatistics().getValue("Mean", 0), 0.0);
    }

    @Test
    public void filteredStatsLabelsAreConsecutiveAndMatchFinalLabelImage() {
        OC3DPlusRunner runner = new OC3DPlusRunner();

        OC3DPlusRunner.Result result = runner.runResult(basicFilterIntensityStack(),
                OC3DPlus.builder()
                        .threshold(10)
                        .minSize(1)
                        .addFilter("volume", ">=", 5.0)
                        .build());

        ResultsTable stats = result.getStatistics();
        assertEquals(2, stats.size());
        assertEquals(labels(Integer.valueOf(1), Integer.valueOf(2)),
                positiveLabels(result.getLabelImage()));
        assertEquals(positiveLabels(result.getLabelImage()), labelsFromStats(stats));
        assertEquals(1.0, stats.getValue("Label", 0), 0.0);
        assertEquals(2.0, stats.getValue("Label", 1), 0.0);
        // Survivors keep their detected measurements and are renumbered in the
        // order they were detected: the 5-voxel column (intensity 200) is found on
        // slice 0, the 8-voxel box (intensity 80) on slice 1.
        assertEquals(200.0, stats.getValue("Mean", 0), 0.0);
        assertEquals(80.0, stats.getValue("Mean", 1), 0.0);
        assertHasColumn(stats, "Morph_Sphericity");
        assertHasColumn(stats, "Morph_Compactness");
        assertHasColumn(stats, "Morph_Elongation");
        assertHasColumn(stats, "Morph_Feret3D_um");
    }

    /**
     * 32-bit input takes the same path as everything else.
     *
     * <p>This test used to assert the opposite: that a 32-bit stack "falls back to
     * native detection", because {@code canUseClassicCounter} accepted only 8- and
     * 16-bit and everything else went to mcib3d - the path the code itself called
     * crash-prone. There is no fallback now, and no second engine to fall back to,
     * so what is worth asserting is that 32-bit input is simply measured.
     */
    @Test
    public void thirtyTwoBitInputRunsOnTheUnifiedPath() {
        OC3DPlusRunner runner = new OC3DPlusRunner();

        OC3DPlusRunner.Result result = runner.runResult(floatStackWithOneCube(),
                OC3DPlus.builder()
                        .threshold(100)
                        .minSize(1)
                        .addFilter("volume", ">=", 1.0)
                        .build());

        assertNotNull(result.getLabelImage());
        assertEquals(1, result.getStatistics().size());
        assertEquals(1, result.getPredicateCounts()[0]);
        assertEquals(8.0, result.getStatistics().getValue("Nb of obj. voxels", 0), 0.0);
        assertEquals(150.0, result.getStatistics().getValue("Mean", 0), 0.0);
    }

    @Test
    public void statsPropertyReturnsDefensiveCopy() {
        ImagePlus labelImage = edgeAndInteriorStack();
        ResultsTable original = new ResultsTable();
        original.incrementCounter();
        original.setValue("Label", 0, 1);
        original.setValue("Mean", 0, 42.0);
        labelImage.setProperty(OC3DPlusRunner.OBJECT_STATS_PROPERTY, original);

        ResultsTable copy = OC3DPlusRunner.statsProperty(labelImage);
        copy.setValue("Mean", 0, 99.0);

        ResultsTable stored = (ResultsTable) labelImage.getProperty(
                OC3DPlusRunner.OBJECT_STATS_PROPERTY);
        assertEquals(42.0, stored.getValue("Mean", 0), 0.0);
        assertEquals(0, OC3DPlusRunner.statsProperty(null).size());
    }

    @Test
    public void countReturnsStatisticsWithoutLabelImageTableProperty() {
        OC3DPlusResult result = OC3DPlus.count(edgeAndInteriorStack(), OC3DPlus.builder()
                .threshold(100)
                .minSize(1)
                .excludeOnEdges(true)
                .build());

        assertNotNull(result.labelImage());
        assertNull(result.labelImage().getProperty(OC3DPlusRunner.OBJECT_STATS_PROPERTY));
        assertEquals(1, result.statistics().size());
        assertHasFiniteValue(result.statistics(), "Mean");
        assertHasColumn(result.statistics(), "Morph_Sphericity");
    }

    @Test
    public void emptyImageReportsZeroForEachPredicate() {
        OC3DPlusParameters params = OC3DPlus.builder()
                .threshold(100)
                .minSize(1)
                .addFilter("volume", ">=", 1.0)
                .addFilter("volume", "<=", 10.0)
                .build();

        OC3DPlusResult result = OC3DPlus.count(emptyStack(), params);

        assertEquals(0, result.objectCount());
        assertNotNull(result.labelImage());
        assertEquals(2, result.survivingPerFilter().length);
        assertEquals(0, result.survivingPerFilter()[0]);
        assertEquals(0, result.survivingPerFilter()[1]);
    }

    @Test
    public void calibratedVolumePredicateUsesUnitVolumeNotVoxelCount() {
        ImagePlus calibrated = edgeAndInteriorStack();
        Calibration cal = new Calibration();
        cal.setUnit("um");
        cal.pixelWidth = 0.5;
        cal.pixelHeight = 0.5;
        cal.pixelDepth = 2.0;
        calibrated.setCalibration(cal);

        OC3DPlusResult voxelVolume = OC3DPlus.count(calibrated, OC3DPlus.builder()
                .threshold(100)
                .minSize(1)
                .excludeOnEdges(true)
                .addFilter("volume", ">=", 20.0)
                .build());
        OC3DPlusResult calibratedVolume = OC3DPlus.count(calibrated, OC3DPlus.builder()
                .threshold(100)
                .minSize(1)
                .excludeOnEdges(true)
                .addFilter("volume_calibrated", ">=", 20.0)
                .build());

        assertEquals(1, voxelVolume.objectCount());
        assertEquals(0, calibratedVolume.objectCount());
        assertEquals(0, calibratedVolume.survivingPerFilter()[0]);
    }

    @Test
    public void basicGeometryFiltersUseAccumulatorMeasurements() {
        OC3DPlusRunner runner = new OC3DPlusRunner();

        OC3DPlusRunner.Result result = runner.runResult(basicFilterIntensityStack(),
                OC3DPlus.builder()
                        .threshold(10)
                        .minSize(1)
                        .addFilter("surface_area", ">=", 20.0)
                        .addFilter("sphericity", ">=", 0.7)
                        .addFilter("compactness", "<=", 2.0)
                        .build());

        assertEquals(1, result.getStatistics().size());
        assertEquals(3, result.getPredicateCounts().length);
        // surface_area>=20 keeps the 3x3x2 box and the 1x1x5 column (drops the 1-voxel blob).
        assertEquals(2, result.getPredicateCounts()[0]);
        // Corrected (Lindblad) sphericity: column=0.964, box=1.523 (small blocky objects can
        // exceed 1.0 under the corrected estimate); both pass sphericity>=0.7.
        assertEquals(2, result.getPredicateCounts()[1]);
        // compactness = sphericity^3: column=0.896 passes <=2.0, box=3.531 is dropped.
        assertEquals(1, result.getPredicateCounts()[2]);
        assertHasFiniteValue(result.getStatistics(), "Morph_Sphericity");
        assertHasFiniteValue(result.getStatistics(), "Morph_Compactness");
    }

    @Test
    public void intensityFiltersUseSourceImageWhenNoRedirectIsSupplied() {
        OC3DPlusRunner runner = new OC3DPlusRunner();

        OC3DPlusRunner.Result result = runner.runResult(basicFilterIntensityStack(),
                OC3DPlus.builder()
                        .threshold(100)
                        .minSize(1)
                        .addFilter("mean_intensity", ">=", 100.0)
                        .addFilter("max_intensity", ">=", 200.0)
                        .build());

        assertEquals(1, result.getStatistics().size());
        assertEquals(2, result.getPredicateCounts().length);
        assertEquals(1, result.getPredicateCounts()[0]);
        assertEquals(1, result.getPredicateCounts()[1]);
        assertEquals(200.0, result.getStatistics().getValue("Max", 0), 0.0);
    }

    @Test
    public void redirectIntensityFilterKeepsClassicDetectionPath() {
        OC3DPlusRunner runner = new OC3DPlusRunner();

        OC3DPlusRunner.Result result = runner.runResult(basicFilterIntensityStack(),
                OC3DPlus.builder()
                        .threshold(10)
                        .minSize(1)
                        .intensityImage(redirectedBasicIntensityStack())
                        .addFilter("mean_intensity", ">=", 400.0)
                        .build());

        ResultsTable stats = result.getStatistics();
        assertEquals(1, stats.size());
        assertEquals(labels(Integer.valueOf(1)), positiveLabels(result.getLabelImage()));
        assertEquals(1.0, stats.getValue("Label", 0), 0.0);
        assertEquals(4000.0, stats.getValue("IntDen", 0), 0.0);
        assertEquals(500.0, stats.getValue("Mean", 0), 0.0);
        assertEquals(500.0, stats.getValue("Min", 0), 0.0);
        assertEquals(500.0, stats.getValue("Max", 0), 0.0);
    }

    @Test
    public void redirectWithoutFiltersKeepsClassicDetectionAndRedirectStats() {
        OC3DPlusRunner runner = new OC3DPlusRunner();

        OC3DPlusRunner.Result result = runner.runResult(basicFilterIntensityStack(),
                OC3DPlus.builder()
                        .threshold(10)
                        .minSize(1)
                        .intensityImage(redirectedBasicIntensityStack())
                        .build());

        ResultsTable stats = result.getStatistics();
        assertEquals(3, stats.size());
        // Intensities come from the redirect image: the column reads 20 there and
        // the box 500, whatever their values in the stack that was thresholded.
        assertEquals(20.0, stats.getValue("Mean", rowForLabel(stats, 1)), 0.0);
        assertEquals(500.0, stats.getValue("Mean", rowForLabel(stats, 3)), 0.0);
        assertHasColumn(stats, "Morph_Sphericity");
        assertHasColumn(stats, "Morph_Compactness");
        assertHasColumn(stats, "Morph_Elongation");
        assertHasColumn(stats, "Morph_Feret3D_um");
    }

    @Test
    public void elongationFilterUsesAccumulatorMeasurements() {
        OC3DPlusRunner runner = new OC3DPlusRunner();

        OC3DPlusRunner.Result result = runner.runResult(shapeFilterSourceStack(),
                OC3DPlus.builder()
                        .threshold(100)
                        .minSize(1)
                        .addFilter("elongation", ">=", 3.0)
                        .build());

        assertEquals(1, result.getStatistics().size());
        assertEquals(1, result.getPredicateCounts().length);
        assertEquals(1, result.getPredicateCounts()[0]);
        assertEquals(labels(Integer.valueOf(1)), positiveLabels(result.getLabelImage()));
        assertHasFiniteValue(result.getStatistics(), "Morph_Elongation");
    }

    @Test
    public void feretFilterUsesAccumulatorMeasurements() {
        OC3DPlusRunner runner = new OC3DPlusRunner();

        OC3DPlusRunner.Result result = runner.runResult(shapeFilterSourceStack(),
                OC3DPlus.builder()
                        .threshold(100)
                        .minSize(1)
                        .addFilter("feret_diameter_max", ">=", 6.0)
                        .build());

        assertEquals(1, result.getStatistics().size());
        assertEquals(1, result.getPredicateCounts().length);
        assertEquals(1, result.getPredicateCounts()[0]);
        assertEquals(labels(Integer.valueOf(1)), positiveLabels(result.getLabelImage()));
        assertHasFiniteValue(result.getStatistics(), "Morph_Feret3D_um");
    }

    @Test
    public void basicFeatureMeasurementBranchDoesNotReferenceMcib3dClasses() throws IOException {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/sc/fiji/oc3dplus/engine/OC3DPlusRunner.java")),
                StandardCharsets.UTF_8);
        int start = source.indexOf("private static Map<Integer, FeatureValues> computeFeaturesByLabel");
        int end = source.indexOf("private static FilterResult evaluateFilters", start);

        assertTrue("computeFeaturesByLabel should exist", start >= 0);
        assertTrue("evaluateFilters should follow computeFeaturesByLabel", end > start);
        assertFalse(source.substring(start, end).contains("mcib3d"));
    }

    @Test
    public void fromLabelImageStatsRowsAreSortedByLabel() {
        ImagePlus labelImage = labelledStackWithUnsortedLabels();

        ObjectsCounter3DWrapper.Result result = new ObjectsCounter3DWrapper()
                .fromLabelImage(labelImage, null, true, false);

        ResultsTable stats = result.getStatistics();
        assertEquals(2, stats.size());
        assertEquals(5.0, stats.getValue("Label", 0), 0.0);
        assertEquals(20.0, stats.getValue("Label", 1), 0.0);
    }

    @Test
    public void detectStatisticsIncludeClassicSurfaceCentroidAndCenterOfMassColumns() {
        OC3DPlusResult result = OC3DPlus.detect(edgeAndInteriorStack(),
                100, 1, Integer.MAX_VALUE, true, null);

        ResultsTable stats = result.statistics();

        assertEquals(1, stats.size());
        assertHasFiniteValue(stats, "Surface (pixel^2)");
        assertHasFiniteValue(stats, "Nb of obj. voxels");
        assertHasFiniteValue(stats, "Nb of surf. voxels");
        assertHasFiniteValue(stats, "StdDev");
        assertHasFiniteValue(stats, "Min");
        assertHasFiniteValue(stats, "Max");
        assertHasFiniteValue(stats, "X");
        assertHasFiniteValue(stats, "Y");
        assertHasFiniteValue(stats, "Z");
        assertHasFiniteValue(stats, "XM");
        assertHasFiniteValue(stats, "YM");
        assertHasFiniteValue(stats, "ZM");
        assertHasColumn(stats, "Morph_Sphericity");
        assertHasColumn(stats, "Morph_Compactness");
        assertHasColumn(stats, "Morph_Elongation");
        assertHasColumn(stats, "Morph_Feret3D_um");
    }

    @Test
    public void detectStatisticsUseClassicColumnOrderBeforeMorphologyColumns() {
        OC3DPlusResult result = OC3DPlus.detect(edgeAndInteriorStack(),
                100, 1, Integer.MAX_VALUE, true, null);

        String[] headings = result.statistics().getHeadings();

        assertEquals("Volume (pixel^3)", headings[0]);
        assertEquals("Surface (pixel^2)", headings[1]);
        assertEquals("Nb of obj. voxels", headings[2]);
        assertEquals("Nb of surf. voxels", headings[3]);
        assertEquals("IntDen", headings[4]);
        assertEquals("Mean", headings[5]);
        assertEquals("StdDev", headings[6]);
        assertEquals("Median", headings[7]);
        assertEquals("Min", headings[8]);
        assertEquals("Max", headings[9]);
        assertEquals("X", headings[10]);
        assertEquals("Y", headings[11]);
        assertEquals("Z", headings[12]);
        assertTrue(result.statistics().getColumnIndex("Morph_Sphericity") >= 0);
    }

    @Test
    public void countWithoutMorphFiltersStillReportsMorphologyColumns() {
        OC3DPlusResult result = OC3DPlus.count(edgeAndInteriorStack(), OC3DPlus.builder()
                .threshold(100)
                .minSize(1)
                .excludeOnEdges(true)
                .build());

        ResultsTable stats = result.statistics();

        assertEquals(1, stats.size());
        assertHasColumn(stats, "Morph_Sphericity");
        assertHasColumn(stats, "Morph_Compactness");
        assertHasColumn(stats, "Morph_Elongation");
        assertHasColumn(stats, "Morph_Feret3D_um");
        assertAllRowsHaveFiniteMorphology(stats);
    }

    @Test
    public void filteredStatsPopulateAllMorphologyValuesWhenOnlySphericityIsFiltered() {
        OC3DPlusRunner runner = new OC3DPlusRunner();

        OC3DPlusRunner.Result result = runner.runResult(shapeFilterSourceStack(),
                OC3DPlus.builder()
                        .threshold(100)
                        .minSize(1)
                        .addFilter("sphericity", ">=", 0.0)
                        .build());

        ResultsTable stats = result.getStatistics();
        assertEquals(2, stats.size());
        assertAllRowsHaveFiniteMorphology(stats);
    }

    @Test
    public void filteredStatsPopulateAllMorphologyValuesForNonMorphFilter() {
        OC3DPlusRunner runner = new OC3DPlusRunner();

        OC3DPlusRunner.Result result = runner.runResult(shapeFilterSourceStack(),
                OC3DPlus.builder()
                        .threshold(100)
                        .minSize(1)
                        .addFilter("volume", ">=", 1.0)
                        .build());

        ResultsTable stats = result.getStatistics();
        assertEquals(2, stats.size());
        assertAllRowsHaveFiniteMorphology(stats);
    }

    @Test
    public void filteredStatsKeepExpectedMorphologyColumnsForShapeFilters() {
        OC3DPlusRunner runner = new OC3DPlusRunner();

        OC3DPlusRunner.Result result = runner.runResult(shapeFilterSourceStack(),
                OC3DPlus.builder()
                        .threshold(100)
                        .minSize(1)
                        .addFilter("sphericity", ">=", 0.0)
                        .addFilter("compactness", ">=", 0.0)
                        .addFilter("elongation", ">=", 1.0)
                        .addFilter("feret_diameter_max", ">=", 0.0)
                        .build());

        ResultsTable stats = result.getStatistics();
        assertEquals(2, stats.size());
        assertEquals(labels(Integer.valueOf(1), Integer.valueOf(2)),
                positiveLabels(result.getLabelImage()));
        assertHasFiniteValue(stats, "Morph_Sphericity");
        assertHasFiniteValue(stats, "Morph_Compactness");
        assertHasFiniteValue(stats, "Morph_Elongation");
        assertHasFiniteValue(stats, "Morph_Feret3D_um");
    }

    @Test
    public void detectStatisticsUseOriginalIntensityValuesForIntensityColumns() {
        OC3DPlusResult result = OC3DPlus.detect(varyingIntensityStack(),
                100, 1, Integer.MAX_VALUE, false, null);

        ResultsTable stats = result.statistics();

        assertEquals(1, stats.size());
        assertEquals(2808.0, stats.getValue("IntDen", 0), 0.0);
        assertEquals(104.0, stats.getValue("Mean", 0), 0.0);
        // Population standard deviation, dividing by n. The old expectation,
        // 2.631174087524414, was Counter3D's SAMPLE deviation over the same 27
        // voxels - larger by exactly sqrt(27/26). See StdDevDefinitionProbeTest.
        assertEquals(2.581988897471494, stats.getValue("StdDev", 0), 1.0e-12);
        assertEquals(100.0, stats.getValue("Min", 0), 0.0);
        assertEquals(108.0, stats.getValue("Max", 0), 0.0);
        assertEquals(7.006410256410256, stats.getValue("XM", 0), 1.0e-6);
        assertEquals(7.019230769230769, stats.getValue("YM", 0), 1.0e-6);
    }

    @Test
    public void centerOfMassUsesIntensityWeightsAcrossZSlices() {
        OC3DPlusResult result = OC3DPlus.detect(zWeightedIntensityStack(),
                100, 1, Integer.MAX_VALUE, false, null);

        ResultsTable stats = result.statistics();

        assertEquals(1, stats.size());
        assertEquals(2.0, stats.getValue("Z", 0), 0.0);
        assertEquals(2.3333333333333335, stats.getValue("ZM", 0), 1.0e-6);
    }

    @Test
    public void fromLabelImageDirectIntensityStatsKeepAllDenseLabels() {
        ObjectsCounter3DWrapper.Result result = new ObjectsCounter3DWrapper()
                .fromLabelImage(denseLabelStack(), denseIntensityStack(), false, false);

        ResultsTable stats = result.getStatistics();

        assertEquals(3, stats.size());
        for (int row = 0; row < 3; row++) {
            int label = row + 1;
            double expectedMean = label * 10.0;
            assertEquals(label, stats.getValue("Label", row), 0.0);
            assertEquals(expectedMean * 8.0, stats.getValue("IntDen", row), 0.0);
            assertEquals(expectedMean, stats.getValue("Mean", row), 0.0);
            assertEquals(expectedMean, stats.getValue("Min", row), 0.0);
            assertEquals(expectedMean, stats.getValue("Max", row), 0.0);
        }
    }

    private static ImagePlus basicFilterLabelMap() {
        ImageStack stack = new ImageStack(16, 16);
        for (int z = 0; z < 6; z++) {
            ShortProcessor sp = new ShortProcessor(16, 16);
            if (z == 1) {
                sp.set(1, 1, 1);
            }
            if (z >= 1 && z < 3) {
                drawCubeSlice(sp, 4, 6, 4, 6, 2);
            }
            if (z < 5) {
                sp.set(10, 10, 3);
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("basic-filter-labels", stack);
    }

    private static ImagePlus basicFilterIntensityStack() {
        ImageStack stack = new ImageStack(16, 16);
        for (int z = 0; z < 6; z++) {
            ShortProcessor sp = new ShortProcessor(16, 16);
            if (z == 1) {
                sp.set(1, 1, 10);
            }
            if (z >= 1 && z < 3) {
                drawCubeSlice(sp, 4, 6, 4, 6, 80);
            }
            if (z < 5) {
                sp.set(10, 10, 200);
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("basic-filter-intensity", stack);
    }

    private static ImagePlus redirectedBasicIntensityStack() {
        ImageStack stack = new ImageStack(16, 16);
        for (int z = 0; z < 6; z++) {
            ShortProcessor sp = new ShortProcessor(16, 16);
            if (z == 1) {
                sp.set(1, 1, 30);
            }
            if (z >= 1 && z < 3) {
                drawCubeSlice(sp, 4, 6, 4, 6, 500);
            }
            if (z < 5) {
                sp.set(10, 10, 20);
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("redirected-basic-intensity", stack);
    }

    /** A 32-bit stack holding one 2x2x2 cube, for the unified-path check. */
    private static ImagePlus floatStackWithOneCube() {
        ImageStack stack = new ImageStack(8, 8);
        for (int z = 0; z < 3; z++) {
            FloatProcessor fp = new FloatProcessor(8, 8);
            if (z >= 1) {
                for (int y = 2; y < 4; y++) {
                    for (int x = 2; x < 4; x++) {
                        fp.setf(x, y, 150f);
                    }
                }
            }
            stack.addSlice(fp);
        }
        return new ImagePlus("float-one-cube", stack);
    }

    private static ImagePlus shapeFilterLabelMap() {
        ImageStack stack = new ImageStack(20, 10);
        for (int z = 0; z < 6; z++) {
            ShortProcessor sp = new ShortProcessor(20, 10);
            if (z >= 1 && z < 4) {
                drawCubeSlice(sp, 1, 4, 1, 4, 1);
            }
            if (z >= 1 && z < 3) {
                drawCubeSlice(sp, 8, 18, 6, 8, 2);
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("shape-filter-labels", stack);
    }

    private static ImagePlus shapeFilterSourceStack() {
        ImageStack stack = new ImageStack(20, 10);
        for (int z = 0; z < 6; z++) {
            ShortProcessor sp = new ShortProcessor(20, 10);
            if (z >= 1 && z < 4) {
                drawCubeSlice(sp, 1, 4, 1, 4, 150);
            }
            if (z >= 1 && z < 3) {
                drawCubeSlice(sp, 8, 18, 6, 8, 150);
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("shape-filter-source", stack);
    }

    private static ImagePlus edgeAndInteriorStack() {
        ImageStack stack = new ImageStack(12, 12);
        for (int z = 0; z < 6; z++) {
            ByteProcessor bp = new ByteProcessor(12, 12);
            if (z >= 1 && z <= 3) {
                drawCubeSlice(bp, 0, 3, 0, 3, 200);
                drawCubeSlice(bp, 6, 9, 6, 9, 200);
            }
            stack.addSlice(bp);
        }
        return new ImagePlus("edge-and-interior", stack);
    }

    private static ImagePlus emptyByteStack(int width, int height, int depth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new ByteProcessor(width, height));
        }
        return new ImagePlus("byte-source", stack);
    }

    private static ImagePlus emptyFloatStack(int width, int height, int depth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new FloatProcessor(width, height));
        }
        return new ImagePlus("float-source", stack);
    }

    private static ImagePlus twoLabelMapWithDifferentVolumes() {
        ImageStack stack = new ImageStack(8, 8);
        for (int z = 0; z < 3; z++) {
            ShortProcessor sp = new ShortProcessor(8, 8);
            if (z == 1) {
                sp.set(1, 1, 1);
            }
            if (z < 2) {
                drawCubeSlice(sp, 4, 6, 4, 6, 2);
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("classic-labels", stack);
    }

    private static ImagePlus singleLabelMap() {
        ImageStack stack = new ImageStack(8, 8);
        for (int z = 0; z < 2; z++) {
            ShortProcessor sp = new ShortProcessor(8, 8);
            drawCubeSlice(sp, 2, 4, 2, 4, 1);
            stack.addSlice(sp);
        }
        return new ImagePlus("native-labels", stack);
    }

    private static ImagePlus emptyStack() {
        ImageStack stack = new ImageStack(12, 12);
        for (int z = 0; z < 6; z++) {
            stack.addSlice(new ByteProcessor(12, 12));
        }
        return new ImagePlus("empty", stack);
    }

    private static ImagePlus varyingIntensityStack() {
        ImageStack stack = new ImageStack(12, 12);
        for (int z = 0; z < 6; z++) {
            ShortProcessor sp = new ShortProcessor(12, 12);
            if (z >= 1 && z <= 3) {
                int value = 100;
                for (int y = 6; y < 9; y++) {
                    for (int x = 6; x < 9; x++) {
                        sp.set(x, y, value++);
                    }
                }
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("varying-intensity", stack);
    }

    private static ImagePlus zWeightedIntensityStack() {
        ImageStack stack = new ImageStack(12, 12);
        for (int z = 0; z < 6; z++) {
            ShortProcessor sp = new ShortProcessor(12, 12);
            if (z >= 1 && z <= 3) {
                int value = z == 1 ? 100 : z == 2 ? 200 : 300;
                for (int y = 6; y < 9; y++) {
                    for (int x = 6; x < 9; x++) {
                        sp.set(x, y, value);
                    }
                }
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("z-weighted-intensity", stack);
    }

    private static ImagePlus labelledStackWithUnsortedLabels() {
        ImageStack stack = new ImageStack(12, 12);
        for (int z = 0; z < 3; z++) {
            ByteProcessor bp = new ByteProcessor(12, 12);
            drawCubeSlice(bp, 1, 3, 1, 3, 20);
            drawCubeSlice(bp, 7, 9, 7, 9, 5);
            stack.addSlice(bp);
        }
        return new ImagePlus("unsorted-labels", stack);
    }

    private static ImagePlus denseLabelStack() {
        ImageStack stack = new ImageStack(10, 10);
        for (int z = 0; z < 4; z++) {
            ShortProcessor sp = new ShortProcessor(10, 10);
            if (z >= 1 && z <= 2) {
                drawCubeSlice(sp, 1, 3, 1, 3, 1);
                drawCubeSlice(sp, 5, 7, 1, 3, 2);
                drawCubeSlice(sp, 1, 3, 5, 7, 3);
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("dense-labels", stack);
    }

    private static ImagePlus denseIntensityStack() {
        ImageStack stack = new ImageStack(10, 10);
        for (int z = 0; z < 4; z++) {
            ShortProcessor sp = new ShortProcessor(10, 10);
            if (z >= 1 && z <= 2) {
                drawCubeSlice(sp, 1, 3, 1, 3, 10);
                drawCubeSlice(sp, 5, 7, 1, 3, 20);
                drawCubeSlice(sp, 1, 3, 5, 7, 30);
            }
            stack.addSlice(sp);
        }
        return new ImagePlus("dense-intensity", stack);
    }

    private static void drawCubeSlice(ByteProcessor bp, int x0, int x1,
                                      int y0, int y1, int value) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                bp.set(x, y, value);
            }
        }
    }

    private static void drawCubeSlice(ShortProcessor sp, int x0, int x1,
                                      int y0, int y1, int value) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                sp.set(x, y, value);
            }
        }
    }

    private static Set<Integer> positiveLabels(ImagePlus image) {
        Set<Integer> labels = new HashSet<Integer>();
        ImageStack stack = image.getStack();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            for (int i = 0; i < stack.getProcessor(slice).getPixelCount(); i++) {
                int label = Math.round(stack.getProcessor(slice).getf(i));
                if (label > 0) labels.add(Integer.valueOf(label));
            }
        }
        return labels;
    }

    private static Set<Integer> labels(Integer... values) {
        Set<Integer> labels = new HashSet<Integer>();
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                labels.add(values[i]);
            }
        }
        return labels;
    }

    private static Set<Integer> labelsFromStats(ResultsTable stats) {
        Set<Integer> labels = new HashSet<Integer>();
        for (int row = 0; row < stats.size(); row++) {
            labels.add(Integer.valueOf((int) Math.round(stats.getValue("Label", row))));
        }
        return labels;
    }

    private static int rowForLabel(ResultsTable stats, int label) {
        for (int row = 0; row < stats.size(); row++) {
            if ((int) Math.round(stats.getValue("Label", row)) == label) return row;
        }
        throw new AssertionError("missing row for label " + label);
    }

    private static ResultsTable statsForLabels(ImagePlus labelImage) {
        ResultsTable stats = new ResultsTable();
        Set<Integer> labels = positiveLabels(labelImage);
        for (Integer label : labels) {
            int row = stats.size();
            stats.incrementCounter();
            stats.setValue("Label", row, label.intValue());
            stats.setValue("Mean", row, label.intValue());
        }
        return stats;
    }

    private static void assertHasFiniteValue(ResultsTable stats, String column) {
        assertTrue("missing column " + column, stats.getColumnIndex(column) >= 0);
        assertTrue("non-finite " + column, Double.isFinite(stats.getValue(column, 0)));
    }

    private static void assertAllRowsHaveFiniteMorphology(ResultsTable stats) {
        assertTrue("expected at least one statistics row", stats.size() > 0);
        assertAllRowsHaveFiniteValue(stats, "Morph_Sphericity");
        assertAllRowsHaveFiniteValue(stats, "Morph_Compactness");
        assertAllRowsHaveFiniteValue(stats, "Morph_Elongation");
        assertAllRowsHaveFiniteValue(stats, "Morph_Feret3D_um");
    }

    private static void assertAllRowsHaveFiniteValue(ResultsTable stats, String column) {
        assertTrue("missing column " + column, stats.getColumnIndex(column) >= 0);
        for (int row = 0; row < stats.size(); row++) {
            assertTrue("non-finite " + column + " at row " + row,
                    Double.isFinite(stats.getValue(column, row)));
        }
    }

    private static void assertHasColumn(ResultsTable stats, String column) {
        assertTrue("missing column " + column, stats.getColumnIndex(column) >= 0);
    }

    private static final class RecordingCounterBackend implements OC3DPlusRunner.CounterBackend {
        private final ImagePlus classicLabelImage;
        private final ImagePlus nativeLabelImage;
        private Set<Integer> labelsMeasuredFromFinalMap = new HashSet<Integer>();
        private int classicRuns;
        private int nativeRuns;
        private int fromLabelImageRuns;

        RecordingCounterBackend(ImagePlus classicLabelImage, ImagePlus nativeLabelImage) {
            this.classicLabelImage = classicLabelImage;
            this.nativeLabelImage = nativeLabelImage;
        }

        @Override public ObjectsCounter3DWrapper.Result run(
                ImagePlus img,
                int threshold,
                int minSize,
                int maxSize,
                boolean excludeOnEdges,
                boolean redirect,
                boolean wantObjectsMap,
                boolean wantMaskedImage) {
            classicRuns++;
            return resultFor(classicLabelImage);
        }

        @Override public ObjectsCounter3DWrapper.Result runNative(
                ImagePlus img,
                int threshold,
                int minSize,
                int maxSize,
                boolean excludeOnEdges,
                ImagePlus redirectImage,
                boolean wantObjectsMap,
                boolean wantMaskedImage,
                ProgressReporter progress,
                boolean finishProgress) {
            nativeRuns++;
            return resultFor(nativeLabelImage);
        }

        @Override public ObjectsCounter3DWrapper.Result fromLabelImage(
                ImagePlus labelImage,
                ImagePlus redirectImage,
                int minSize,
                int maxSize,
                boolean wantObjectsMap,
                boolean wantMaskedImage,
                ProgressReporter progress,
                boolean finishProgress) {
            fromLabelImageRuns++;
            labelsMeasuredFromFinalMap = positiveLabels(labelImage);
            return resultFor(labelImage);
        }

        private static ObjectsCounter3DWrapper.Result resultFor(ImagePlus labelImage) {
            ResultsTable stats = statsForLabels(labelImage);
            return new ObjectsCounter3DWrapper.Result(stats, labelImage, null,
                    stats.size() > 0);
        }
    }
}
