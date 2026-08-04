package sc.fiji.oc3dplus.equivalence;

import ij.ImagePlus;
import org.junit.Test;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusResult;

import static org.junit.Assert.assertEquals;

/**
 * The connectivity discriminators of harness section 6.
 *
 * <p>Connectivity was settled on 2026-08-03: both existing paths use
 * 26-connectivity and they agree, which is why no pre-existing disagreement has to
 * be adjudicated and both the Case A and Case B references stand.
 * {@code Utilities.Counter3D.minAntTag} scans the 13 anterior members of the
 * 26-neighbourhood and {@code findObjects} sweeps the full 3x3x3 in its second
 * pass; {@code mcib3d.image3d.ImageLabeller.getLabels(ImageHandler)} passes
 * {@code false} for {@code connectivity6}, dispatching to {@code labelSpots26}.
 *
 * <p>These assertions are kept anyway. They are cheap, and they catch a regression
 * in the <em>wiring</em> - a fixture routed to the wrong engine, an option not
 * reaching the labeller - rather than in the algorithm, which is what an
 * equivalence harness is for.
 */
public class ConnectivityDiscriminatorTest {

    @Test
    public void twoCubesSharingAFaceAreOneObject() {
        assertObjectCount("conn-face", 1);
    }

    @Test
    public void twoCubesSharingAnEdgeAreOneObjectUnder26Connectivity() {
        assertObjectCount("conn-edge", 1);
    }

    @Test
    public void twoCubesSharingACornerAreOneObjectUnder26Connectivity() {
        assertObjectCount("conn-corner", 1);
    }

    @Test
    public void aDiagonalVoxelChainThroughZIsOneObjectUnder26Connectivity() {
        assertObjectCount("conn-diag-z", 1);
    }

    /** Foreground is {@code value >= threshold}, pinned with connectivity. */
    @Test
    public void foregroundIncludesVoxelsEqualToTheThreshold() {
        assertObjectCount("single-voxel", Stacks.FOREGROUND, 1);
        assertObjectCount("single-voxel", Stacks.FOREGROUND + 1, 0);
    }

    /**
     * Edges are x=0, y=0, x=width-1, y=height-1, and z=0 and z=depth-1 only when
     * depth is greater than one: a single-slice stack has no z edge. So excluding
     * edge objects must not remove an interior object from a one-slice stack.
     */
    @Test
    public void aSingleSliceStackHasNoZEdge() {
        assertObjectCount("single-slice", 1);
        ImagePlus input = FixtureCorpus.byName("single-slice").createInput();
        ImagePlus labels = null;
        try {
            OC3DPlusResult result = OC3DPlus.count(input, OC3DPlus.builder()
                    .threshold(100).minSize(1).excludeOnEdges(true).build());
            labels = result.labelImage();
            assertEquals("an interior object in a one-slice stack touches no edge, "
                            + "so edge exclusion must keep it",
                    1, result.objectCount());
        } finally {
            Stacks.discard(labels);
            Stacks.discard(input);
        }
    }

    private static void assertObjectCount(String fixtureName, int expected) {
        assertObjectCount(fixtureName, 100, expected);
    }

    private static void assertObjectCount(String fixtureName, int threshold, int expected) {
        ImagePlus input = FixtureCorpus.byName(fixtureName).createInput();
        ImagePlus labels = null;
        try {
            OC3DPlusResult result = OC3DPlus.count(input, OC3DPlus.builder()
                    .threshold(threshold).minSize(1).build());
            labels = result.labelImage();
            assertEquals(fixtureName + " at threshold " + threshold, expected, result.objectCount());
        } finally {
            Stacks.discard(labels);
            Stacks.discard(input);
        }
    }
}
