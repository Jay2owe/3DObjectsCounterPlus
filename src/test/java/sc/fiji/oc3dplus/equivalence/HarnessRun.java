package sc.fiji.oc3dplus.equivalence;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the whole corpus, once, and returns the records grouped by golden file.
 *
 * <p>Used by both the capture and the comparison, so a golden and a candidate can
 * never be produced by two different traversals.
 */
public final class HarnessRun {

    /** Comma-separated fixture names to restrict the run to, for iteration. */
    private static final String SUBSET_PROPERTY = "oc3dplus.harness.fixtures";

    private HarnessRun() {}

    /** Receives one golden file's records at a time, so nothing has to be retained. */
    public interface Visitor {
        void visit(String goldenName, List<CaptureRecord> records) throws IOException;
    }

    /**
     * The names of the golden files this run covers, in traversal order. Computed
     * without running anything.
     */
    public static List<String> goldenNames() {
        List<String> out = new ArrayList<String>();
        List<String> subset = subset();
        List<Fixture> fixtures = FixtureCorpus.all();
        for (int i = 0; i < fixtures.size(); i++) {
            String name = fixtures.get(i).name;
            if (subset.isEmpty() || subset.contains(name)) out.add(name);
        }
        List<BatchCapture.Scenario> scenarios = BatchCapture.scenarios();
        for (int i = 0; i < scenarios.size(); i++) {
            String name = scenarios.get(i).recordName();
            if (subset.isEmpty() || subset.contains(name)) out.add(name);
        }
        return out;
    }

    /**
     * Streams the corpus one golden file at a time.
     *
     * <p>Streaming rather than collecting is not a refinement: the object-count
     * ladder reaches 65,536 objects, and holding two complete runs of the corpus in
     * memory at once exhausted the heap. The determinism check therefore compares
     * one fixture's two runs and releases them before moving on.
     */
    public static void forEachGolden(File batchRoot, Visitor visitor) throws IOException {
        List<String> subset = subset();
        List<Fixture> fixtures = FixtureCorpus.all();
        for (int i = 0; i < fixtures.size(); i++) {
            Fixture fixture = fixtures.get(i);
            if (!subset.isEmpty() && !subset.contains(fixture.name)) continue;
            visitor.visit(fixture.name, capture(fixture));
        }
        if (batchRoot == null) return;
        List<BatchCapture.Scenario> scenarios = BatchCapture.scenarios();
        for (int i = 0; i < scenarios.size(); i++) {
            BatchCapture.Scenario scenario = scenarios.get(i);
            if (!subset.isEmpty() && !subset.contains(scenario.recordName())) continue;
            visitor.visit(scenario.recordName(),
                    Collections.singletonList(BatchCapture.capture(scenario, batchRoot)));
        }
    }

    /** Every configuration of one fixture. */
    public static List<CaptureRecord> capture(Fixture fixture) {
        List<RunConfig> configs = ConfigSweep.forFixture(fixture);
        List<CaptureRecord> records = new ArrayList<CaptureRecord>(configs.size());
        for (int c = 0; c < configs.size(); c++) {
            records.add(Capture.capture(fixture, configs.get(c)));
        }
        return records;
    }

    /** One golden file's records by name, for the streaming callers that need a lookup. */
    public static List<CaptureRecord> captureNamed(String goldenName, File batchRoot)
            throws IOException {
        List<BatchCapture.Scenario> scenarios = BatchCapture.scenarios();
        for (int i = 0; i < scenarios.size(); i++) {
            if (scenarios.get(i).recordName().equals(goldenName)) {
                return Collections.singletonList(
                        BatchCapture.capture(scenarios.get(i), batchRoot));
            }
        }
        return capture(FixtureCorpus.byName(goldenName));
    }

    public static Map<String, List<CaptureRecord>> captureAll(File batchRoot) throws IOException {
        final Map<String, List<CaptureRecord>> out =
                new LinkedHashMap<String, List<CaptureRecord>>();
        forEachGolden(batchRoot, new Visitor() {
            @Override public void visit(String goldenName, List<CaptureRecord> records) {
                out.put(goldenName, records);
            }
        });
        return out;
    }

    /** Flat list in traversal order. */
    public static List<CaptureRecord> flatten(Map<String, List<CaptureRecord>> grouped) {
        List<CaptureRecord> out = new ArrayList<CaptureRecord>();
        for (Map.Entry<String, List<CaptureRecord>> entry : grouped.entrySet()) {
            out.addAll(entry.getValue());
        }
        return out;
    }

    /** Coverage summary, including what any reduced sweep left out. */
    public static List<String> coverage() {
        List<String> lines = new ArrayList<String>();
        List<Fixture> fixtures = FixtureCorpus.all();
        int records = 0;
        for (int i = 0; i < fixtures.size(); i++) {
            Fixture fixture = fixtures.get(i);
            int configs = ConfigSweep.forFixture(fixture).size();
            records += configs;
            List<String> omitted = fixture.harnessCase == HarnessCase.C
                    ? Collections.<String>emptyList()
                    : ConfigSweep.omittedBy(fixture.sweep);
            lines.add(fixture.name + " case=" + fixture.harnessCase
                    + " sweep=" + fixture.sweep + " configs=" + configs
                    + (omitted.isEmpty() ? ""
                            : " NOT RUN: " + ColumnContract.join(omitted, ",")));
        }
        lines.add("fixtures=" + fixtures.size()
                + " batchScenarios=" + BatchCapture.scenarios().size()
                + " records=" + (records + BatchCapture.scenarios().size()));
        return lines;
    }

    private static List<String> subset() {
        String configured = System.getProperty(SUBSET_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        String[] parts = configured.split(",");
        for (int i = 0; i < parts.length; i++) {
            String name = parts[i].trim();
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }
}
