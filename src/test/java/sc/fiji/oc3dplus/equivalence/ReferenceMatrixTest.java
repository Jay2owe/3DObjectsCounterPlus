package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;
import ij.measure.ResultsTable;
import org.junit.Test;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusResult;
import sc.fiji.oc3dplus.engine.ObjectsCounter3DWrapper;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Asserts the reference matrix of harness section 2 is real, rather than taking
 * each fixture's declared case on trust.
 *
 * <p>The routing is not directly observable, but it leaves a fingerprint: the
 * classic {@code Counter3D} path emits a {@code Median} column
 * (from {@code Utilities.Object3D.median}) and the mcib3d path does not. So the
 * presence of {@code Median} distinguishes Case A from Case B on the current
 * build.
 *
 * <p>That fingerprint is deliberately kept out of the golden records. It
 * disappears in Stage 03 by construction - no accumulator computes a median - and
 * recording it would turn one finding into a diff on every Case A record. The
 * column-set comparison in {@link Differ} reports the loss once, which is the
 * right number of times.
 */
public class ReferenceMatrixTest {

    private static final String CLASSIC_PATH_MARKER = "Median";

    @Test
    public void bothGplReferencesArePresent() {
        assertTrue("mcib3d-core must be on the test classpath to capture the Case B and C "
                        + "references; it is removed in Stage 04, not here",
                ObjectsCounter3DWrapper.isMcib3dAvailable());
        try {
            Class.forName("Utilities.Counter3D");
        } catch (ClassNotFoundException missing) {
            fail("sc.fiji:3D_Objects_Counter must be on the test classpath to capture the "
                    + "Case A reference");
        }
    }

    @Test
    public void caseAFixturesTakeTheClassicPath() {
        List<Fixture> fixtures = FixtureCorpus.all();
        for (int i = 0; i < fixtures.size(); i++) {
            Fixture fixture = fixtures.get(i);
            if (fixture.harnessCase != HarnessCase.A) continue;
            if (isExpectedToThrow(fixture)) continue;
            String columns = columnsOf(fixture);
            if (columns == null) continue;
            assertTrue("fixture '" + fixture.name + "' is declared Case A but produced no "
                            + CLASSIC_PATH_MARKER + " column, so it did not take the classic "
                            + "path. Columns: " + columns,
                    columns.contains(CLASSIC_PATH_MARKER));
        }
    }

    @Test
    public void caseBFixturesTakeTheNativePath() {
        List<Fixture> fixtures = FixtureCorpus.all();
        for (int i = 0; i < fixtures.size(); i++) {
            Fixture fixture = fixtures.get(i);
            if (fixture.harnessCase != HarnessCase.B) continue;
            String columns = columnsOf(fixture);
            if (columns == null) continue;
            assertTrue("fixture '" + fixture.name + "' is declared Case B but produced a "
                            + CLASSIC_PATH_MARKER + " column, so it took the classic path. "
                            + "Columns: " + columns,
                    !columns.contains(CLASSIC_PATH_MARKER));
        }
    }

    /**
     * The Morph_* columns are already computed by {@code LabelFeatureAccumulator}
     * on both non-label paths today, which is why TOLERANCES.md puts them in Tier
     * 1 rather than Tier 2 for Cases A and B. If that ever stops being true the
     * tier assignment is wrong, so it is asserted rather than assumed.
     */
    @Test
    public void morphologyColumnsArePresentOnBothDetectionPaths() {
        String caseA = columnsOf(FixtureCorpus.byName("blobs-8bit"));
        String caseB = columnsOf(FixtureCorpus.byName("blobs-32bit"));
        String[] expected = {
                "Morph_Sphericity", "Morph_Compactness", "Morph_Elongation", "Morph_Feret3D_um"
        };
        for (int i = 0; i < expected.length; i++) {
            assertTrue("Case A is missing " + expected[i], caseA.contains(expected[i]));
            assertTrue("Case B is missing " + expected[i], caseB.contains(expected[i]));
        }
    }

    /** Case C is reachable only through the public wrapper: no production caller exists. */
    @Test
    public void caseCMeasuresASuppliedLabelImage() {
        Fixture fixture = FixtureCorpus.byName("label-simple");
        ImagePlus labels = fixture.createInput();
        ObjectsCounter3DWrapper.Result result = null;
        try {
            result = new ObjectsCounter3DWrapper()
                    .fromLabelImage(labels, null, 0, Integer.MAX_VALUE, true, false);
            assertEquals("three labelled objects", 3, result.getStatistics().size());
        } finally {
            if (result != null) {
                Stacks.discard(result.getObjectsMap());
                Stacks.discard(result.getMaskedImage());
            }
            Stacks.discard(labels);
        }
    }

    private static boolean isExpectedToThrow(Fixture fixture) {
        return fixture.note().startsWith("KNOWN FAILING");
    }

    /** Column list for the default configuration, or null when nothing was detected. */
    private static String columnsOf(Fixture fixture) {
        ImagePlus input = fixture.createInput();
        ImagePlus labels = null;
        try {
            OC3DPlusResult result = OC3DPlus.count(input, OC3DPlus.builder()
                    .threshold(100).minSize(1).build());
            labels = result.labelImage();
            ResultsTable statistics = result.statistics();
            if (statistics == null || statistics.size() == 0) return null;
            String[] headings = statistics.getHeadings();
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < headings.length; i++) {
                if (i > 0) out.append(',');
                out.append(headings[i]);
            }
            return out.toString();
        } finally {
            Stacks.discard(labels);
            Stacks.discard(input);
        }
    }
}
