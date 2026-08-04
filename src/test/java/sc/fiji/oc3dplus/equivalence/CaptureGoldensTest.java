package sc.fiji.oc3dplus.equivalence;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Writes the golden set. Deliberately not part of an ordinary build: run it once,
 * against the pre-migration build, with
 *
 * <pre>
 * mvn -o -B test -Dtest=CaptureGoldensTest -Doc3dplus.captureGoldens=true
 * </pre>
 *
 * <p>It runs the entire corpus <b>twice</b> and refuses to write anything unless
 * the two runs are byte-identical. A flaky harness certifies nothing, so
 * determinism is enforced at the moment of capture rather than checked later.
 *
 * <p>It also refuses to overwrite an existing golden set. Goldens are immutable;
 * regenerating them to make a diff disappear is the failure mode the harness
 * exists to prevent.
 */
public class CaptureGoldensTest {

    private static final String ENABLE_PROPERTY = "oc3dplus.captureGoldens";
    /** Set only when a golden set is being replaced on purpose, with a reason. */
    private static final String OVERWRITE_PROPERTY = "oc3dplus.overwriteGoldens";

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void captureTwiceThenWrite() throws Exception {
        Assume.assumeTrue("set -D" + ENABLE_PROPERTY + "=true to capture goldens",
                Boolean.getBoolean(ENABLE_PROPERTY));

        if (!GoldenStore.isEmpty() && !Boolean.getBoolean(OVERWRITE_PROPERTY)) {
            throw new IllegalStateException("A golden set already exists at "
                    + GoldenStore.directory().getAbsolutePath()
                    + ". Goldens are immutable: a golden found to be wrong is a bug report "
                    + "against the shipped plugin, fixed as its own change. Set -D"
                    + OVERWRITE_PROPERTY + "=true only if you are replacing the set deliberately.");
        }

        List<String> coverage = HarnessRun.coverage();
        for (int i = 0; i < coverage.size(); i++) {
            System.out.println("coverage: " + coverage.get(i));
        }

        File batchRootFirst = temporary.newFolder("batch-run-1");
        File batchRootSecond = temporary.newFolder("batch-run-2");
        List<String> names = HarnessRun.goldenNames();
        long started = System.currentTimeMillis();
        int written = 0;
        int records = 0;

        // One fixture at a time, both runs, then released. Collecting the whole
        // corpus twice exhausted the heap on the 65,536-object ladder.
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            List<CaptureRecord> firstRun = HarnessRun.captureNamed(name, batchRootFirst);
            int count = firstRun.size();
            String serialisedFirst = GoldenStore.serialise(firstRun);
            firstRun = null;

            String serialisedSecond =
                    GoldenStore.serialise(HarnessRun.captureNamed(name, batchRootSecond));
            if (!serialisedFirst.equals(serialisedSecond)) {
                File dumped = dump(name, serialisedFirst, serialisedSecond);
                assertEquals("harness is not deterministic for fixture " + name
                                + "; refusing to capture a golden from it. Both runs written to "
                                + dumped.getAbsolutePath() + ". First differing line: "
                                + firstDifference(serialisedFirst, serialisedSecond),
                        serialisedFirst, serialisedSecond);
            }
            GoldenStore.writeText(name, serialisedFirst, count);
            written++;
            records += count;
        }

        System.out.println("captured " + written + " golden files (" + records + " records) in "
                + (System.currentTimeMillis() - started) + " ms to "
                + GoldenStore.directory().getAbsolutePath());
        assertFalse("expected at least one golden file", written == 0);
    }

    /**
     * Both runs of a non-deterministic fixture, on disk. A determinism failure is
     * hard to read from an assertion message and impossible to re-derive later, so
     * the evidence is kept.
     */
    private static File dump(String fixture, String first, String second) throws Exception {
        File directory = new File("target", "harness-determinism");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new java.io.IOException("Could not create " + directory);
        }
        Files.write(new File(directory, fixture + ".run1").toPath(),
                first.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(directory, fixture + ".run2").toPath(),
                second.getBytes(StandardCharsets.UTF_8));
        return directory;
    }

    private static String firstDifference(String first, String second) {
        String[] a = first.split("\\R", -1);
        String[] b = second.split("\\R", -1);
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (!a[i].equals(b[i])) {
                return "line " + (i + 1) + "\n  run1: " + a[i] + "\n  run2: " + b[i];
            }
        }
        return "line counts differ: run1=" + a.length + " run2=" + b.length;
    }
}
