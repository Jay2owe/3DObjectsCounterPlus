package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;
import ij.measure.ResultsTable;
import org.junit.Test;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusResult;
import sc.fiji.oc3dplus.engine.ObjectsCounter3DWrapper;
import sc.fiji.oc3dplus.engine.ReferenceEngines;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Enumerates every object the two engines disagree about under
 * {@code excludeOnEdges} - the one object-set difference Stage 03 declares - and
 * classifies each one by whether it actually touches a border.
 *
 * <p>The plan names this as a deliverable rather than a gate, and for a reason:
 * "some objects differ" is not a sign-off. What can be signed off is a list in
 * which every entry is accounted for.
 *
 * <p><b>What the enumeration found, which is not what was expected.</b>
 * TOLERANCES.md §4 declared the difference in one direction, from the Stage 2
 * reading of the bytecode:
 *
 * <blockquote>{@code Counter3D.findObjects()} records edge contact against
 * whichever provisional id a voxel carries when its second pass reaches it, and
 * {@code replaceID} does not carry that flag across a later merge. An object whose
 * only edge contact is labelled under an id that merges away afterwards keeps no
 * flag and survives a filter it should fail.</blockquote>
 *
 * <p>That predicts {@code Counter3D} keeping an edge-touching object. Across all
 * 33 Case A fixtures and a seeded sweep of random volumes, <b>no such object was
 * found</b>. The disagreements that were found run the other way: the new engine
 * keeps an object that does not touch any border and {@code Counter3D} drops it -
 * a false negative, not a false positive. Every one traced to the same
 * final-voxel off-by-one already pinned by {@link Counter3DDefectTest}, which
 * under {@code excludeOnEdges} corrupts a result instead of throwing.
 * {@link #theFinalVoxelIsWhatBreaksIt} isolates that to scan-order finality
 * rather than to edge-ness.
 *
 * <p>Both directions are therefore accepted here and reported separately. What is
 * <em>not</em> accepted is a difference in which the object's position does not
 * explain which engine kept it - that is neither known defect, and it fails.
 *
 * <p>Objects are matched between engines by bounding box and voxel count rather
 * than by label, because a differing object set renumbers everything after it.
 */
public class ExcludeOnEdgesEnumerationTest {

    private static final int THRESHOLD = 100;
    /** Seeded, so the report describes a fixed set of volumes and not a lottery. */
    private static final long SWEEP_SEED = 20260805L;
    /**
     * How many random volumes to sweep. The first disagreement in this sequence is
     * volume 732 and the second is 1348, so the deliverable run uses 1500:
     *
     * <pre>
     * mvn -o -B test -Dtest=ExcludeOnEdgesEnumerationTest -Doc3dplus.edgeSweep=1500
     * </pre>
     *
     * <p>The default is smaller because {@code Counter3D} costs about 25 ms per
     * volume and the everyday suite should not pay four minutes for evidence that
     * was gathered once. A short sweep therefore finds nothing, and the report says
     * how many volumes it looked at so that "0 differences" can never be read as
     * "no differences exist" - the standing evidence is
     * {@link #theSmallestDisagreementIsTwoVoxels}, which runs every time.
     */
    private static final int SWEEP_VOLUMES =
            Integer.getInteger("oc3dplus.edgeSweep", 200).intValue();

    private static final File REPORT =
            new File("target" + File.separator + "equivalence",
                    "exclude-on-edges-differences.md");

    /** One object, described by what survives renumbering. */
    private static final class Box {
        final int x;
        final int y;
        final int z;
        final int width;
        final int height;
        final int depth;
        final long voxels;

        Box(int x, int y, int z, int width, int height, int depth, long voxels) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.voxels = voxels;
        }

        String key() {
            return x + "," + y + "," + z + " " + width + "x" + height + "x" + depth
                    + " n=" + voxels;
        }

        /**
         * Whether the box reaches a border of a {@code w x h x d} volume. The z
         * faces count only when the stack is genuinely 3D, which is the rule both
         * engines apply.
         */
        boolean touchesEdge(int w, int h, int d) {
            if (x <= 0 || y <= 0) return true;
            if (x + width >= w || y + height >= h) return true;
            return d > 1 && (z <= 0 || z + depth >= d);
        }

        @Override public String toString() {
            return key();
        }
    }

    /** Running tally, so the corpus pass and the random sweep report the same way. */
    private static final class Tally {
        final List<String> rows = new ArrayList<String>();
        final List<String> unexplained = new ArrayList<String>();
        int compared;
        int differing;
        /** Counter3D kept an object that touches a border - the declared direction. */
        int counter3dKeptAnEdgeObject;
        /** The new engine kept an interior object Counter3D dropped. */
        int counter3dDroppedAnInteriorObject;

        void classify(String source, ImagePlus input,
                      Map<String, Box> classic, Map<String, Box> unified) {
            int w = input.getWidth();
            int h = input.getHeight();
            int d = input.getStack().getSize();
            List<Box> classicOnly = missingFrom(classic, unified);
            List<Box> unifiedOnly = missingFrom(unified, classic);
            if (classicOnly.isEmpty() && unifiedOnly.isEmpty()) return;
            differing++;

            for (int i = 0; i < classicOnly.size(); i++) {
                Box box = classicOnly.get(i);
                boolean touches = box.touchesEdge(w, h, d);
                if (touches) counter3dKeptAnEdgeObject++;
                rows.add(row(source, w, h, d, "Counter3D", box, touches
                        ? "touches a border, so `excludeOnEdges` should have dropped it: the "
                          + "declared merge-loses-the-flag direction"
                        : "**does not touch a border, and Counter3D kept it while the new "
                          + "engine dropped it** - neither known defect"));
                if (!touches) {
                    unexplained.add(source + ": Counter3D kept " + box.key()
                            + " and the new engine dropped it, but it does not touch a border, "
                            + "so neither known defect explains it");
                }
            }
            for (int i = 0; i < unifiedOnly.size(); i++) {
                Box box = unifiedOnly.get(i);
                boolean touches = box.touchesEdge(w, h, d);
                if (!touches) counter3dDroppedAnInteriorObject++;
                rows.add(row(source, w, h, d, "StreamingLabeller", box, touches
                        ? "**touches a border, and the new engine kept it** - the option says "
                          + "it should be dropped"
                        : "does not touch a border, so Counter3D dropped an object it should "
                          + "have kept: the final-voxel defect"));
                if (touches) {
                    unexplained.add(source + ": StreamingLabeller kept " + box.key()
                            + ", which touches a border, and excludeOnEdges says it must not");
                }
            }
        }

        private static String row(String source, int w, int h, int d,
                                  String keptBy, Box box, String verdict) {
            return "| `" + source + "` | " + w + "x" + h + "x" + d + " | " + keptBy
                    + " | `" + box.key() + "` | " + verdict + " |";
        }
    }

    @Test
    public void everyDisagreementIsOneOfTheTwoKnownDefects() throws Exception {
        Tally tally = new Tally();
        List<Fixture> fixtures = FixtureCorpus.all();
        for (int i = 0; i < fixtures.size(); i++) {
            Fixture fixture = fixtures.get(i);
            // Case B and C never reached the classic edge filter, so there is no
            // pair to compare: B went to mcib3d and C takes a label image, which
            // carries no edge option at all.
            if (fixture.harnessCase != HarnessCase.A) continue;
            ImagePlus input = fixture.createInput();
            try {
                compare(tally, fixture.name, input);
            } finally {
                Stacks.discard(input);
            }
        }
        int corpusCompared = tally.compared;
        int corpusDiffering = tally.differing;

        // The corpus is built from shapes chosen to discriminate connectivity and
        // geometry, not to stress the edge filter, and it produces no difference at
        // all. A deliverable that read "0 differences" on that basis alone would be
        // reporting the corpus's blind spot as a property of the engines.
        Random random = new Random(SWEEP_SEED);
        for (int volume = 0; volume < SWEEP_VOLUMES; volume++) {
            ImagePlus input = randomVolume(random, volume);
            try {
                compare(tally, "sweep-" + volume, input);
            } finally {
                Stacks.discard(input);
            }
        }

        write(tally, corpusCompared, corpusDiffering);

        assertTrue("excludeOnEdges differences that neither known defect explains:\n  "
                + ColumnContract.join(tally.unexplained, "\n  "), tally.unexplained.isEmpty());
    }

    /**
     * The smallest disagreement the sweep can be reduced to: two voxels.
     *
     * <p>One interior voxel, plus the voxel at {@code (w-1, h-1, d-1)} - the last
     * one in scan order. {@code Counter3D} drops <em>both</em>; the new engine
     * drops the corner and keeps the interior voxel, which is the right answer.
     *
     * <p>The interior voxel is nowhere near a border, so the loss is not an edge
     * decision at all. It is the {@code IDcount} off-by-one of
     * {@link Counter3DDefectTest}: with {@code excludeOnEdges} on, the same
     * mis-sized tally silently discards an object instead of throwing.
     */
    @Test
    public void theSmallestDisagreementIsTwoVoxels() {
        ImagePlus input = twoVoxels(8, 8, 4);
        try {
            Map<String, Box> classic = classicObjects(input);
            Map<String, Box> unified = unifiedObjects(input);
            System.out.println("=== excludeOnEdges, two-voxel reproducer ===");
            System.out.println("  Counter3D kept          " + classic.keySet());
            System.out.println("  StreamingLabeller kept  " + unified.keySet());

            assertEquals("Counter3D drops the interior voxel along with the corner", 0, classic.size());
            assertEquals("the new engine keeps the interior voxel and drops the corner",
                    1, unified.size());
            Box kept = unified.values().iterator().next();
            assertEquals("the surviving object is the interior voxel", "4,6,3 1x1x1 n=1", kept.key());
            assertTrue("and it is genuinely interior", !kept.touchesEdge(9, 9, 5));
        } finally {
            Stacks.discard(input);
        }
    }

    /**
     * The same two voxels with the corner moved one slice earlier, which leaves it
     * just as edge-touching but no longer last in scan order. The engines agree.
     *
     * <p>Pairing this with {@link #theSmallestDisagreementIsTwoVoxels} is what
     * separates the two candidate explanations. If the loss were about edge-ness,
     * moving the corner within the border would change nothing. It changes
     * everything, so the cause is finality in scan order.
     */
    @Test
    public void theFinalVoxelIsWhatBreaksIt() {
        ImagePlus input = twoVoxels(8, 8, 3);
        try {
            Map<String, Box> classic = classicObjects(input);
            Map<String, Box> unified = unifiedObjects(input);
            System.out.println("=== excludeOnEdges, corner one slice earlier ===");
            System.out.println("  Counter3D kept          " + classic.keySet());
            System.out.println("  StreamingLabeller kept  " + unified.keySet());
            assertEquals("moving the corner off the final voxel makes the engines agree",
                    classic.keySet(), unified.keySet());
            assertEquals("both keep the interior voxel alone", 1, unified.size());
        } finally {
            Stacks.discard(input);
        }
    }

    /**
     * A 9x9x5 volume holding one interior voxel at (4,6,3) and one corner voxel at
     * the given position. Minimised from a random 9x9x5 volume of 71 voxels by
     * removing voxels while the disagreement survived.
     */
    private static ImagePlus twoVoxels(int cornerX, int cornerY, int cornerZ) {
        ImagePlus image = Stacks.bytes("two-voxels", 9, 9, 5);
        Stacks.set(image, 4, 6, 3, Stacks.FOREGROUND);
        Stacks.set(image, cornerX, cornerY, cornerZ, Stacks.FOREGROUND);
        return image;
    }

    /** Sparse noise in a small volume: many objects, most of them touching a border. */
    private static ImagePlus randomVolume(Random random, int index) {
        int width = 6 + random.nextInt(7);
        int height = 6 + random.nextInt(7);
        int depth = 3 + random.nextInt(4);
        double density = 0.15 + random.nextDouble() * 0.35;
        ImagePlus image = Stacks.bytes("sweep-" + index, width, height, depth);
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (random.nextDouble() < density) {
                        Stacks.set(image, x, y, z, Stacks.FOREGROUND);
                    }
                }
            }
        }
        return image;
    }

    private static void compare(Tally tally, String source, ImagePlus input) {
        Map<String, Box> classic;
        try {
            classic = classicObjects(input);
        } catch (RuntimeException classicFailed) {
            // corner-x1y1z1 and any sweep volume whose final voxel is isolated
            // throw out of the shipped counter. That defect is pinned by
            // Counter3DDefectTest; here it just means there is no "before" set.
            tally.rows.add("| `" + source + "` | - | - | - | "
                    + classicFailed.getClass().getSimpleName()
                    + " out of Counter3D, so no comparison is possible |");
            return;
        }
        tally.compared++;
        tally.classify(source, input, classic, unifiedObjects(input));
    }

    private static Map<String, Box> classicObjects(ImagePlus input) {
        ObjectsCounter3DWrapper.Result result = new ReferenceEngines().run(
                input, THRESHOLD, 1, Integer.MAX_VALUE, true, false, false, false);
        return boxes(result.getStatistics());
    }

    private static Map<String, Box> unifiedObjects(ImagePlus input) {
        ImagePlus labels = null;
        try {
            OC3DPlusResult result = OC3DPlus.count(input, OC3DPlus.builder()
                    .threshold(THRESHOLD).minSize(1).excludeOnEdges(true).build());
            labels = result.labelImage();
            return boxes(result.statistics());
        } finally {
            Stacks.discard(labels);
        }
    }

    private static Map<String, Box> boxes(ResultsTable table) {
        Map<String, Box> out = new LinkedHashMap<String, Box>();
        if (table == null) return out;
        for (int row = 0; row < table.size(); row++) {
            Box box = new Box(
                    (int) Math.round(table.getValue("BX", row)),
                    (int) Math.round(table.getValue("BY", row)),
                    (int) Math.round(table.getValue("BZ", row)),
                    (int) Math.round(table.getValue("B-width", row)),
                    (int) Math.round(table.getValue("B-height", row)),
                    (int) Math.round(table.getValue("B-depth", row)),
                    Math.round(table.getValue("Nb of obj. voxels", row)));
            // Two objects can share a key only if they share a bounding box and a
            // voxel count. Numbering the duplicate keeps the comparison total
            // rather than silently dropping one.
            String key = box.key();
            for (int suffix = 2; out.containsKey(key); suffix++) {
                key = box.key() + " #" + suffix;
            }
            out.put(key, box);
        }
        return out;
    }

    private static List<Box> missingFrom(Map<String, Box> source, Map<String, Box> other) {
        List<Box> out = new ArrayList<Box>();
        for (Map.Entry<String, Box> entry : source.entrySet()) {
            if (!other.containsKey(entry.getKey())) out.add(entry.getValue());
        }
        return out;
    }

    private static void write(Tally tally, int corpusCompared, int corpusDiffering)
            throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# `excludeOnEdges` object-set differences\n\n");
        out.append("Written by `ExcludeOnEdgesEnumerationTest`. Every Case A fixture plus ")
                .append(SWEEP_VOLUMES).append(" seeded random volumes (seed ").append(SWEEP_SEED)
                .append("), each run through both engines at threshold ").append(THRESHOLD)
                .append(", `minSize=1`, `excludeOnEdges=true`, with the surviving object sets ")
                .append("matched by bounding box and voxel count.\n\n");
        out.append("**").append(tally.compared).append(" volumes compared, ")
                .append(tally.differing).append(" differ:  ")
                .append(corpusDiffering).append(" of ").append(corpusCompared)
                .append(" fixtures, ")
                .append(tally.differing - corpusDiffering).append(" of ")
                .append(tally.compared - corpusCompared).append(" sweep volumes.**\n\n");
        out.append("| Direction | Count |\n|---|---|\n");
        out.append("| `Counter3D` kept an object that touches a border | ")
                .append(tally.counter3dKeptAnEdgeObject).append(" |\n");
        out.append("| `Counter3D` dropped an object that touches no border | ")
                .append(tally.counter3dDroppedAnInteriorObject).append(" |\n\n");

        out.append("## The declared direction, and the one that was observed\n\n");
        out.append("TOLERANCES.md §4 declared this difference from the Stage 2 bytecode ")
                .append("reading: `Counter3D.findObjects` flags edge contact against a ")
                .append("provisional id, `replaceID` does not carry the flag across a later ")
                .append("merge, and so an edge-touching object survives a filter it should ")
                .append("fail. That predicts `Counter3D` **keeping** objects the new engine ")
                .append("drops. Objects of that kind found here: ")
                .append(tally.counter3dKeptAnEdgeObject).append(".\n\n");
        out.append("The direction that *is* reproducible runs the other way, and is a ")
                .append("different defect: `Counter3D` **drops** an object that touches no ")
                .append("border at all, whenever the volume's final voxel in scan order is an ")
                .append("isolated object. That is the `IDcount` off-by-one of ")
                .append("`Counter3DDefectTest`, which throws with `excludeOnEdges` off and ")
                .append("silently loses an object with it on. Instances found here: ")
                .append(tally.counter3dDroppedAnInteriorObject).append(".\n\n");
        out.append("Neither count is the standing evidence, because a sweep this size can ")
                .append("miss a rare shape. The standing evidence is a pair of tests that run ")
                .append("every time: `theSmallestDisagreementIsTwoVoxels` reduces the second ")
                .append("direction to **two voxels** in a 9x9x5 volume, and ")
                .append("`theFinalVoxelIsWhatBreaksIt` moves the corner one slice earlier - ")
                .append("just as edge-touching, no longer last - where the engines agree ")
                .append("again. That pair is what shows the cause is finality in scan order ")
                .append("rather than edge-ness. Nothing comparable exists for the declared ")
                .append("direction, which is not disproved, only unobserved.\n\n");
        out.append("Both directions would be fixes. In the observed one the new engine keeps ")
                .append("an object that should never have been dropped, so nothing is lost.\n\n");

        if (tally.rows.isEmpty()) {
            out.append("No differences found.\n");
        } else {
            out.append("| Source | Volume | Kept by | Object (x,y,z w x h x d n=voxels) | Verdict |\n");
            out.append("|---|---|---|---|---|\n");
            for (int i = 0; i < tally.rows.size(); i++) {
                out.append(tally.rows.get(i)).append('\n');
            }
        }
        File directory = REPORT.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory);
        }
        Files.write(REPORT.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + REPORT.getAbsolutePath());
    }
}
