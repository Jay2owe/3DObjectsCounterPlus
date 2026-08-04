package sc.fiji.oc3dplus.equivalence;

import ij.io.FileInfo;
import ij.io.TiffDecoder;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Reports the dimensions of nominated real stacks without opening them, so the
 * real-data corpus can be assembled from facts rather than from file sizes.
 *
 * <pre>
 * mvn -o -B test -Dtest=RealCorpusProbeTest -Doc3dplus.realCorpus=docs/migration/real-corpus.txt
 * </pre>
 *
 * <p>The list is one path per line; blank lines and {@code #} comments are ignored.
 * Only headers are read, so a 17 GB stack costs milliseconds.
 *
 * <p>Two of the release-note claims depend on what this finds:
 *
 * <ul>
 *   <li>The 2<sup>31</sup>-voxel ceiling was removed <b>by inspection only</b> - no
 *       flat {@code int length = w*h*d} exists - and has never been tested. It can
 *       only be claimed if a stack above 2,147,483,647 voxels exists to run.</li>
 *   <li>A stack that fails today with {@code NegativeArraySizeException} is the
 *       most persuasive before/after in the release notes, and needs identifying
 *       rather than assuming.</li>
 * </ul>
 *
 * <p>If no such stack is in the corpus, the corresponding claim gets dropped. The
 * probe exists so that decision is made on evidence.
 *
 * <p>Reads TIFF headers only. Bio-Formats formats such as {@code .lif} need the
 * Bio-Formats reader, which is not on this test classpath; those are reported as
 * unreadable here rather than silently skipped.
 */
public class RealCorpusProbeTest {

    private static final String LIST_PROPERTY = "oc3dplus.realCorpus";
    private static final long INT_VOXEL_CEILING = 2147483647L;

    @Test
    public void probeNominatedStacks() throws Exception {
        String configured = System.getProperty(LIST_PROPERTY);
        Assume.assumeTrue("set -D" + LIST_PROPERTY + "=<file or semicolon-separated paths> "
                        + "to probe the real corpus",
                configured != null && !configured.trim().isEmpty());

        List<String> paths = resolve(configured.trim());
        System.out.println("=== real corpus probe: " + paths.size() + " path(s) ===");
        int aboveCeiling = 0;
        for (int i = 0; i < paths.size(); i++) {
            String line = describe(paths.get(i));
            System.out.println(line);
            if (line.contains("ABOVE-2^31")) aboveCeiling++;
        }
        System.out.println("stacks above the 2^31-voxel ceiling: " + aboveCeiling
                + (aboveCeiling == 0
                        ? " - the ceiling-removal claim cannot be tested from this list"
                        : " - the ceiling-removal claim is testable"));
    }

    private static List<String> resolve(String configured) throws Exception {
        File asFile = new File(configured);
        List<String> out = new ArrayList<String>();
        if (asFile.isFile()) {
            List<String> lines = Files.readAllLines(asFile.toPath(), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                out.add(line);
            }
            return out;
        }
        String[] parts = configured.split(";");
        for (int i = 0; i < parts.length; i++) {
            String path = parts[i].trim();
            if (!path.isEmpty()) out.add(path);
        }
        return out;
    }

    private static String describe(String path) {
        File file = new File(path);
        if (!file.isFile()) return "  MISSING  " + path;
        String name = file.getName();
        long megabytes = file.length() / (1024 * 1024);
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".tif") && !lower.endsWith(".tiff")) {
            return "  NEEDS-BIO-FORMATS  " + megabytes + " MB  " + name
                    + "  (header not readable by ij.io.TiffDecoder)";
        }
        try {
            TiffDecoder decoder = new TiffDecoder(file.getParent(), name);
            FileInfo[] info = decoder.getTiffInfo();
            if (info == null || info.length == 0) {
                return "  UNREADABLE  " + megabytes + " MB  " + name;
            }
            int width = info[0].width;
            int height = info[0].height;
            int bytesPerPixel = info[0].getBytesPerPixel();
            int ifds = info.length;
            int declaredImages = info[0].nImages;
            int slices = ifds == 1 && declaredImages > 1 ? declaredImages : ifds;
            long voxels = (long) width * height * slices;

            // Cross-check against the file size. An OME-TIFF can describe its plane
            // count in OME-XML rather than in one IFD per plane, so the IFD count
            // can badly under-report the stack. Reporting a confidently wrong depth
            // would be worse than reporting the disagreement.
            long voxelsFromSize = bytesPerPixel > 0
                    ? file.length() / bytesPerPixel : 0;
            long impliedSlices = (long) width * height > 0
                    ? voxelsFromSize / ((long) width * height) : 0;
            boolean disagrees = impliedSlices > slices * 2L;

            long reportedVoxels = disagrees ? voxelsFromSize : voxels;
            String flag = reportedVoxels > INT_VOXEL_CEILING ? "  ABOVE-2^31" : "";
            return (disagrees ? "  CHECK" : "  OK") + "  " + megabytes + " MB  "
                    + width + "x" + height + "x" + slices
                    + "  voxels=" + voxels
                    + "  type=" + bytesPerPixel + "B/px"
                    + (disagrees
                            ? "  size implies ~" + impliedSlices + " planes (~" + voxelsFromSize
                              + " voxels): IFD count under-reports, open it to confirm"
                            : "")
                    + flag + "  " + name;
        } catch (Exception unreadable) {
            return "  UNREADABLE  " + megabytes + " MB  " + name
                    + "  (" + unreadable.getClass().getSimpleName() + ": "
                    + unreadable.getMessage() + ")";
        }
    }
}
