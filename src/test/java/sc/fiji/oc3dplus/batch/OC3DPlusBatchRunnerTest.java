package sc.fiji.oc3dplus.batch;

import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileSaver;
import ij.process.ByteProcessor;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.Rule;
import sc.fiji.oc3dplus.ui.OC3DPlusDialogModel;

import java.io.File;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class OC3DPlusBatchRunnerTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void batchParametersDefensivelyCopySettingsOnInputAndOutput()
            throws Exception {
        File input = temporary.newFolder("immutable-parameters");
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.threshold = 123;
        model.redirectTitle = "raw.tif";
        OC3DPlusBatchParameters parameters = new OC3DPlusBatchParameters(
                input, java.util.Collections.<File>emptyList(), model);

        model.threshold = 7;
        OC3DPlusDialogModel returned = parameters.settings();
        assertEquals(123, returned.threshold);
        assertEquals("", returned.redirectTitle);

        returned.threshold = 9;
        assertEquals(123, parameters.settings().threshold);
    }

    @Test
    public void writesManifestObjectsAndScoresWithEveryDiscoveredImage() throws Exception {
        File input = temporary.newFolder("input");
        File valid = new File(input, "three_objects.tif");
        assertTrue(new FileSaver(threeObjects()).saveAsTiffStack(valid.getAbsolutePath()));
        File broken = new File(input, "broken.tif");
        Files.write(broken.toPath(), "not a tiff".getBytes(StandardCharsets.UTF_8));

        File outputRoot = new File(input, "3D Objects Counter Plus Batch");
        List<File> files = BatchFileDiscovery.discover(input, true, outputRoot);
        assertEquals(2, files.size());
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.threshold = 100;
        model.minSize = 1;
        model.maxSize = Integer.MAX_VALUE;

        OC3DPlusBatchRunner.Result result = OC3DPlusBatchRunner.run(
                new OC3DPlusBatchParameters(input, files, model), null);

        assertEquals(2, result.inputCount);
        assertEquals(1, result.successfulCount);
        assertEquals(1, result.failedCount);
        assertEquals(3, result.objectCount);
        assertEquals(result.outputDirectory.getName(), result.batchRunId);
        String manifest = text(new File(result.outputDirectory, "batch_manifest.csv"));
        String objects = text(new File(result.outputDirectory, "batch_objects.csv"));
        String scores = text(new File(result.outputDirectory, "batch_scores.csv"));
        assertTrue(manifest.contains("three_objects.tif"));
        assertTrue(manifest.contains("broken.tif"));
        assertTrue(manifest.contains("failed"));
        assertTrue(manifest.contains("SourceLastModified"));
        assertTrue(manifest.contains("ContributedScoreRows"));
        assertTrue(manifest.contains("SpatialUnit"));
        assertTrue(manifest.contains("FractalXYEnabled"));
        assertTrue(objects.contains("SourceRelativePath"));
        assertTrue(objects.contains("SourceImageIndex"));
        assertTrue(objects.contains("three_objects.tif"));
        assertTrue(scores.contains("WithinBatchPercentile"));
        assertTrue(scores.contains("RawUnit"));
        assertTrue(scores.contains("ScoringValue"));
        assertTrue(scores.contains("ScoringUnit"));
        assertTrue(scores.contains("all successful objects in this BatchRunId"));
        assertTrue(scores.contains(",Volume,"));
        assertTrue(scores.contains("Morph_Sphericity"));
        assertTrue(scores.contains("NaN"));
        int emittedRows = 0;
        for (String line : scores.split("\\R")) {
            String[] cells = line.split(",", -1);
            if (cells.length >= 2 && "three_objects.tif".equals(cells[1])) {
                emittedRows++;
            }
        }
        int contributedRows = -1;
        for (String line : manifest.split("\\R")) {
            String[] cells = line.split(",", -1);
            if (cells.length >= 7 && "three_objects.tif".equals(cells[1])) {
                contributedRows = Integer.parseInt(cells[6]);
            }
        }
        assertEquals(emittedRows, contributedRows);
        assertFalse(new File(result.outputDirectory, ".incomplete").exists());
        assertFalse(new File(result.outputDirectory, ".batch_scores.csv.tmp").exists());
    }

    @Test
    public void collidingTimestampIdsReceiveUniqueIdsAndDirectories()
            throws Exception {
        File root = temporary.newFolder("run-id-root");

        File first = OC3DPlusBatchRunner.createUniqueOutputDirectory(
                root, "20260730-120000-000");
        File second = OC3DPlusBatchRunner.createUniqueOutputDirectory(
                root, "20260730-120000-000");

        assertEquals("20260730-120000-000", first.getName());
        assertEquals("20260730-120000-000-2", second.getName());
        assertTrue(first.isDirectory());
        assertTrue(second.isDirectory());
    }

    @Test
    public void allZeroObjectBatchRetainsLegacyAndEnabledExtendedSchema()
            throws Exception {
        File input = temporary.newFolder("zero-object-input");
        File blank = new File(input, "blank.tif");
        assertTrue(new FileSaver(noObjects()).saveAsTiffStack(blank.getAbsolutePath()));
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.threshold = 100;
        model.minSize = 1;
        model.measureComposites = true;

        OC3DPlusBatchRunner.Result result = OC3DPlusBatchRunner.run(
                new OC3DPlusBatchParameters(
                        input, java.util.Collections.singletonList(blank), model),
                null);

        assertEquals(0, result.objectCount);
        String objects = text(new File(result.outputDirectory, "batch_objects.csv"));
        String[] lines = objects.split("\\R");
        assertEquals(1, lines.length);
        assertTrue(lines[0].contains("Volume ("));
        assertTrue(lines[0].contains("Morph_Sphericity"));
        assertTrue(lines[0].contains("Morph_RI"));
        assertTrue(lines[0].indexOf("Morph_Sphericity")
                < lines[0].indexOf("Morph_RI"));
    }

    @Test
    public void oneIncompatibleImageExcludesPhysicalFeatureAcrossWholeBatch()
            throws Exception {
        File input = temporary.newFolder("mixed-unit-input");
        List<File> files = new ArrayList<File>();
        for (int i = 0; i < 3; i++) {
            File file = new File(input, "micrometre-" + i + ".tif");
            saveThreeObjects(file, "um", 1.0);
            files.add(file);
        }
        File pixels = new File(input, "pixels.tif");
        saveThreeObjects(pixels, "pixel", 1.0);
        files.add(pixels);

        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.threshold = 100;
        model.minSize = 1;
        model.measureFractalXY = true;
        OC3DPlusBatchRunner.Result result = OC3DPlusBatchRunner.run(
                new OC3DPlusBatchParameters(input, files, model), null);

        String scores = text(new File(result.outputDirectory, "batch_scores.csv"));
        int volumeRows = 0;
        for (String line : scores.split("\\R")) {
            String[] cells = line.split(",", -1);
            if (cells.length < 15 || !"Volume".equals(cells[4])) continue;
            volumeRows++;
            assertTrue(Double.isFinite(Double.parseDouble(cells[5])));
            assertEquals("NaN", cells[7]);
            assertEquals("NaN", cells[9]);
            assertEquals("NaN", cells[10]);
        }
        assertEquals(12, volumeRows);
        String manifest = text(new File(result.outputDirectory, "batch_manifest.csv"));
        assertTrue(manifest.contains(
                "Volume scores were excluded across the batch"));
        String objects = text(new File(result.outputDirectory, "batch_objects.csv"));
        String header = objects.substring(0, objects.indexOf('\n'));
        int firstExtended = header.indexOf("Morph_FractalDim_XY");
        assertTrue(firstExtended > 0);
        assertTrue(header.lastIndexOf("Volume (") < firstExtended);
        assertTrue(header.lastIndexOf("Surface (") < firstExtended);
    }

    @Test
    public void unavailableExtendedObjectValuesRemainExplicitNaN() throws Exception {
        File input = temporary.newFolder("unavailable-input");
        File valid = new File(input, "objects.tif");
        assertTrue(new FileSaver(threeObjects()).saveAsTiffStack(valid.getAbsolutePath()));
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.threshold = 100;
        model.minSize = 1;
        model.measureArborization = true;

        OC3DPlusBatchRunner.Result result = OC3DPlusBatchRunner.run(
                new OC3DPlusBatchParameters(
                        input, java.util.Collections.singletonList(valid), model),
                null);

        String objects = text(new File(result.outputDirectory, "batch_objects.csv"));
        assertTrue(objects.contains("Morph_ShollCriticalRadius_um"));
        assertTrue(objects.contains("NaN"));
    }

    @Test
    public void convertsRecognisedPhysicalVolumeAndSurfaceUnitsForScoring()
            throws Exception {
        File input = temporary.newFolder("calibrated-input");
        ImagePlus image = threeObjects();
        image.getCalibration().setUnit("nm");
        image.getCalibration().pixelWidth = 1000.0;
        image.getCalibration().pixelHeight = 1000.0;
        image.getCalibration().pixelDepth = 1000.0;
        File valid = new File(input, "objects_nm.tif");
        assertTrue(new FileSaver(image).saveAsTiffStack(valid.getAbsolutePath()));

        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.threshold = 100;
        model.minSize = 1;
        model.maxSize = Integer.MAX_VALUE;
        OC3DPlusBatchRunner.Result result = OC3DPlusBatchRunner.run(
                new OC3DPlusBatchParameters(
                        input, java.util.Collections.singletonList(valid), model),
                null);

        String scores = text(new File(result.outputDirectory, "batch_scores.csv"));
        assertTrue(scores.contains(",Volume,"));
        assertTrue(scores.contains(",nm^3,"));
        assertTrue(scores.contains(",um^3,"));
        assertTrue(scores.contains(",Surface,"));
        assertTrue(scores.contains(",nm^2,"));
        assertTrue(scores.contains(",um^2,"));
    }

    @Test
    public void cancellationLeavesOnlyClearlyIncompleteOutput() throws Exception {
        File input = temporary.newFolder("cancelled-input");
        File valid = new File(input, "objects.tif");
        assertTrue(new FileSaver(threeObjects()).saveAsTiffStack(valid.getAbsolutePath()));
        OC3DPlusDialogModel model = new OC3DPlusDialogModel();
        model.threshold = 100;

        try {
            Thread.currentThread().interrupt();
            OC3DPlusBatchRunner.run(new OC3DPlusBatchParameters(
                    input, java.util.Collections.singletonList(valid), model), null);
            fail("Expected cancellation to interrupt the batch.");
        } catch (InterruptedIOException expected) {
            // Expected.
        } finally {
            Thread.interrupted();
        }

        File root = new File(input, "3D Objects Counter Plus Batch");
        File[] outputs = root.listFiles();
        assertTrue(outputs != null && outputs.length == 1);
        assertTrue(new File(outputs[0], ".incomplete").isFile());
        assertFalse(new File(outputs[0], "batch_manifest.csv").exists());
        assertFalse(new File(outputs[0], "batch_objects.csv").exists());
        assertFalse(new File(outputs[0], "batch_scores.csv").exists());
    }

    private static String text(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static ImagePlus threeObjects() {
        int width = 28;
        int height = 10;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < 3; z++) {
            ByteProcessor processor = new ByteProcessor(width, height);
            fill(processor, 2, 2, 4, 4);
            fill(processor, 11, 2, 13, 4);
            fill(processor, 20, 2, 22, 4);
            stack.addSlice(processor);
        }
        return new ImagePlus("three", stack);
    }

    private static ImagePlus noObjects() {
        ImageStack stack = new ImageStack(12, 10);
        stack.addSlice(new ByteProcessor(12, 10));
        stack.addSlice(new ByteProcessor(12, 10));
        return new ImagePlus("blank", stack);
    }

    private static void saveThreeObjects(File file,
                                         String unit,
                                         double pixelSize) {
        ImagePlus image = threeObjects();
        image.getCalibration().setUnit(unit);
        image.getCalibration().pixelWidth = pixelSize;
        image.getCalibration().pixelHeight = pixelSize;
        image.getCalibration().pixelDepth = pixelSize;
        assertTrue(new FileSaver(image).saveAsTiffStack(file.getAbsolutePath()));
    }

    private static void fill(ByteProcessor processor,
                             int minX,
                             int minY,
                             int maxX,
                             int maxY) {
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                processor.set(x, y, 200);
            }
        }
    }
}
