package sc.fiji.oc3dplus.equivalence;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the golden set.
 *
 * <p><b>The golden set is immutable once captured.</b> It is captured from the
 * named pre-migration build and never regenerated to make a diff go away: a
 * golden later found to be wrong is a bug report against the shipped plugin and
 * is fixed as its own change with its own release note.
 *
 * <p><b>Layout, and a deliberate deviation.</b> Harness section 7 suggests
 * {@code golden/<sha>/<fixture>/<config>/}. This store writes one file per
 * fixture, {@code golden/<sha>/<fixture>.golden}, holding every configuration for
 * that fixture in order. A diff still points at exactly one (fixture, config,
 * key), because every line carries all three. The reason for collapsing the
 * per-config directories is concrete rather than aesthetic: the corpus is ~490
 * records, and the directory form would add ~490 directories and files inside a
 * Dropbox-synced working tree. Dropbox dehydrating many small files into
 * unreadable placeholders is exactly what took this repository out of action
 * before P0, and every extra file is also a git object. Fewer, larger text files
 * are the safer shape here and lose nothing diagnostically.
 */
public final class GoldenStore {

    /**
     * The pre-migration build the goldens describe:
     * {@code chore: checkpoint the current build before capturing migration goldens}.
     * A constant rather than a git call, because the goldens are immutable and
     * must keep naming this build no matter what HEAD later becomes.
     */
    public static final String BUILD_SHA = "e6d0e2e";

    private static final String DIRECTORY_PROPERTY = "oc3dplus.goldenDir";
    private static final String EXTENSION = ".golden";

    private GoldenStore() {}

    public static File directory() {
        String configured = System.getProperty(DIRECTORY_PROPERTY);
        if (configured != null && !configured.trim().isEmpty()) {
            return new File(configured.trim());
        }
        return new File(new File("golden"), BUILD_SHA);
    }

    public static File fileFor(String fixtureName) {
        return new File(directory(), fixtureName + EXTENSION);
    }

    public static boolean exists(String fixtureName) {
        return fileFor(fixtureName).isFile();
    }

    /** True when no golden at all has been captured yet. */
    public static boolean isEmpty() {
        File directory = directory();
        if (!directory.isDirectory()) return true;
        String[] names = directory.list();
        return names == null || names.length == 0;
    }

    public static void write(String fixtureName, List<CaptureRecord> records) throws IOException {
        writeText(fixtureName, serialise(records), records.size());
    }

    /** Writes already-serialised records, so a caller can release them first. */
    public static void writeText(String fixtureName, String serialised, int recordCount)
            throws IOException {
        File directory = directory();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create golden directory " + directory.getAbsolutePath());
        }
        StringBuilder out = new StringBuilder();
        out.append("# ").append(CaptureRecord.FORMAT).append('\n');
        out.append("# build=").append(BUILD_SHA).append(" fixture=").append(fixtureName)
                .append(" records=").append(recordCount).append('\n');
        out.append("# Immutable. Never regenerate a golden to make a diff disappear.\n");
        out.append(serialised);
        Files.write(fileFor(fixtureName).toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static List<CaptureRecord> read(String fixtureName) throws IOException {
        File file = fileFor(fixtureName);
        if (!file.isFile()) return null;
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return CaptureRecord.parse(text);
    }

    /** Serialised form, used by the determinism check. */
    public static String serialise(List<CaptureRecord> records) {
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < records.size(); i++) {
            lines.addAll(records.get(i).toLines());
        }
        return join(lines);
    }

    private static String join(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            out.append(lines.get(i)).append('\n');
        }
        return out.toString();
    }
}
