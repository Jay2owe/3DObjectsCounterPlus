package sc.fiji.oc3dplus.equivalence;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.fail;

/**
 * <b>The ship gate.</b> Runs the corpus against the current build and applies the
 * tier contract to every golden.
 *
 * <p>Tier 1 differences fail. Tier 2 differences fail only outside their declared
 * bound. Tier 3 differences are printed for sign-off and do not fail, because they
 * are known algorithmic changes - but they are never silent.
 *
 * <p>The Tier 2 delta table is printed on every run, pass or fail, because it is a
 * required deliverable of each migration stage and not merely diagnostic output.
 */
public class EquivalenceHarnessTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void currentBuildMatchesGoldens() throws Exception {
        Assume.assumeFalse("no golden set at " + GoldenStore.directory().getAbsolutePath()
                        + "; run CaptureGoldensTest against the pre-migration build first",
                GoldenStore.isEmpty());

        final Differ.Report report = new Differ.Report();
        final List<String> missing = new ArrayList<String>();
        // Streamed one golden at a time: the corpus does not fit in memory twice.
        HarnessRun.forEachGolden(temporary.newFolder("batch"), new HarnessRun.Visitor() {
            @Override public void visit(String goldenName, List<CaptureRecord> records)
                    throws IOException {
                List<CaptureRecord> goldens = GoldenStore.read(goldenName);
                if (goldens == null) {
                    missing.add(goldenName);
                    return;
                }
                Differ.Report perFixture = Differ.diff(goldens, records);
                report.findings.addAll(perFixture.findings);
                for (Differ.Delta delta : perFixture.deltaTable()) {
                    Differ.Delta target = report.delta(delta.harnessCase, delta.column);
                    target.compared += delta.compared;
                    target.nonZero += delta.nonZero;
                    target.outsideBound += delta.outsideBound;
                    target.relative.addAll(delta.relative);
                }
            }
        });

        System.out.println("=== Tier 2 delta table (relative differences) ===");
        List<Differ.Delta> deltas = report.deltaTable();
        for (int i = 0; i < deltas.size(); i++) {
            System.out.println("  " + deltas.get(i).describe());
        }

        List<Differ.Finding> tier3 = report.tier(3);
        if (!tier3.isEmpty()) {
            System.out.println("=== Tier 3, requires written sign-off (" + tier3.size() + ") ===");
            for (int i = 0; i < Math.min(tier3.size(), 50); i++) {
                System.out.println("  " + tier3.get(i));
            }
            if (tier3.size() > 50) {
                System.out.println("  ... " + (tier3.size() - 50) + " more not printed");
            }
        }

        StringBuilder failure = new StringBuilder();
        if (!missing.isEmpty()) {
            failure.append("no golden file for: ")
                    .append(ColumnContract.join(missing, ", ")).append('\n');
        }
        appendFindings(failure, "TIER 1", report.tier(1));
        appendFindings(failure, "TIER 2 outside declared bound (or a Tier 2 mismatch with no bound, "
                + "such as a column reorder)", report.tier(2));
        if (failure.length() > 0) {
            fail("Equivalence harness failed (" + report.summarise() + "):\n" + failure);
        }
    }

    private static void appendFindings(StringBuilder out, String label, List<Differ.Finding> findings) {
        if (findings.isEmpty()) return;
        out.append(label).append(" (").append(findings.size()).append("):\n");
        for (int i = 0; i < Math.min(findings.size(), 60); i++) {
            out.append("  ").append(findings.get(i)).append('\n');
        }
        if (findings.size() > 60) {
            out.append("  ... ").append(findings.size() - 60).append(" more\n");
        }
    }
}
