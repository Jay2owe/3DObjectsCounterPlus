package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;
import ij.plugin.FileInfoVirtualStack;
import org.junit.Assume;
import org.junit.Test;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusResult;
import sc.fiji.oc3dplus.engine.ObjectsCounter3DWrapper;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Re-measures wall clock and peak heap on the <b>real</b> corpus, on the unified
 * path, so the release note's performance figures come from the images this lab
 * actually processes rather than from synthetic speckle.
 *
 * <pre>
 * java -Xmx5g -Doc3dplus.realCorpus=docs/migration/real-corpus.txt \
 *      -cp target/classes;target/test-classes;&lt;deps&gt; \
 *      org.junit.runner.JUnitCore sc.fiji.oc3dplus.equivalence.RealCorpusPerformanceTest
 * </pre>
 *
 * <p>Run outside surefire when a large heap is needed: the scijava parent pins
 * {@code -Xms512m -Xmx512m} in the surefire {@code argLine} after the injected
 * one, so a {@code -DargLine} of a bigger heap is overridden by the later flag and
 * silently ignored.
 *
 * <p><b>Stacks are opened virtually.</b> That is how the plugin is used on these
 * files and how it must be measured - a full load of the 5.5 GB timelapse would
 * dwarf every figure in the table with I/O the engine does not control. Peak heap
 * here is therefore the engine's own footprint plus whatever slices the virtual
 * stack has materialised, which is the number that matters when deciding whether a
 * run fits in Fiji's default heap.
 *
 * <p>The classic engine is measured too, but only where it can be: it holds the
 * whole volume in a flat {@code int[]}, so above {@link #CLASSIC_VOXEL_LIMIT}
 * voxels the comparison costs more than it informs and the row says so rather than
 * quietly omitting it.
 */
public class RealCorpusPerformanceTest {

    private static final String LIST_PROPERTY = "oc3dplus.realCorpus";
    /**
     * The classic baseline is opt-in, because it is not merely slow but
     * unpredictably slow. Measured here: {@code Counter3D.replaceID} was still
     * running after 15 minutes of CPU on the 81.8M-voxel {@code MCG_04} stack,
     * against the 107 seconds Stage 02 measured on 52.4M voxels - 1.6x the volume
     * for more than 8x the time, and counting. Leaving it on by default means one
     * stack can consume the whole run and the unified figures never get written.
     */
    private static final String CLASSIC_PROPERTY = "oc3dplus.classicBaseline";
    /** Four bytes per voxel in {@code Counter3D}'s flat array, plus the run's own working set. */
    private static final long CLASSIC_VOXEL_LIMIT = 100L * 1000 * 1000;
    private static final File REPORT =
            new File("docs" + File.separator + "migration", "PERFORMANCE_UNIFIED.md");

    private static final class Row {
        String file = "";
        String dimensions = "";
        long voxels;
        int channels = 1;
        int frames = 1;
        int bitDepth;
        int threshold;
        int objects = -1;
        long unifiedMillis = -1;
        long unifiedPeakBytes = -1;
        long classicMillis = -1;
        long classicPeakBytes = -1;
        String classicNote = "";
        String note = "";
    }

    @Test
    public void measureTheRealCorpus() throws Exception {
        String configured = System.getProperty(LIST_PROPERTY);
        Assume.assumeTrue("set -D" + LIST_PROPERTY + "=docs/migration/real-corpus.txt",
                configured != null && !configured.trim().isEmpty());

        List<String> paths = readList(new File(configured.trim()));
        List<Row> rows = new ArrayList<Row>();
        for (int i = 0; i < paths.size(); i++) {
            Row row = measure(new File(paths.get(i)));
            rows.add(row);
            System.out.println("  " + (i + 1) + "/" + paths.size() + "  " + row.file
                    + "  " + row.dimensions
                    + "  unified=" + (row.unifiedMillis < 0 ? "-" : row.unifiedMillis + " ms")
                    + "  objects=" + (row.objects < 0 ? "-" : Integer.toString(row.objects))
                    + (row.note.isEmpty() ? "" : "  " + row.note));
            System.out.flush();
            // Rewritten after every stack, not once at the end. A run over 11.7 GB
            // of real data is long enough to be interrupted, and an interrupted run
            // that produced nothing wastes the whole measurement.
            write(rows, paths.size());
        }
        write(rows, paths.size());
    }

    private static Row measure(File file) {
        Row row = new Row();
        row.file = file.getName();
        if (!file.isFile()) {
            row.note = "MISSING";
            return row;
        }
        ImagePlus image = null;
        ImagePlus labels = null;
        try {
            try {
                image = FileInfoVirtualStack.openVirtual(file.getAbsolutePath());
            } catch (RuntimeException unopenable) {
                row.note = "could not open virtually: " + unopenable.getClass().getSimpleName();
                return row;
            }
            if (image == null || image.getStack() == null) {
                row.note = "could not open virtually (needs Bio-Formats, or is compressed)";
                return row;
            }
            int planes = image.getStack().getSize();
            row.channels = Math.max(1, image.getNChannels());
            row.frames = Math.max(1, image.getNFrames());
            row.bitDepth = image.getBitDepth();
            int slices = Math.max(1, image.getNSlices());
            row.dimensions = image.getWidth() + "x" + image.getHeight() + "x" + slices;
            row.voxels = (long) image.getWidth() * image.getHeight() * slices;
            if (row.channels > 1 || row.frames > 1) {
                row.dimensions += " of " + planes + " planes";
            }
            row.threshold = isoDataAtCentreSlice(image);
            row.note = depthWarning(file, image, planes);

            try {
                resetPeakHeap();
                long started = System.nanoTime();
                // Channel and frame pinned rather than left to the image's current
                // position, so the row says which volume was measured and a re-run
                // measures the same one.
                OC3DPlusResult result = OC3DPlus.count(image, OC3DPlus.builder()
                        .threshold(row.threshold).channel(1).frame(1).build());
                row.unifiedMillis = (System.nanoTime() - started) / 1000000L;
                row.unifiedPeakBytes = peakHeap();
                labels = result.labelImage();
                row.objects = result.objectCount();
            } catch (OutOfMemoryError memory) {
                row.note = "unified path ran out of heap";
                return row;
            } catch (RuntimeException failed) {
                row.note = "unified path failed: " + failed.getClass().getSimpleName()
                        + ": " + failed.getMessage();
                return row;
            } finally {
                Stacks.discard(labels);
                labels = null;
            }

            measureClassic(row, image);
            return row;
        } finally {
            Stacks.discard(labels);
            Stacks.discard(image);
        }
    }

    /** The before figure, where the classic path can take the input at all. */
    private static void measureClassic(Row row, ImagePlus image) {
        if (!Boolean.getBoolean(CLASSIC_PROPERTY)) {
            row.classicNote = "not run (set -D" + CLASSIC_PROPERTY + "=true)";
            return;
        }
        if (row.channels > 1 || row.frames > 1) {
            row.classicNote = "not applicable: `canUseClassicCounter` rejected multichannel and "
                    + "timelapse input, so this stack never had a classic figure";
            return;
        }
        if (row.bitDepth != 8 && row.bitDepth != 16) {
            row.classicNote = "not applicable: " + row.bitDepth + "-bit";
            return;
        }
        if (row.voxels > CLASSIC_VOXEL_LIMIT) {
            row.classicNote = "not run: " + row.voxels + " voxels needs a "
                    + (row.voxels * 4 / (1024 * 1024)) + " MB flat `int[]` in `Counter3D` "
                    + "before it reads a voxel";
            return;
        }
        try {
            resetPeakHeap();
            long started = System.nanoTime();
            new ObjectsCounter3DWrapper().run(image, row.threshold, 10, Integer.MAX_VALUE,
                    false, false, false, false);
            row.classicMillis = (System.nanoTime() - started) / 1000000L;
            row.classicPeakBytes = peakHeap();
        } catch (OutOfMemoryError memory) {
            row.classicNote = "OutOfMemoryError";
        } catch (RuntimeException failed) {
            row.classicNote = failed.getClass().getSimpleName();
        }
    }

    /**
     * Flags a file whose size implies far more planes than ImageJ's TIFF reader
     * exposed.
     *
     * <p>This matters for reading the table rather than for the engine. The
     * 3.1 GB OME-TIFF in this corpus declares four IFDs and describes the rest of
     * its ~1575 planes in OME-XML, which {@code ij.io.TiffDecoder} does not read,
     * so the run measures four planes - and takes five minutes doing it, because
     * {@code RandomAccessStream.skip} walks the file to reach each one. Without
     * this note the row reads as five minutes for 4.2M voxels and looks like an
     * engine problem, when it is a file-format seek cost on a volume the engine
     * never saw.
     */
    private static String depthWarning(File file, ImagePlus image, int openedPlanes) {
        int bytesPerPixel = Math.max(1, image.getBitDepth() / 8);
        long plane = (long) image.getWidth() * image.getHeight() * bytesPerPixel;
        if (plane <= 0) return "";
        long implied = file.length() / plane;
        if (implied <= openedPlanes * 2L) return "";
        return "**only " + openedPlanes + " of ~" + implied + " planes opened**: the file's IFD "
                + "count under-reports its depth (OME-XML), so this row measures a fraction of "
                + "the volume, and its time is dominated by seeking through "
                + (file.length() / (1024 * 1024)) + " MB rather than by the engine";
    }

    /**
     * The dialog's own default threshold rule, reproduced here because
     * {@code OC3DPlusDialogDefaults} is package-private: IsoData on the middle
     * slice. Using it rather than a fixed number keeps the figures on the volume a
     * user would actually have counted.
     */
    private static int isoDataAtCentreSlice(ImagePlus image) {
        int planes = image.getStack().getSize();
        try {
            double threshold = image.getStack()
                    .getProcessor(Math.max(1, (planes + 1) / 2)).getAutoThreshold();
            if (!Double.isFinite(threshold)) return 128;
            return (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.round(threshold)));
        } catch (RuntimeException unavailable) {
            return 128;
        }
    }

    private static void resetPeakHeap() {
        System.gc();
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        for (int i = 0; i < pools.size(); i++) {
            if (pools.get(i).getType() == MemoryType.HEAP) pools.get(i).resetPeakUsage();
        }
    }

    private static long peakHeap() {
        long total = 0;
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        for (int i = 0; i < pools.size(); i++) {
            MemoryPoolMXBean pool = pools.get(i);
            if (pool.getType() != MemoryType.HEAP || pool.getPeakUsage() == null) continue;
            total += pool.getPeakUsage().getUsed();
        }
        return total;
    }

    private static List<String> readList(File file) throws IOException {
        List<String> out = new ArrayList<String>();
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            out.add(line);
        }
        return out;
    }

    private static void write(List<Row> rows, int total) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# Performance on the real corpus - unified path\n\n");
        if (rows.size() < total) {
            out.append("**PARTIAL: ").append(rows.size()).append(" of ").append(total)
                    .append(" stacks measured so far.** Rewritten after each stack, so this ")
                    .append("file is usable even if the run is interrupted.\n\n");
        }
        out.append("Written by `RealCorpusPerformanceTest` from ")
                .append("`docs/migration/real-corpus.txt`. Every stack opened **virtually**, ")
                .append("threshold from IsoData on the centre slice, `minSize=10`, ")
                .append("channel 1 and frame 1 pinned.\n\n");
        out.append("JVM max heap: ")
                .append(Runtime.getRuntime().maxMemory() / (1024 * 1024))
                .append(" MB. Figures are comparable within this table and not with a ")
                .append("differently-configured JVM.\n\n");
        out.append("**The ~50x memory claim from the parent plan does not hold and is not ")
                .append("resurrected here.** Stage 02 measured about 2x on a 52.4M-voxel ")
                .append("volume, because the label image is a full-volume allocation the ")
                .append("plugin genuinely needs whichever engine produces it.\n\n");
        out.append("Peak heap is the sum of peak usage across the JVM's heap pools after ")
                .append("resetting their counters, so it covers the whole run rather than a ")
                .append("sample of it.\n\n");
        out.append("| # | File | Volume measured | Voxels | Ch | Fr | Thr | Objects "
                + "| Unified time | Unified peak | Classic time | Classic peak | Notes |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        long unifiedTotal = 0;
        int measured = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            out.append("| ").append(i + 1)
                    .append(" | `").append(row.file).append('`')
                    .append(" | ").append(row.dimensions.isEmpty() ? "-" : row.dimensions)
                    .append(" | ").append(row.voxels == 0 ? "-" : Long.toString(row.voxels))
                    .append(" | ").append(row.channels)
                    .append(" | ").append(row.frames)
                    .append(" | ").append(row.threshold)
                    .append(" | ").append(row.objects < 0 ? "-" : Integer.toString(row.objects))
                    .append(" | ").append(row.unifiedMillis < 0
                            ? "-" : row.unifiedMillis + " ms")
                    .append(" | ").append(row.unifiedPeakBytes < 0
                            ? "-" : (row.unifiedPeakBytes / (1024 * 1024)) + " MB")
                    .append(" | ").append(row.classicMillis < 0
                            ? row.classicNote : row.classicMillis + " ms")
                    .append(" | ").append(row.classicPeakBytes < 0
                            ? "-" : (row.classicPeakBytes / (1024 * 1024)) + " MB")
                    .append(" | ").append(row.note.isEmpty() ? "" : row.note)
                    .append(" |\n");
            if (row.unifiedMillis >= 0) {
                unifiedTotal += row.unifiedMillis;
                measured++;
            }
        }
        out.append("\n**").append(measured).append(" of ").append(rows.size())
                .append(" attempted stacks completed on the unified path, ")
                .append(unifiedTotal / 1000).append(" s in total.**\n\n");
        out.append("The classic column is opt-in (`-D").append(CLASSIC_PROPERTY)
                .append("=true`) because `Counter3D` is not merely slower but ")
                .append("unpredictably slower: `replaceID` was still running after 15 minutes ")
                .append("of CPU on the 81.8M-voxel `MCG_04` stack, against the 107 s Stage 02 ")
                .append("measured on 52.4M voxels - 1.6x the volume for over 8x the time, and ")
                .append("unfinished. Left on by default, one stack consumes the whole run.\n\n");
        out.append("A `Ch` or `Fr` above 1 is a hyperstack: the volume measured is one channel ")
                .append("and one frame of it, which is what the `Volume measured` column ")
                .append("reports. Before Stage 03 the plugin measured the first `nSlices` ")
                .append("planes of the whole stack instead, so for those rows the old timing ")
                .append("would not have been measuring the same thing.\n");
        File parent = REPORT.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        Files.write(REPORT.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + REPORT.getAbsolutePath());
    }
}
