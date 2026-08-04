package sc.fiji.oc3dplus;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;
import sc.fiji.oc3dplus.api.OC3DPlus;
import sc.fiji.oc3dplus.api.OC3DPlusParameters;
import sc.fiji.oc3dplus.api.OC3DPlusResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Stress test for {@link OC3DPlus#countAll}. Builds several synthetic stacks
 * with known blob counts, runs them in parallel, and asserts every result
 * matches the expected object count. Also verifies determinism by running
 * the same input twice and comparing.
 */
public class ParallelCountTest {

    private static final int W = 20, H = 20, D = 20;

    @Test
    public void emptyInputReturnsEmptyList() {
        List<OC3DPlusResult> results = OC3DPlus.countAll(
                Collections.<ImagePlus>emptyList(),
                OC3DPlus.builder().threshold(100).minSize(1).build(),
                4);
        assertTrue(results.isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullInputThrows() {
        OC3DPlus.countAll(null, OC3DPlus.builder().build(), 4);
    }

    @Test
    public void workerFailureNamesImageIndexAndTitle() {
        ImagePlus broken = new ImagePlus() {
            @Override public String getTitle() {
                return "broken-stack";
            }

            @Override public int getWidth() {
                return 0;
            }

            @Override public int getHeight() {
                return 0;
            }

            @Override public ImageStack getImageStack() {
                return null;
            }
        };
        try {
            OC3DPlus.countAll(Collections.singletonList(broken),
                    OC3DPlus.builder().threshold(100).minSize(1).build(),
                    1);
            fail("expected RuntimeException");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("images[0]"));
            assertTrue(expected.getMessage().contains("broken-stack"));
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void parallelCountMatchesPerImageCountSerially() {
        int[] expected = new int[] { 1, 2, 3, 4 };
        List<ImagePlus> images = new ArrayList<ImagePlus>();
        for (int blobs : expected) {
            images.add(buildStackWithNBlobs(blobs));
        }

        OC3DPlusParameters params = OC3DPlus.builder()
                .threshold(100).minSize(1)
                .build();

        List<OC3DPlusResult> results = OC3DPlus.countAll(images, params, 4);

        assertEquals(expected.length, results.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals("image " + i + " should have " + expected[i] + " blobs",
                    expected[i], results.get(i).objectCount());
        }
    }

    @Test
    public void parallelCountIsDeterministic() {
        List<ImagePlus> images = new ArrayList<ImagePlus>();
        for (int blobs : new int[] { 3, 5, 2, 4 }) {
            images.add(buildStackWithNBlobs(blobs));
        }
        OC3DPlusParameters params = OC3DPlus.builder()
                .threshold(100).minSize(1)
                .addFilter("volume", ">=", 1)
                .build();

        List<OC3DPlusResult> first = OC3DPlus.countAll(images, params, 4);
        List<OC3DPlusResult> second = OC3DPlus.countAll(images, params, 4);

        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).objectCount(), second.get(i).objectCount());
            assertEquals(first.get(i).statistics().size(),
                    second.get(i).statistics().size());
        }
    }

    @Test
    public void threadsLessThanOneFallsBackToProcessorCount() {
        // Just verify the API doesn't crash when threads <= 0; result correctness
        // is the contract.
        List<ImagePlus> images = Arrays.asList(
                buildStackWithNBlobs(1),
                buildStackWithNBlobs(2));
        OC3DPlusParameters params = OC3DPlus.builder().threshold(100).minSize(1).build();

        List<OC3DPlusResult> r0 = OC3DPlus.countAll(images, params, 0);
        List<OC3DPlusResult> rNeg = OC3DPlus.countAll(images, params, -3);

        assertEquals(2, r0.size());
        assertEquals(2, rNeg.size());
        assertEquals(1, r0.get(0).objectCount());
        assertEquals(2, r0.get(1).objectCount());
    }

    // ─── helpers ──────────────────────────────────────────────────────

    /** Build a 20^3 stack containing N non-touching 3x3x3 cubes along z=2..4. */
    private static ImagePlus buildStackWithNBlobs(int n) {
        ImageStack stack = new ImageStack(W, H);
        for (int z = 0; z < D; z++) {
            ByteProcessor bp = new ByteProcessor(W, H);
            if (z >= 2 && z < 5) {
                for (int i = 0; i < n; i++) {
                    int x0 = 2 + i * 4;
                    int y0 = 8;
                    for (int dy = 0; dy < 3; dy++) {
                        for (int dx = 0; dx < 3; dx++) {
                            bp.set(x0 + dx, y0 + dy, 200);
                        }
                    }
                }
            }
            stack.addSlice(bp);
        }
        return new ImagePlus("synthetic-" + n + "-blobs", stack);
    }
}
