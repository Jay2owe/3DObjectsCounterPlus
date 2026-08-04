package sc.fiji.oc3dplus.api;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OC3DPlusFacadeTest {

    @Test
    public void builderDefaults() {
        OC3DPlusParameters params = OC3DPlus.builder().build();
        assertEquals(0, params.threshold);
        assertEquals(10, params.minSize);
        assertEquals(Integer.MAX_VALUE, params.maxSize);
        assertFalse(params.excludeOnEdges);
        assertEquals(0, params.morphPredicates.size());
        assertSame(OC3DPlusParameters.NO_OP_WARNING_SINK, params.warningSink);
    }

    @Test
    public void builderFluentChain() {
        OC3DPlusParameters params = OC3DPlus.builder()
                .threshold(128)
                .minSize(20)
                .maxSize(50_000)
                .excludeOnEdges(true)
                .addFilter("sphericity", ">=", 0.6)
                .addFilter("volume", ">=", 100)
                .build();
        assertEquals(128, params.threshold);
        assertEquals(20, params.minSize);
        assertEquals(50_000, params.maxSize);
        assertEquals(2, params.morphPredicates.size());
        assertEquals("sphericity", params.morphPredicates.get(0).featureName);
        assertEquals(MorphPredicate.Operator.GE, params.morphPredicates.get(0).op);
        assertEquals(0.6, params.morphPredicates.get(0).value, 1e-12);
    }

    @Test
    public void builderAddFilterAcceptsAllOperators() {
        OC3DPlusParameters params = OC3DPlus.builder()
                .addFilter("a", ">=", 1.0)
                .addFilter("b", "<=", 2.0)
                .addFilter("c", ">", 3.0)
                .addFilter("d", "<", 4.0)
                .build();
        assertEquals(MorphPredicate.Operator.GE, params.morphPredicates.get(0).op);
        assertEquals(MorphPredicate.Operator.LE, params.morphPredicates.get(1).op);
        assertEquals(MorphPredicate.Operator.GT, params.morphPredicates.get(2).op);
        assertEquals(MorphPredicate.Operator.LT, params.morphPredicates.get(3).op);
    }

    @Test
    public void builderAddFilterRejectsUnknownOperator() {
        try {
            OC3DPlus.builder().addFilter("volume", "==", 1.0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void builderAddFilterRejectsNullPredicate() {
        try {
            OC3DPlus.builder().addFilter(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void builderRejectsNegativeSizes() {
        try {
            OC3DPlus.builder().minSize(-1);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            OC3DPlus.builder().maxSize(-5);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void builderRejectsMaxBelowMin() {
        try {
            OC3DPlus.builder().minSize(100).maxSize(50).build();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void countRejectsNullImage() {
        try {
            OC3DPlus.count(null, OC3DPlus.builder().build());
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void countRejectsImageWithNoPixels() {
        try {
            OC3DPlus.count(new ImagePlus(), OC3DPlus.builder().build());
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("non-empty image slice"));
        }
    }

    @Test
    public void countRejectsMismatchedIntensityDimensions() {
        ImagePlus image = new ImagePlus("image", new ByteProcessor(5, 5));
        ImagePlus intensity = new ImagePlus("intensity", new ByteProcessor(6, 5));
        try {
            OC3DPlus.count(image, OC3DPlus.builder().intensityImage(intensity).build());
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("intensityImage dimensions"));
        }
    }

    @Test
    public void detectRejectsNullImage() {
        try {
            OC3DPlus.detect(null, 128, 10, Integer.MAX_VALUE, false, null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void detectRejectsMismatchedIntensityDimensions() {
        ImagePlus image = new ImagePlus("image", new ByteProcessor(5, 5));
        ImagePlus intensity = new ImagePlus("intensity", new ByteProcessor(5, 6));
        try {
            OC3DPlus.detect(image, 128, 10, Integer.MAX_VALUE, false, intensity);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("intensityImage dimensions"));
        }
    }

    @Test
    public void resultDefaultsForEmpty() {
        OC3DPlusResult empty = new OC3DPlusResult(null, null, null, null);
        assertEquals(0, empty.objectCount());
        assertFalse(empty.foundObjects());
        assertEquals(0, empty.survivingPerFilter().length);
        assertEquals(0, empty.filterLabels().length);
    }

    @Test
    public void resultArraysAreDefensivelyCopied() {
        int[] counts = new int[] { 5, 3 };
        String[] labels = new String[] { "a>=1", "b<=2" };
        OC3DPlusResult r = new OC3DPlusResult(null, null, counts, labels);

        counts[0] = 999;
        labels[0] = "tampered";

        assertEquals(5, r.survivingPerFilter()[0]);
        assertEquals("a>=1", r.filterLabels()[0]);
    }
}
